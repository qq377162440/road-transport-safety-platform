package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 印章引擎 —— 实现《09-静态资料范本引擎》第六章「印章引擎（已确认）」与
 * 原型 范本引擎原型测试-v3.html 的「标准圆形公章」。
 *
 * <p>原型阶段确认的规则：
 * <ul>
 *   <li>两种来源：客户上传真实印章图（优先） / 系统按公司全称自动生成圆形红章</li>
 *   <li>规格：圆形、直径 40mm（≈148px）、#D0021B 印泥红、上弧公司全称（字多则字小）、
 *       中心五角星、外圈实线 + 内圈细线</li>
 *   <li>时机：生成台账时自动盖在落款处，章略倾斜压住落款文字（骑年压月）</li>
 *   <li>全局：所有台账 PDF 都要盖公司章</li>
 * </ul>
 *
 * <p>实现要点：原型用 SVG 弧形排字，但后端 PDF 引擎（openhtmltopdf 1.0.10）不带 Batik，
 * 不支持内联 SVG；因此这里用 Java2D 按同一套几何参数绘制成透明 PNG，再以 data URI
 * 嵌入 HTML。前端 A4 预览与 PDF 因此共用完全相同的图片，做到「屏幕所见即 PDF 所得」。
 */
@Service
public class SealService {

    /** 印泥红（原型 / 详细设计指定） */
    private static final Color RED = new Color(0xD0, 0x02, 0x1B);

    /** 逻辑画布 200×200（与原型 SVG viewBox 一致），物理输出 2 倍保证清晰 */
    private static final int LOGICAL = 200;
    private static final int SCALE = 2;

    /** 上弧文字基线半径 / 总弧度 / 字号分档（与原型 v3 renderSeal 完全一致） */
    private static final double TEXT_R = 68;
    private static final double TOTAL_ARC = 232;

    private static final Map<String, String> AUTO_CACHE = new ConcurrentHashMap<>();
    private static volatile Font sealFont;

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;

    public SealService(JdbcTemplate jdbc, TenantService tenantService) {
        this.jdbc = jdbc;
        this.tenantService = tenantService;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? null : t;
    }

    /** 当前企业名称（自动章的上弧文字来源） */
    public String companyName() {
        try {
            String n = tenantService.currentTenantName();
            return (n == null || n.isBlank()) ? "公司名称" : n.trim();
        } catch (Exception e) {
            return "公司名称";
        }
    }

    /** 当前企业生效的印章：客户上传章优先，否则按公司名自动生成 */
    public String dataUri() {
        Long t = tid();
        if (t != null) {
            List<String> rows = jdbc.queryForList(
                    "SELECT seal_image FROM tpl_seal WHERE tenant_id = ? AND seal_type = 1 "
                            + "AND is_default = 1 AND deleted = 0 LIMIT 1",
                    String.class, t);
            if (!rows.isEmpty() && rows.get(0) != null && rows.get(0).startsWith("data:image/")) {
                return rows.get(0);
            }
        }
        return autoDataUri(companyName());
    }

