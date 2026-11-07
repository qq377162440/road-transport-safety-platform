package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.mapper.TplTemplateMapper;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台账范本库服务 —— 「台账范本管理」与「电子档案柜」的唯一同源数据层。
 *
 * <p>数据模型：
 * <ul>
 *   <li>服务商全局范本：tenant_id=0、group_id=4001、status=1（V10 落库的用户真实范本，只读基线）</li>
 *   <li>企业当前应用范本：tenant_id=当前企业、source_id=全局范本 id，同 code 覆盖全局内容</li>
 * </ul>
 * 查询一律「企业覆盖优先、全局兜底」，因此在范本管理中保存修改后，
 * 电子档案柜的生成与预览会立刻读到同一份新内容，实现两模块联动。
 */
@Service
public class TplLibraryService {

    /** 服务商默认范本套 id（V2 seed: 4001 普货通用范本） */
    private static final long GLOBAL_SET_ID = 4001L;

    private final TplTemplateMapper templateMapper;
    private final TplRenderEngine renderEngine;
    private final PdfService pdfService;
    private final TenantService tenantService;
    private final SealService sealService;

    public TplLibraryService(TplTemplateMapper templateMapper, TplRenderEngine renderEngine,
                             PdfService pdfService, TenantService tenantService, SealService sealService) {
        this.templateMapper = templateMapper;
        this.renderEngine = renderEngine;
        this.pdfService = pdfService;
        this.tenantService = tenantService;
        this.sealService = sealService;
    }

