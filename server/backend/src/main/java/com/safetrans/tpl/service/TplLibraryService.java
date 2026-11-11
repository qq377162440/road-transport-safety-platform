package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.mapper.TplTemplateMapper;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台账范本库服务 —— 「台账范本管理」与「电子档案柜」的唯一同源数据层。
 *
 * <p>范本套作用域（V17 起）：
 * <ul>
 *   <li>企业「当前应用范本套」由 ent_tpl_set.is_current=1 决定；其范本内容落在
 *       tpl_template（tenant_id=企业、group_id=ent_tpl_set.id），是该套的独立副本。</li>
 *   <li>切换范本套 → 本服务所有读取（清单/正文/匹配）随之改变 → 电子档案柜按当前套生成归档。</li>
 *   <li>企业尚未创建应用套（或平台账号）→ 回退到服务商全局默认套 4001 + 按 code 私有覆盖的旧行为，
 *       保证既有数据与回归完全兼容。</li>
 * </ul>
 */
@Service
public class TplLibraryService {

    /** 服务商默认范本套 id（V2 seed: 4001 普货通用范本） */
    private static final long GLOBAL_SET_ID = TplSetScope.GLOBAL_SET_ID;

    private final TplTemplateMapper templateMapper;
    private final TplRenderEngine renderEngine;
    private final PdfService pdfService;
    private final TenantService tenantService;
    private final SealService sealService;
    private final TplPersonResolver personResolver;
    private final TplSetScope setScope;
    private final JdbcTemplate jdbc;

    public TplLibraryService(TplTemplateMapper templateMapper, TplRenderEngine renderEngine,
                             PdfService pdfService, TenantService tenantService, SealService sealService,
                             TplPersonResolver personResolver, TplSetScope setScope, JdbcTemplate jdbc) {
        this.templateMapper = templateMapper;
        this.renderEngine = renderEngine;
        this.pdfService = pdfService;
        this.tenantService = tenantService;
        this.sealService = sealService;
        this.personResolver = personResolver;
        this.setScope = setScope;
        this.jdbc = jdbc;
    }

    /** 当前企业 id（平台账号无租户，返回 null，仅使用全局范本） */
    private Long tid() {
        return setScope.tid();
    }

    /** 当前应用范本套的内容作用域；null 表示走全局默认套旧行为 */
    private Long groupId() {
        return setScope.currentGroupId();
    }

