package com.safetrans.org.service;

import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 架构图位图生成器（PDF 专用）。
 *
 * <p>屏幕端用 CSS flex 连线树（与原型 org_chart.js 同源），但 openhtmltopdf
 * 不支持 flex / 伪元素连线，直接用同一份 HTML 渲染 PDF 会导致树形塌陷。
 * 因此 PDF 版改用 Java2D 按同一套视觉规范绘制高清 PNG 后内嵌 data URI，
 * 复用印章引擎（SealService）已验证的「Java2D + 中文字体 + base64」链路。
 *
 * <p>视觉规范与原型一致：公司节点紫色、总经理紫色、其余岗位蓝色，
 * 节点含岗位名/职能副标题/人员/兼并提示，连线为淡蓝色直角折线。
 */
public final class OrgChartPng {

    private OrgChartPng() {
    }

    /** 2 倍分辨率输出，A4 横向缩放后仍清晰 */
    private static final int S = 2;
    private static final int NODE_W = 156;
    private static final int COMPANY_W = 240;
    private static final int NODE_H = 54;
    private static final int MERGE_H = 20;
    private static final int LEVEL_H = 92;
    private static final int GAP_X = 20;
    private static final int MARGIN = 26;

    private static final Color LINE = new Color(0xA8, 0xC4, 0xE8);
    private static final Color BORDER = new Color(0xC9, 0xD9, 0xF0);
    private static final Color BLUE = new Color(0x25, 0x63, 0xEB);
    private static final Color PURPLE = new Color(0x7C, 0x3A, 0xED);
    private static final Color PERSON_BG = new Color(0xDB, 0xEA, 0xFE);
    private static final Color PERSON_INK = new Color(0x1E, 0x40, 0xAF);
    private static final Color DIM = new Color(0x64, 0x74, 0x8B);
    private static final Color WHITE = Color.WHITE;

    private static volatile Font baseFont;

    private static Font font() {
        if (baseFont == null) {
            synchronized (OrgChartPng.class) {
                if (baseFont == null) {
                    try (InputStream in = new ClassPathResource("fonts/wqy-zenhei.ttf").getInputStream()) {
                        baseFont = Font.createFont(Font.TRUETYPE_FONT, in);
                    } catch (Exception e) {
                        baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
                    }
                }
            }
        }
        return baseFont;
    }