    /** 当前印章信息（管理页展示用） */
    public SealInfo current() {
        SealInfo info = new SealInfo();
        info.setCompanyName(companyName());
        Long t = tid();
        boolean uploaded = false;
        if (t != null) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tpl_seal WHERE tenant_id = ? AND seal_type = 1 "
                            + "AND is_default = 1 AND deleted = 0",
                    Integer.class, t);
            uploaded = n != null && n > 0;
        }
        info.setSource(uploaded ? "upload" : "auto");
        info.setLabel(uploaded ? "客户上传的真实印章" : "系统自动生成 · 内部文件章");
        info.setDataUri(dataUri());
        return info;
    }

    /**
     * 落款处注入的印章 HTML 片段（渲染引擎调用）。
     * 用块级 + text-align + 负 margin 实现「骑年压月」，不依赖 CSS absolute 定位：
     * openhtmltopdf 对 left:100% / transform 支持不完整，会导致 PDF 里章被推出页面裁切。
     */
    public String sealHtml() {
        return "<div class=\"seal-box\"><img class=\"seal-img\" src=\"" + dataUri()
                + "\" alt=\"公司印章\" /></div>";
    }

    /** 上传真实印章（透明底 PNG 等），以 data URI 存 tpl_seal */
    public void upload(String image) {
        Long t = tid();
        if (t == null) throw new BizException("请先以企业账号登录后再上传印章");
        if (image == null || !image.startsWith("data:image/") || !image.contains(";base64,")) {
            throw new BizException("印章图片格式不正确，请上传透明底 PNG 图片");
        }
        if (image.length() > 3_000_000) throw new BizException("印章图片过大，请压缩到 2MB 以内");
        jdbc.update("UPDATE tpl_seal SET is_default = 0, deleted = 1 WHERE tenant_id = ? AND seal_type = 1", t);
        jdbc.update("INSERT INTO tpl_seal (id, tenant_id, seal_type, seal_image, is_default, created_by, deleted) "
                        + "VALUES (?, ?, 1, ?, 1, 0, 0)",
                IdWorker.getId(), t, image);
        AUTO_CACHE.clear();
    }

    /** 恢复系统自动生成的圆形红章 */
    public void reset() {
        Long t = tid();
        if (t == null) return;
        jdbc.update("UPDATE tpl_seal SET is_default = 0, deleted = 1 WHERE tenant_id = ? AND seal_type = 1", t);
        AUTO_CACHE.clear();
    }

    /** 按公司名生成圆形红章 PNG（供管理与预览直接调用） */
    public byte[] autoPng(String company) {
        String name = company == null ? "" : company.replaceAll("\\s+", "");
        if (name.isEmpty()) name = "公司名称";
        // 公司全称过长时按真实公章习惯截断，避免字符重叠
        char[] chars = name.length() > 24 ? name.substring(0, 24).toCharArray() : name.toCharArray();
        int n = chars.length;

        BufferedImage img = new BufferedImage(LOGICAL * SCALE, LOGICAL * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(SCALE, SCALE);
        // 原型：章略倾斜 -7°、半透明 0.88，画进图片里，避免依赖 CSS transform
        g.rotate(Math.toRadians(-7), LOGICAL / 2.0, LOGICAL / 2.0);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f));
        g.setColor(RED);

        // 外圈实线 r=92（线宽 5）+ 内圈细线 r=86（线宽 1）
        g.setStroke(new BasicStroke(5f));
        g.drawOval(8, 8, 184, 184);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(14, 14, 172, 172);

        // 上弧公司全称：每个字符按角度定位并旋转，字头朝外
        float fs = n <= 10 ? 22 : (n <= 14 ? 19 : (n <= 18 ? 16 : 13.5f));
        Font font = font().deriveFont(Font.BOLD, fs);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        double startAngle = 270 - TOTAL_ARC / 2;
        double step = n > 1 ? TOTAL_ARC / (n - 1) : 0;
        for (int i = 0; i < n; i++) {
            double angle = startAngle + step * i;
            double rad = Math.toRadians(angle);
            double x = 100 + TEXT_R * Math.cos(rad);
            double y = 100 + TEXT_R * Math.sin(rad);
            String ch = String.valueOf(chars[i]);
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(Math.toRadians(angle + 90));
            g.drawString(ch, -fm.stringWidth(ch) / 2f, (fm.getAscent() - fm.getDescent()) / 2f);
            g.setTransform(old);
        }

        // 中心五角星（外接圆半径 24 / 内 9.4，起始 -90°）
        int[] xs = new int[10];
        int[] ys = new int[10];
        for (int i = 0; i < 10; i++) {
            double r = (i % 2 == 0) ? 24 : 9.4;
            double a = Math.toRadians(-90 + i * 36);
            xs[i] = (int) Math.round(100 + r * Math.cos(a));
            ys[i] = (int) Math.round(100 + r * Math.sin(a));
        }
        g.fillPolygon(xs, ys, 10);
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("印章图片生成失败：" + e.getMessage(), e);
        }
    }

    public String autoDataUri(String company) {
        String name = company == null || company.isBlank() ? "公司名称" : company;
        return AUTO_CACHE.computeIfAbsent(name, k -> "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(autoPng(k)));
    }

    /** 中文字体：用 PDF 引擎同款文泉驿正黑 TTF，保证弧上中文不缺字 */
    private static Font font() {
        if (sealFont == null) {
            synchronized (SealService.class) {
                if (sealFont == null) {
                    try (InputStream in = new ClassPathResource("fonts/wqy-zenhei.ttf").getInputStream()) {
                        sealFont = Font.createFont(Font.TRUETYPE_FONT, in);
                    } catch (Exception e) {
                        sealFont = new Font(Font.SERIF, Font.BOLD, 20);
                    }
                }
            }
        }
        return sealFont;
    }

    @Data
    public static class SealInfo {
        private String source;      // auto | upload
        private String label;
        private String companyName;
        private String dataUri;
    }
}