    /** 当前企业 id（平台账号无租户，返回 null，仅使用全局范本） */
    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? null : t;
    }

    /** 全局范本（服务商内置，只读基线） */
    private List<TplTemplate> globals() {
        return templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                .eq(TplTemplate::getStatus, 1)
                .orderByAsc(TplTemplate::getBoxNo)
                .orderByAsc(TplTemplate::getId));
    }

    /** 本企业对全局范本的私有覆盖 */
    private Map<String, TplTemplate> overrides() {
        Long t = tid();
        if (t == null) return Map.of();
        List<TplTemplate> list = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getStatus, 1));
        Map<String, TplTemplate> map = new LinkedHashMap<>();
        for (TplTemplate x : list) map.put(x.getCode(), x);
        return map;
    }

    /** 按 code 取「当前生效」范本（企业覆盖优先） */
    public TplTemplate effective(String code) {
        TplTemplate own = overrides().get(code);
        if (own != null) return own;
        return templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getCode, code)
                .eq(TplTemplate::getStatus, 1)
                .last("limit 1"));
    }

    /** 按归档项名称取可用范本列表（档案柜生成/预览用），主范本排前面 */
    public List<TplTemplate> forItem(String archiveItem, Integer boxNo) {
        List<TplTemplate> all = effectiveList();
        List<TplTemplate> exact = new ArrayList<>();
        List<TplTemplate> fuzzy = new ArrayList<>();
        for (TplTemplate t : all) {
            String ai = t.getArchiveItem() == null ? "" : t.getArchiveItem();
            if (boxNo != null && t.getBoxNo() != null && t.getBoxNo() != boxNo) continue;
            if (ai.equals(archiveItem)) exact.add(t);
            else if (!ai.isEmpty() && (archiveItem.contains(ai) || ai.contains(archiveItem))) fuzzy.add(t);
        }
        if (!exact.isEmpty()) {
            exact.sort(Comparator.comparingInt((TplTemplate t) -> "RESP_LETTER".equals(t.getCode()) ? 0 : 1)
                    .thenComparing(TplTemplate::getId));
            return exact;
        }
        return fuzzy;
    }

    /**
     * 当前企业生效的完整范本清单（全局 + 私有覆盖合并），按档案盒分组排序。
     * customized=true 表示该范本已被本企业修改过，可「恢复内置」。
     */
    public List<TplVO> list() {
        Map<String, TplTemplate> own = overrides();
        List<TplVO> out = new ArrayList<>();
        for (TplTemplate g : globals()) {
            TplTemplate e = own.remove(g.getCode());
            TplTemplate active = e != null ? e : g;
            out.add(toVO(active, e != null, g));
        }
        // 企业自建（全局没有的）范本也一并列出
        for (TplTemplate e : own.values()) out.add(toVO(e, true, null));
        out.sort(Comparator.comparingInt((TplVO v) -> v.getBoxNo() == null ? 99 : v.getBoxNo())
                .thenComparing(v -> v.getCode() == null ? "" : v.getCode()));
        return out;
    }

    /** 清单 + 每个范本的正文（供前端一次性缓存，避免逐条请求） */
    public List<TplVO> listWithContent() {
        List<TplVO> list = list();
        for (TplVO v : list) v.setContent(effective(v.getCode()).getContent());
        return list;
    }

    private List<TplTemplate> effectiveList() {
        List<TplTemplate> out = new ArrayList<>();
        Map<String, TplTemplate> own = overrides();
        for (TplTemplate g : globals()) {
            TplTemplate e = own.remove(g.getCode());
            out.add(e != null ? e : g);
        }
        out.addAll(own.values());
        return out;
    }

    private TplVO toVO(TplTemplate t, boolean customized, TplTemplate base) {
        TplVO v = new TplVO();
        v.setId(t.getId());
        v.setCode(t.getCode());
        v.setName(t.getName());
        v.setCategory(t.getCategory());
        v.setBoxNo(t.getBoxNo());
        v.setArchiveItem(t.getArchiveItem());
        v.setArchiveCode(t.getArchiveCode());
        v.setVersion(t.getVersion());
        v.setVersionLabel(t.getVersionLabel());
        v.setCustomized(customized);
        v.setBaseContent(base == null ? null : base.getContent());
        return v;
    }

    /** 保存本范本：写/更新本企业私有覆盖；全局基线不动 */
    @Transactional
    public TplVO save(String code, String content, String versionLabel) {
        TplTemplate global = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getCode, code)
                .eq(TplTemplate::getStatus, 1)
                .last("limit 1"));
        if (global == null) throw new BizException("内置范本不存在：" + code);
        Long t = tid();
        if (t == null) throw new BizException("平台账号请通过服务商范本套维护全局范本");

        TplTemplate own = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getCode, code)
                .last("limit 1"));
        if (own == null) {
            own = new TplTemplate();
            own.setTenantId(t);
            own.setGroupId(GLOBAL_SET_ID);
            own.setSourceId(global.getId());
            own.setCode(code);
            own.setName(global.getName());
            own.setCategory(global.getCategory());
            own.setArchiveCode(global.getArchiveCode());
            own.setArchiveItem(global.getArchiveItem());
            own.setBoxNo(global.getBoxNo());
            own.setPostScope(global.getPostScope());
            own.setNeedSeal(global.getNeedSeal());
            own.setVersion(2);
            own.setStatus(1);
            own.setCreatedBy(0L);
            own.setDeleted(0);
        } else {
            own.setVersion((own.getVersion() == null ? 1 : own.getVersion()) + 1);
            own.setStatus(1);
        }
        own.setContent(content);
        own.setVersionLabel(versionLabel == null || versionLabel.isBlank() ? "本企业定制" : versionLabel);
        if (own.getId() == null) templateMapper.insert(own);
        else templateMapper.updateById(own);
        return toVO(own, true, global);
    }

    /** 恢复内置：删除本企业私有覆盖，回到服务商全局范本 */
    @Transactional
    public void reset(String code) {
        Long t = tid();
        if (t == null) return;
        templateMapper.delete(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getCode, code));
    }

    /**
     * 当前范本是否盖章：原型规则「所有台账（PDF）都要盖公司章」，
     * need_seal 缺省或非空均视为盖章，仅显式 0 才不盖。
     */
    private String sealFor(TplTemplate tpl) {
        Integer need = tpl.getNeedSeal();
        return (need != null && need == 0) ? null : sealService.sealHtml();
    }

    /** 渲染后的正文片段（前端 A4 预览容器内嵌） */
    public String renderBody(String code, String itemName, int year, String post) {
        TplTemplate tpl = effective(code);
        if (tpl == null) throw new BizException("范本不存在：" + code);
        Map<String, String> vars = renderEngine.defaultVars(
                tenantService.currentTenantName(), year, post, itemName);
        return renderEngine.renderBody(tpl.getContent(), vars, sealFor(tpl));
    }

    /** 渲染后的完整 HTML 文档（PDF 生成用） */
    public String renderDocument(String code, String itemName, int year, String post) {
        TplTemplate tpl = effective(code);
        if (tpl == null) throw new BizException("范本不存在：" + code);
        Map<String, String> vars = renderEngine.defaultVars(
                tenantService.currentTenantName(), year, post, itemName);
        return renderEngine.renderDocument(tpl.getContent(), vars, sealFor(tpl));
    }

    /** 直接渲染为 PDF 字节（范本管理「预览 PDF / 打印」用） */
    public byte[] renderPdf(String code, String itemName, int year, String post) {
        return pdfService.render(renderDocument(code, itemName, year, post));
    }

    /** 统一版式 CSS（前端 A4 预览容器复用，保证屏幕与 PDF 一致） */
    public String pageCss() {
        return renderEngine.pageCss();
    }

    /**
     * 联动编辑：修改某岗位的目标/职责/考核，同时写回《安全生产责任制》与
     * 该岗位《安全生产责任书》两份范本（原型 template_manager.html 的联动编辑口径）。
     */
    @Transactional
    public void saveResponsibility(String post, List<String> target, List<String> duty, List<String> assess) {
        if (post == null || post.isBlank()) throw new BizException("请选择岗位");

        // 1) 责任制：只替换该岗位区块，保留其他岗位与用户的其他修改
        TplTemplate sys = effective("RESPONSIBILITY");
        if (sys != null) {
            String html = sys.getContent() == null ? "" : sys.getContent();
            String marker = "<div class=\"resp-post\" data-post=\"" + escapeAttr(post) + "\">";
            String block = buildRespPostBlock(post, target, duty, assess);
            int start = html.indexOf(marker);
            if (start >= 0) {
                int end = balancedDivEnd(html, start + marker.length());
                html = html.substring(0, start) + block + html.substring(end);
            } else {
                html += block;
            }
            save("RESPONSIBILITY", html, "联动编辑 · " + post);
        }

        // 2) 该岗位责任书：整份重建，与责任制三段内容保持一致
        TplTemplate letter = null;
        for (TplVO v : list()) {
            if (v.getCode() != null && v.getCode().startsWith("RESP_LETTER_P")
                    && v.getName() != null && v.getName().contains(post)) {
                letter = effective(v.getCode());
                break;
            }
        }
        if (letter == null) letter = effective("RESP_LETTER_P1");
        if (letter != null) {
            save(letter.getCode(), buildRespBook(post, target, duty, assess), "联动编辑 · " + post);
        }
    }

    private String buildRespPostBlock(String post, List<String> target, List<String> duty, List<String> assess) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"resp-post\" data-post=\"").append(escapeAttr(post)).append("\">");
        h.append("<div class=\"doc-h1\">").append(esc(post)).append("</div>");
        h.append(seg("target", "安全生产目标", post, target));
        h.append(seg("duty", "安全职责", post, duty));
        h.append(seg("assess", "考核标准", post, assess));
        return h.append("</div>").toString();
    }

    private String seg(String key, String label, String post, List<String> lines) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"resp-").append(key).append("\" data-label=\"").append(label).append("\">");
        h.append("<div class=\"doc-h2\">").append(esc(post)).append(" · ").append(label).append("</div>");
        for (String s : lines == null ? List.<String>of() : lines) {
            h.append("<div class=\"doc-li\">").append(esc(s)).append("</div>");
        }
        return h.append("</div>").toString();
    }

    private String buildRespBook(String post, List<String> target, List<String> duty, List<String> assess) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"resp-book\" data-post=\"").append(escapeAttr(post)).append("\">");
        b.append("<div class=\"doc-h0\">安全生产责任书（").append(esc(post)).append("）</div>");
        b.append("<div class=\"doc-p\">为落实安全生产责任制，明确").append(esc(post))
                .append("岗位安全生产职责，特签订本责任书。</div>");
        String[] labels = {"一、安全生产目标", "二、安全职责", "三、考核标准"};
        String[] keys = {"target", "duty", "assess"};
        List<List<String>> groups = List.of(
                target == null ? List.of() : target,
                duty == null ? List.of() : duty,
                assess == null ? List.of() : assess);
        for (int i = 0; i < 3; i++) {
            b.append("<div class=\"resp-").append(keys[i]).append("\" data-label=\"")
                    .append(labels[i]).append("\"><div class=\"doc-h1\">").append(labels[i]).append("</div>");
            for (String s : groups.get(i)) b.append("<div class=\"doc-li\">").append(esc(s)).append("</div>");
            b.append("</div>");
        }
        b.append("<div class=\"doc-sign\"><div>责任管理人（上级签字）：<span class=\"fill\">{{leader_name}}</span></div>");
        b.append("<div>责任人（本人签字）：<span class=\"fill\">{{person_name}}</span></div>");
        b.append("<div>签订日期：<span class=\"fill\">{{sign_date}}</span></div></div></div>");
        return b.toString();
    }

    /** 从 from 起找到与起始 div 配对的 </div> 结束位置 */
    private int balancedDivEnd(String s, int from) {
        Matcher m = Pattern.compile("<div\\b|</div>").matcher(s);
        m.region(from, s.length());
        int depth = 1;
        while (m.find()) {
            if ("</div>".equals(m.group())) {
                if (--depth == 0) return m.end();
            } else depth++;
        }
        return s.length();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeAttr(String s) {
        return esc(s).replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 范本清单视图对象 */
    @Data
    public static class TplVO {
        private Long id;
        private String code;
        private String name;
        private String category;
        private Integer boxNo;
        private String archiveItem;
        private String archiveCode;
        private Integer version;
        private String versionLabel;
        private boolean customized;
        /** 可选：正文（listWithContent 时填充） */
        private String content;
        /** 可选：内置基线正文（用于「查看差异 / 恢复内置」） */
        private String baseContent;
    }
}