    /** 绘制布局盒 */
    private static final class Box {
        String name;
        String label;
        String person;
        String merged;
        boolean company;
        boolean gm;
        int w;
        int h;
        int cx;
        int top;
        final List<Box> kids = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Box build(Map<String, Object> node, boolean company) {
        Box b = new Box();
        b.company = company;
        b.gm = !company && "gm".equals(node.get("funcKey"));
        b.name = company ? str(node.get("company")) : str(node.get("name"));
        b.label = company ? "" : str(node.get("label"));
        b.person = company ? "" : str(node.get("person"));
        Object mg = node.get("merged");
        List<String> merged = mg instanceof List<?> ? (List<String>) mg : new ArrayList<>();
        b.merged = merged.isEmpty() ? "" : "兼并：" + String.join("、", merged);
        b.w = company ? COMPANY_W : NODE_W;
        b.h = company ? 34 : NODE_H + (b.merged.isEmpty() ? 0 : MERGE_H);
        Object children = node.get("children");
        if (!company && children instanceof List<?>) {
            for (Object o : (List<?>) children) {
                if (o instanceof Map<?, ?>) b.kids.add(build((Map<String, Object>) o, false));
            }
        }
        return b;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 递归布局：返回下一个可用的左边界 */
    private static int place(Box b, int left, int depth) {
        b.top = MARGIN + depth * LEVEL_H;
        if (b.kids.isEmpty()) {
            b.cx = left + b.w / 2;
            return left + b.w + GAP_X;
        }
        int cursor = left;
        for (Box k : b.kids) cursor = place(k, cursor, depth + 1);
        b.cx = (b.kids.get(0).cx + b.kids.get(b.kids.size() - 1).cx) / 2;
        return cursor;
    }

    private static void collect(Box b, List<Box> all) {
        all.add(b);
        for (Box k : b.kids) collect(k, all);
    }

    /**
     * 生成 PNG 的 data URI。
     *
     * @param company 企业名称（公司根节点）
     * @param roots   启用岗位树（后端 OrgChartService.chart() 的 tree）
     */
    public static String dataUri(String company, List<Map<String, Object>> roots) {
        Map<String, Object> fake = new java.util.HashMap<>();
        fake.put("company", company == null ? "企业名称" : company);
        fake.put("children", roots);
        Box root = build(fake, true);
        // build() 对公司节点不递归 children，这里单独挂上岗位树
        for (Map<String, Object> n : roots) root.kids.add(build(n, false));

        int right = place(root, MARGIN, 0);
        List<Box> all = new ArrayList<>();
        collect(root, all);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxBottom = 0;
        for (Box b : all) {
            minX = Math.min(minX, b.cx - b.w / 2);
            maxX = Math.max(maxX, b.cx + b.w / 2);
            maxBottom = Math.max(maxBottom, b.top + b.h);
        }
        int shift = MARGIN - minX;
        int width = (maxX - minX + MARGIN * 2);
        int height = maxBottom + MARGIN;
        for (Box b : all) b.cx += shift;

        int cw = Math.max(width, 400);
        int ch = Math.max(height, 160);
        // 先按内容实际尺寸高清绘制，再等比缩放进固定 2200x1000 画布（比例 2.2:1）。
        // A4 横向正文区约 273x186mm，图片按 255mm 宽显示时高度约 116mm，
        // 连同页眉/标题/页脚可稳稳落在一页内，不会把页脚挤到第二页。
        BufferedImage content = new BufferedImage(cw * S, ch * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = content.createGraphics();
        g.setColor(WHITE);
        g.fillRect(0, 0, content.getWidth(), content.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(S, S);

        // 先画连线，再画节点（连线在卡片下层）
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.2f));
        for (Box b : all) drawLinks(g, b);
        for (Box b : all) drawNode(g, b);
        g.dispose();

        final int CANVAS_W = 2200, CANVAS_H = 1000;
        BufferedImage canvas = new BufferedImage(CANVAS_W * S, CANVAS_H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = canvas.createGraphics();
        cg.setColor(WHITE);
        cg.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale = Math.min((double) CANVAS_W / cw, (double) CANVAS_H / ch);
        int dw = (int) Math.round(cw * scale), dh = (int) Math.round(ch * scale);
        int dx = (CANVAS_W - dw) / 2, dy = (CANVAS_H - dh) / 2;
        cg.drawImage(content, dx * S, dy * S, dw * S, dh * S, null);
        cg.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("架构图绘制失败：" + e.getMessage(), e);
        }
    }

    private static void drawLinks(Graphics2D g, Box b) {
        if (b.kids.isEmpty()) return;
        int fromY = b.top + b.h;
        // 拐点取父子层间距的中点，层高压缩后连线仍居中、不贴卡片
        int midY = fromY + Math.max(8, (b.kids.get(0).top - fromY) / 2);
        int first = b.kids.get(0).cx;
        int last = b.kids.get(b.kids.size() - 1).cx;
        g.drawLine(b.cx, fromY, b.cx, midY);
        if (first != last) g.drawLine(first, midY, last, midY);
        for (Box k : b.kids) g.drawLine(k.cx, midY, k.cx, k.top);
    }

    private static void drawNode(Graphics2D g, Box b) {
        int x = b.cx - b.w / 2;
        int y = b.top;
        int arc = 8;

        // 卡片底 + 边框
        g.setColor(WHITE);
        g.fillRoundRect(x, y, b.w, b.h, arc, arc);
        Stroke old = g.getStroke();
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, b.w, b.h, arc, arc);
        g.setStroke(old);

        int titleH = b.company ? 34 : (b.label.isEmpty() || b.label.equals(b.name) ? 30 : 34);
        Color head = (b.company || b.gm) ? PURPLE : BLUE;
        // 标题条限制在圆角裁剪内，保持上圆角
        java.awt.Shape clip = g.getClip();
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(x, y, b.w, b.h, arc, arc));
        g.setColor(head);
        g.fillRect(x, y, b.w, titleH);
        g.setClip(clip);

        Font f = font();
        g.setColor(WHITE);
        if (b.company) {
            g.setFont(f.deriveFont(Font.BOLD, 13f));
            drawCenter(g, b.name, b.cx, y + 23);
        } else {
            g.setFont(f.deriveFont(Font.BOLD, 13f));
            drawCenter(g, b.name, b.cx, y + (titleH == 34 ? 15 : 20));
            if (titleH == 34) {
                g.setFont(f.deriveFont(Font.PLAIN, 10.5f));
                drawCenter(g, b.label, b.cx, y + 29);
            }
        }

        if (b.company) return;

        int personH = 24;
        int personY = y + titleH;
        g.setColor(PERSON_BG);
        g.fillRect(x, personY, b.w, personH);
        g.setColor(b.person.isEmpty() ? DIM : PERSON_INK);
        g.setFont(f.deriveFont(b.person.isEmpty() ? Font.PLAIN : Font.BOLD, 12f));
        drawCenter(g, b.person.isEmpty() ? "待配置" : b.person, b.cx, personY + 17);

        if (!b.merged.isEmpty()) {
            int my = personY + personH;
            g.setColor(PERSON_BG);
            g.fillRect(x, my, b.w, MERGE_H);
            g.setColor(LINE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{3f, 3f}, 0f));
            g.drawLine(x + 8, my, x + b.w - 8, my);
            g.setStroke(old);
            g.setColor(PERSON_INK);
            g.setFont(f.deriveFont(Font.PLAIN, 10f));
            drawCenter(g, clipText(g, b.merged, b.w - 12), b.cx, my + 15);
        }
    }

    private static void drawCenter(Graphics2D g, String text, int cx, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, cx - fm.stringWidth(text) / 2, baseline);
    }

    private static String clipText(Graphics2D g, String text, int maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxW) return text;
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(sb + String.valueOf(c) + "…") > maxW) break;
            sb.append(c);
        }
        return sb + "…";
    }
}