    /**
     * 「当前生效」的范本基线：
     * 有当前应用套 → 该企业该套的独立副本；无 → 服务商全局默认套 4001。
     */
    private List<TplTemplate> baseTemplates() {
        Long g = groupId();
        if (g != null) {
            List<TplTemplate> inSet = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, tid())
                    .eq(TplTemplate::getGroupId, g)
                    .eq(TplTemplate::getStatus, 1)
                    .orderByAsc(TplTemplate::getBoxNo)
                    .orderByAsc(TplTemplate::getId));
            // 迁移新增的内置范本（如 V30 的二级维护计划）不在历史企业套副本里：
            // 把全局组中套内缺失的 code 补进基线，保证新范本对已应用套的企业立即可见、可生成。
            Set<String> codes = inSet.stream().map(TplTemplate::getCode).collect(Collectors.toSet());
            List<TplTemplate> globals = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, 0L)
                    .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                    .eq(TplTemplate::getStatus, 1));
            for (TplTemplate gl : globals) {
                if (gl.getCode() != null && !codes.contains(gl.getCode())) inSet.add(gl);
            }
            return inSet;
        }
        return templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                .eq(TplTemplate::getStatus, 1)
                .orderByAsc(TplTemplate::getBoxNo)
                .orderByAsc(TplTemplate::getId));
    }

    /**
     * 旧模式（无应用套）下企业对全局范本的按 code 私有覆盖。
     * 有应用套时范本内容本身即企业私有行，不再需要覆盖层，返回空。
     */
    private Map<String, TplTemplate> overrides() {
        Long t = tid();
        // 必须返回可变 Map：list()/effectiveList() 会对它做 remove 消费。
        // 有当前应用套时无覆盖层，但仍需可变的空 Map，不能返回 Map.of()。
        if (t == null || groupId() != null) return new LinkedHashMap<>();
        List<TplTemplate> list = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                .eq(TplTemplate::getStatus, 1));
        Map<String, TplTemplate> map = new LinkedHashMap<>();
        for (TplTemplate x : list) map.put(x.getCode(), x);
        return map;
    }

    /** 应用套内某范本的「内置基线正文」（source_id 指向的全局范本），用于定制判定与恢复内置 */
    private TplTemplate sourceOf(TplTemplate row) {
        if (row.getSourceId() == null) return null;
        return templateMapper.selectById(row.getSourceId());
    }

    /** 按 code 取「当前生效」范本（企业套内容 / 旧模式覆盖 / 全局兜底，含套内缺失的全局新增范本） */
    public TplTemplate effective(String code) {
        Long g = groupId();
        if (g != null) {
            TplTemplate inSet = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, tid())
                    .eq(TplTemplate::getGroupId, g)
                    .eq(TplTemplate::getCode, code)
                    .eq(TplTemplate::getStatus, 1)
                    .last("limit 1"));
            if (inSet != null) return inSet;
            // 套内没有（迁移新增的内置范本未同步进历史企业套）→ 兜底全局
        }
        TplTemplate own = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, tid() == null ? -1L : tid())
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                .eq(TplTemplate::getCode, code)
                .eq(TplTemplate::getStatus, 1)
                .last("limit 1"));
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

    /** 当前生效的完整范本清单（按档案盒分组排序） */
    public List<TplVO> list() {
        Long g = groupId();
        Map<String, TplTemplate> own = overrides();
        List<TplVO> out = new ArrayList<>();
        for (TplTemplate base : baseTemplates()) {
            TplTemplate e = own.remove(base.getCode());
            TplTemplate active = e != null ? e : base;
            boolean customized;
            String baseContent;
            if (g != null) {
                TplTemplate src = sourceOf(base);
                baseContent = src == null ? null : src.getContent();
                customized = baseContent == null || !Objects.equals(baseContent, active.getContent());
            } else {
                customized = e != null;
                baseContent = base.getContent();
            }
            out.add(toVO(active, customized, baseContent));
        }
        // 旧模式下企业自建（全局没有的）范本也一并列出
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
        for (TplTemplate base : baseTemplates()) {
            TplTemplate e = own.remove(base.getCode());
            out.add(e != null ? e : base);
        }
        out.addAll(own.values());
        return out;
    }

    private TplVO toVO(TplTemplate t, boolean customized, String baseContent) {
        TplVO v = new TplVO();
        v.setId(t.getId());
        v.setCode(t.getCode());
        v.setName(t.getName());
        v.setCategory(t.getCategory());
        v.setBoxNo(t.getBoxNo());
        v.setArchiveItem(t.getArchiveItem());
        v.setArchiveCode(t.getArchiveCode());
        v.setCatalogId(t.getCatalogId());
        v.setVersion(t.getVersion());
        v.setVersionLabel(t.getVersionLabel());
        v.setCustomized(customized);
        v.setBaseContent(baseContent);
        return v;
    }

    /** 保存本范本：有应用套则改本套副本；旧模式则写按 code 私有覆盖 */
    @Transactional
    public TplVO save(String code, String content, String versionLabel) {
        Long t = tid();
        Long g = groupId();
        String label = versionLabel == null || versionLabel.isBlank() ? "本企业定制" : versionLabel;

        if (g != null) {
            TplTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, t)
                    .eq(TplTemplate::getGroupId, g)
                    .eq(TplTemplate::getCode, code)
                    .last("limit 1"));
            if (row == null) {
                // 套内缺失的迁移新增范本：首次定制时把全局行复制进套，再按定制内容保存
                TplTemplate global = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                        .eq(TplTemplate::getTenantId, 0L)
                        .eq(TplTemplate::getCode, code)
                        .eq(TplTemplate::getStatus, 1)
                        .last("limit 1"));
                if (global == null) throw new BizException("当前应用范本套内不存在范本：" + code);
                row = new TplTemplate();
                row.setTenantId(t);
                row.setGroupId(g);
                row.setSourceId(global.getId());
                row.setCode(global.getCode());
                row.setName(global.getName());
                row.setCategory(global.getCategory());
                row.setArchiveCode(global.getArchiveCode());
                row.setBoxNo(global.getBoxNo());
                row.setArchiveItem(global.getArchiveItem());
                row.setCatalogId(global.getCatalogId());
                row.setVersionLabel(global.getVersionLabel());
                row.setPostScope(global.getPostScope());
                row.setContent(global.getContent());
                row.setNeedSeal(global.getNeedSeal());
                row.setVersion(1);
                row.setStatus(1);
                row.setCreatedBy(0L);
                templateMapper.insert(row);
            }
            row.setContent(content);
            row.setVersionLabel(label);
            row.setVersion((row.getVersion() == null ? 1 : row.getVersion()) + 1);
            row.setStatus(1);
            templateMapper.updateById(row);
            TplTemplate src = sourceOf(row);
            return toVO(row, true, src == null ? null : src.getContent());
        }

        // ---- 旧行为（无应用套）----
        TplTemplate global = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getCode, code)
                .eq(TplTemplate::getStatus, 1)
                .last("limit 1"));
        if (global == null) throw new BizException("内置范本不存在：" + code);
        if (t == null) throw new BizException("平台账号请通过服务商范本套维护全局范本");

        TplTemplate own = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
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
            own.setCatalogId(global.getCatalogId());
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
        own.setVersionLabel(label);
        if (own.getId() == null) templateMapper.insert(own);
        else templateMapper.updateById(own);
        return toVO(own, true, global.getContent());
    }

    /** 恢复内置：套内副本还原为来源全局范本正文；旧模式删除私有覆盖 */
    @Transactional
    public void reset(String code) {
        Long t = tid();
        if (t == null) return;
        Long g = groupId();
        if (g != null) {
            TplTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, t)
                    .eq(TplTemplate::getGroupId, g)
                    .eq(TplTemplate::getCode, code)
                    .last("limit 1"));
            if (row == null) return;
            TplTemplate src = sourceOf(row);
            if (src == null) return;
            row.setContent(src.getContent());
            row.setVersionLabel(src.getVersionLabel());
            row.setVersion((row.getVersion() == null ? 1 : row.getVersion()) + 1);
            row.setStatus(1);
            templateMapper.updateById(row);
            return;
        }
        templateMapper.delete(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, t)
                .eq(TplTemplate::getGroupId, GLOBAL_SET_ID)
                .eq(TplTemplate::getCode, code));
    }

    // ===== 范本 ↔ 电子档案柜归档项映射 =====

    /**
     * 当前应用范本套与电子档案柜的对应关系。
     * 范本侧：每份范本对应哪个归档项（catalog_id）；
     * 档案柜侧：每个归档项是否有范本（无范本 = 营业执照等需手动上传的证件）。
     */
    public Map<String, Object> mapping() {
        List<Map<String, Object>> catalog = jdbc.queryForList(
                "SELECT c.`id`, c.`name`, c.`parent_id`, p.`sort_no` AS box_no, p.`name` AS box_name " +
                        "FROM `archive_catalog` c JOIN `archive_catalog` p ON p.`id`=c.`parent_id` " +
                        "WHERE c.`biz_type`='GENERAL' AND c.`level`=2 AND c.`deleted`=0 AND p.`deleted`=0 " +
                        "ORDER BY p.`sort_no`, c.`sort_no`, c.`id`");
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (Map<String, Object> c : catalog) nameById.put(((Number) c.get("id")).longValue(), String.valueOf(c.get("name")));

        List<TplVO> tpls = list();
        Map<Long, List<String>> tplByCatalog = new LinkedHashMap<>();
        List<Map<String, Object>> tplRows = new ArrayList<>();
        int mapped = 0;
        for (TplVO v : tpls) {
            Long cid = v.getCatalogId() == null ? 0L : v.getCatalogId();
            String cname = nameById.getOrDefault(cid, "");
            boolean exact = cname.equals(v.getArchiveItem() == null ? "" : v.getArchiveItem());
            if (cid != 0 && !cname.isEmpty()) mapped++;
            tplByCatalog.computeIfAbsent(cid, k -> new ArrayList<>()).add(v.getCode());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", v.getCode());
            row.put("name", v.getName());
            row.put("boxNo", v.getBoxNo());
            row.put("archiveItem", v.getArchiveItem());
            row.put("catalogId", cid);
            row.put("catalogName", cname);
            row.put("matchType", cid == 0 ? "none" : (exact ? "exact" : "grouped"));
            row.put("customized", v.isCustomized());
            tplRows.add(row);
        }

        List<Map<String, Object>> catalogRows = new ArrayList<>();
        List<Map<String, Object>> uploadOnly = new ArrayList<>();
        for (Map<String, Object> c : catalog) {
            long cid = ((Number) c.get("id")).longValue();
            List<String> codes = tplByCatalog.getOrDefault(cid, List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cid);
            row.put("name", c.get("name"));
            row.put("boxNo", c.get("box_no"));
            row.put("boxName", c.get("box_name"));
            row.put("tplCodes", codes);
            row.put("hasTemplate", !codes.isEmpty());
            catalogRows.add(row);
            if (codes.isEmpty()) uploadOnly.add(row);
        }

        EntTplSet cur = setScope.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentSetId", cur == null ? null : String.valueOf(cur.getId()));
        out.put("currentSetName", cur == null ? "普货通用范本（内置）" : cur.getName());
        out.put("currentSourceSetId", cur == null ? GLOBAL_SET_ID : cur.getSourceSetId());
        out.put("templates", tplRows);
        out.put("catalogItems", catalogRows);
        out.put("uploadOnly", uploadOnly);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("templateCount", tpls.size());
        summary.put("mappedCount", mapped);
        summary.put("catalogItemCount", catalogRows.size());
        summary.put("uploadOnlyCount", uploadOnly.size());
        out.put("summary", summary);
        return out;
    }

    /**
     * 当前范本是否盖章：原型规则「所有台账（PDF）都要盖公司章」。
     * <b>只在成品层生效</b> —— 台账范本管理的空白范本不盖章。
     */
    private String sealFor(TplTemplate tpl) {
        Integer need = tpl.getNeedSeal();
        return (need != null && need == 0) ? null : sealService.sealHtml();
    }

    private TplTemplate mustGet(String code) {
        TplTemplate tpl = effective(code);
        if (tpl == null) throw new BizException("范本不存在：" + code);
        return tpl;
    }

    private Map<String, String> blankVars(String post) {
        return renderEngine.defaultVars(null, java.time.Year.now().getValue(), post, null);
    }

    // ===== 模板层：台账范本管理（空白范本，无章、无真实人名）=====

    /** 空白范本正文：变量保持 {{}} 占位，管理员看的是「待填版式」 */
    public String renderBlankBody(String code, String post) {
        return renderEngine.renderBlankBody(mustGet(code).getContent(), blankVars(post));
    }

    public String renderBlankDocument(String code, String post) {
        return renderEngine.renderBlankDocument(mustGet(code).getContent(), blankVars(post));
    }

    public byte[] renderBlankPdf(String code, String post) {
        return pdfService.render(renderBlankDocument(code, post));
    }

    // ===== 成品层：电子档案柜（真实企业信息 + 岗位人名 + 公司章）=====

    /** 成品正文片段（档案柜 A4 预览容器内嵌） */
    public String renderBody(String code, String itemName, int year, String post) {
        TplTemplate tpl = mustGet(code);
        Map<String, String> vars = renderEngine.defaultVars(
                tenantService.currentTenantName(), year, post, itemName);
        personResolver.apply(vars, renderEngine.resolveConditions(tpl.getContent(), vars), post);
        return renderEngine.renderBody(tpl.getContent(), vars, sealFor(tpl));
    }

    /** 成品完整 HTML 文档（PDF 生成用） */
    public String renderDocument(String code, String itemName, int year, String post) {
        TplTemplate tpl = mustGet(code);
        Map<String, String> vars = renderEngine.defaultVars(
                tenantService.currentTenantName(), year, post, itemName);
        personResolver.apply(vars, renderEngine.resolveConditions(tpl.getContent(), vars), post);
        return renderEngine.renderDocument(tpl.getContent(), vars, sealFor(tpl));
    }

    /** 直接渲染为成品 PDF（电子档案柜生成归档用，自动使用当前应用范本套） */
    public byte[] renderPdf(String code, String itemName, int year, String post) {
        return pdfService.render(renderDocument(code, itemName, year, post));
    }

    /** 生成前检查：该范本在当前岗位下读不到人名的字段清单 */
    public List<TplPersonResolver.Missing> checkMissing(String code, String post) {
        TplTemplate tpl = mustGet(code);
        Map<String, String> vars = renderEngine.defaultVars(
                tenantService.currentTenantName(), java.time.Year.now().getValue(), post, null);
        return personResolver.check(renderEngine.resolveConditions(tpl.getContent(), vars), post);
    }

    /** 当前企业岗位人员名册（档案柜补录弹窗用） */
    public List<Map<String, Object>> roster() {
        return personResolver.roster();
    }

    /** 为岗位补录人名（提醒闭环） */
    public String assignPerson(String post, String name) {
        return personResolver.assign(post, name);
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
        /** 对应电子档案柜归档项 id */
        private Long catalogId;
        private Integer version;
        private String versionLabel;
        private boolean customized;
        /** 可选：正文（listWithContent 时填充） */
        private String content;
        /** 可选：内置基线正文（用于「查看差异 / 恢复内置」） */
        private String baseContent;
    }
}
