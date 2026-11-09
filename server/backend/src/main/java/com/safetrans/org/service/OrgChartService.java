package com.safetrans.org.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.service.TplRevisionLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 安全管理架构图服务（按原型 prototype/pc/org.html + assets/org_chart.js 正式版）。
 *
 * <p>数据全部来自 PC 端同一批真实业务表：org_post / org_member / person / tenant，
 * 不另建静态假数据。核心规则与原型 OrgChart.normalize() 完全一致：
 * <ol>
 *   <li>只渲染启用岗位，禁用岗位不以灰色占位出现；</li>
 *   <li>禁用中间层的启用下级沿 parent_id 链「上提」到最近的启用祖先；</li>
 *   <li>防环 + 按职能序号稳定排序，总经理恒为岗位树根；</li>
 *   <li>架构图不显示「强制」角标，强制岗位只在岗位信息管理页标注。</li>
 * </ol>
 */
@Service
public class OrgChartService {

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;
    private final PdfService pdfService;
    private final TplRevisionLogService revisionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrgChartService(JdbcTemplate jdbc, TenantService tenantService, PdfService pdfService,
                           TplRevisionLogService revisionService) {
        this.jdbc = jdbc;
        this.tenantService = tenantService;
        this.pdfService = pdfService;
        this.revisionService = revisionService;
    }

    /** 12 个系统职能：序号 + 职能副标题（企业侧不可改，对齐原型 org_data.js） */
    private static final Map<String, Object[]> FUNCS = new LinkedHashMap<>();
    /** 默认上级关系（取消兼并时恢复层级用） */
    private static final Map<String, String> DEFAULT_PARENT = new HashMap<>();

    static {
        FUNCS.put("gm", new Object[]{1, "主要负责人"});
        FUNCS.put("safe", new Object[]{2, "安全管理员"});
        FUNCS.put("disp", new Object[]{3, "运营调度员"});
        FUNCS.put("mon", new Object[]{4, "动态监控管理员"});
        FUNCS.put("clerk", new Object[]{5, "行政办公管理员"});
        FUNCS.put("finance", new Object[]{6, "财务"});
        FUNCS.put("captain", new Object[]{7, "车队长"});
        FUNCS.put("vehtech", new Object[]{8, "车辆技术管理员"});
        FUNCS.put("parklot", new Object[]{9, "停车场管理员"});
        FUNCS.put("driver", new Object[]{10, "驾驶员"});
        FUNCS.put("escort", new Object[]{11, "押运员"});
        FUNCS.put("loader", new Object[]{12, "装卸管理员"});

        DEFAULT_PARENT.put("safe", "gm");
        DEFAULT_PARENT.put("disp", "gm");
        DEFAULT_PARENT.put("mon", "safe");
        DEFAULT_PARENT.put("clerk", "gm");
        DEFAULT_PARENT.put("finance", "gm");
        DEFAULT_PARENT.put("captain", "disp");
        DEFAULT_PARENT.put("vehtech", "gm");
        DEFAULT_PARENT.put("parklot", "gm");
        DEFAULT_PARENT.put("driver", "captain");
        DEFAULT_PARENT.put("escort", "captain");
        DEFAULT_PARENT.put("loader", "captain");
    }

    /** 演示企业岗位初始显示名（重置演示数据时恢复，与 V16 初始态一致） */
    private static final Map<String, String> DEMO_NAMES = new HashMap<>();

    static {
        DEMO_NAMES.put("gm", "总经理");
        DEMO_NAMES.put("safe", "安环主管");
        DEMO_NAMES.put("disp", "运营调度员");
        DEMO_NAMES.put("mon", "监控员");
        DEMO_NAMES.put("clerk", "资料员");
        DEMO_NAMES.put("finance", "财务");
        DEMO_NAMES.put("captain", "车队长");
        DEMO_NAMES.put("vehtech", "车辆技术管理员");
        DEMO_NAMES.put("parklot", "停车场管理员");
        DEMO_NAMES.put("driver", "驾驶员");
        DEMO_NAMES.put("escort", "押运员");
        DEMO_NAMES.put("loader", "装卸管理员");
    }

    /** 演示企业岗位初始制度关联（与 V16 迁移一致） */
    private static final Map<String, String> DEMO_DOCS = new HashMap<>();

    static {
        DEMO_DOCS.put("gm", "[\"安全生产责任制\",\"主要负责人任命书\",\"主要负责人告知书\",\"年度安全生产承诺书\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("safe", "[\"安全生产责任制\",\"安全管理人员任命书\",\"安全检查制度\",\"隐患排查治理制度\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("mon", "[\"安全生产责任制\",\"动态监控制度\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("driver", "[\"安全生产责任制\",\"一日三检制度\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("disp", "[\"安全生产责任制\",\"运营调度管理制度\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("captain", "[\"安全生产责任制\",\"车队安全管理制度\",\"一日三检制度\",\"安全生产责任书（岗位版）\"]");
        DEMO_DOCS.put("clerk", "[\"安全生产责任制\",\"档案管理制度\",\"安全生产责任书（岗位版）\"]");
    }

    /** 原型 DOC_POOL：岗位可关联的制度与责任书 */
    public static final List<String> DOC_POOL = Arrays.asList(
            "安全生产责任制", "主要负责人任命书", "主要负责人告知书", "安全管理人员任命书",
            "年度安全生产承诺书", "安全生产责任书（岗位版）", "安全检查制度", "隐患排查治理制度",
            "动态监控制度", "运营调度管理制度", "车队安全管理制度", "一日三检制度",
            "车辆维护保养制度", "停车场管理制度", "装卸管理制度", "安全投入管理制度", "档案管理制度");

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int noOf(String funcKey) {
        Object[] f = FUNCS.get(funcKey);
        return f == null ? 999 : (Integer) f[0];
    }

    private static String labelOf(String funcKey) {
        Object[] f = FUNCS.get(funcKey);
        return f == null ? "自定义岗位" : (String) f[1];
    }

    private List<String> parseDocs(Object raw) {
        String v = s(raw);
        if (v.isEmpty()) return new ArrayList<>();
        try {
            return mapper.readValue(v, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> parseMerged(Object raw) {
        String v = s(raw);
        if (v.isEmpty()) return new ArrayList<>();
        return Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ 读

    /** 架构图聚合数据：一次返回树、统计、人员名册、制度池（弱网友好，也便于 PDF 复用） */
    public Map<String, Object> chart() {
        long t = tid();
        String company = tenantService.currentTenantName();
        List<Map<String, Object>> posts = jdbc.queryForList(
                "SELECT `id`,`parent_id`,`func_key`,`post_name`,`merged_func_ids`,`required`,`enabled`," +
                        "`permissions`,`goals`,`duties`,`assess_std`,`docs` " +
                        "FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0", t);

        // 岗位人员（org_member → person 真实人员库）
        Map<String, List<Map<String, Object>>> members = new HashMap<>();
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT m.`post_id`,p.`id` AS `person_id`,p.`name`,p.`telephone` " +
                        "FROM `org_member` m JOIN `person` p ON p.`id`=m.`user_id` AND p.`deleted`=0 " +
                        "WHERE m.`tenant_id`=? AND m.`deleted`=0 ORDER BY m.`id`", t)) {
            members.computeIfAbsent(s(m.get("post_id")), k -> new ArrayList<>()).add(m);
        }

        // 全部岗位索引（含禁用），用于上提链解析
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> p : posts) byId.put(s(p.get("id")), p);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        int liftedCount = 0;
        for (Map<String, Object> p : posts) {
            String name = s(p.get("post_name"));
            if (!"1".equals(s(p.get("enabled")))) {
                disabled.add(name);
                continue;
            }
            String funcKey = s(p.get("func_key"));
            String id = s(p.get("id"));
            Object[] resolved = resolveParent(p, byId);   // {parentIdOrNull, lifted}
            String parentId = "gm".equals(funcKey) ? null : (String) resolved[0];
            boolean lifted = !"gm".equals(funcKey) && (Boolean) resolved[1];
            if (lifted) liftedCount++;

            List<Map<String, Object>> mem = members.getOrDefault(id, new ArrayList<>());
            String person = mem.isEmpty() ? "" : s(mem.get(0).get("name"));
            String personId = mem.isEmpty() ? "" : s(mem.get(0).get("person_id"));
            int group = mem.size();

            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("funcKey", funcKey);
            n.put("no", noOf(funcKey));
            n.put("name", name);
            n.put("label", labelOf(funcKey));
            n.put("required", "1".equals(s(p.get("required"))));
            n.put("enabled", true);
            n.put("person", person);
            n.put("personId", personId);
            n.put("group", group);
            n.put("merged", parseMerged(p.get("merged_func_ids")).stream()
                    .map(OrgChartService::labelOf).collect(Collectors.toList()));
            n.put("mergedKeys", parseMerged(p.get("merged_func_ids")));
            n.put("parentId", parentId);
            // 编辑弹窗要展示数据库原始上级（架构图里的 parentId 是「上提后」的解析值）
            Object rawParent = p.get("parent_id");
            n.put("rawParentId", (rawParent == null || "0".equals(s(rawParent))) ? "" : s(rawParent));
            n.put("lifted", lifted);
            n.put("permissions", s(p.get("permissions")));
            n.put("goals", s(p.get("goals")));
            n.put("duties", s(p.get("duties")));
            n.put("assess", s(p.get("assess_std")));
            n.put("docs", parseDocs(p.get("docs")));
            nodes.add(n);
        }
        nodes.sort(Comparator.comparingInt(n -> (Integer) n.get("no")));

        List<Map<String, Object>> tree = buildTree(nodes);
        Set<String> active = nodes.stream().map(n -> (String) n.get("funcKey")).collect(Collectors.toSet());
        String tplName = active.containsAll(Arrays.asList("escort", "loader", "vehtech"))
                ? "危货岗位模板"
                : (active.containsAll(Arrays.asList("gm", "safe", "mon", "driver"))
                && active.size() == 4 ? "普货岗位模板" : "企业自定义岗位模板");

        StringBuilder summary = new StringBuilder("当前启用 ").append(nodes.size()).append(" 个岗位");
        if (!disabled.isEmpty()) summary.append("，").append(disabled.size()).append(" 个已禁用岗位不列入本图");
        if (liftedCount > 0) summary.append("，").append(liftedCount)
                .append(" 个岗位因上级禁用已自动上提至最近的启用上级");
        summary.append("。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company);
        out.put("tplName", tplName);
        out.put("date", LocalDate.now().toString());
        out.put("nodes", nodes);
        out.put("tree", tree);
        // PDF / 打印归档专用树（隐藏待配置岗位、下级上提），与下载的架构图完全同源
        out.put("archiveTree", archiveTree(tree));
        out.put("disabled", disabled);
        out.put("disabledCount", disabled.size());
        out.put("enabledCount", nodes.size());
        out.put("lifted", liftedCount);
        out.put("summary", summary.toString());
        out.put("docPool", DOC_POOL);
        out.put("people", people());
        // 含禁用岗位的全量岗位：编辑弹窗的「上级岗位 / 兼并职能」候选（原型 D.posts() 口径）
        List<Map<String, Object>> allPosts = new ArrayList<>();
        for (Map<String, Object> p : posts) {
            String fk = s(p.get("func_key"));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", s(p.get("id")));
            a.put("funcKey", fk);
            a.put("name", s(p.get("post_name")));
            a.put("label", labelOf(fk));
            a.put("no", noOf(fk));
            a.put("enabled", "1".equals(s(p.get("enabled"))));
            a.put("required", "1".equals(s(p.get("required"))));
            allPosts.add(a);
        }
        allPosts.sort(Comparator.comparingInt(a -> (Integer) a.get("no")));
        out.put("allPosts", allPosts);
        return out;
    }

    /** 沿 parent_id 链找最近的启用岗位；跳过所有禁用节点（即原型「上提」），带防环 */
    private Object[] resolveParent(Map<String, Object> p, Map<String, Map<String, Object>> byId) {
        Set<String> seen = new HashSet<>();
        seen.add(s(p.get("id")));
        Object pidObj = p.get("parent_id");
        String pid = (pidObj == null || "0".equals(s(pidObj))) ? null : s(pidObj);
        boolean lifted = false;
        while (pid != null) {
            if (!seen.add(pid)) return new Object[]{null, lifted};   // 环：断开
            Map<String, Object> q = byId.get(pid);
            if (q == null) return new Object[]{null, lifted};        // 上级已不存在
            if ("1".equals(s(q.get("enabled")))) return new Object[]{pid, lifted};
            lifted = true;
            Object next = q.get("parent_id");
            pid = (next == null || "0".equals(s(next))) ? null : s(next);
        }
        return new Object[]{null, lifted};
    }

    /** 组树：按 parentId 挂接、防环兜底、未访问节点降级为根（绝不丢岗位） */
    private List<Map<String, Object>> buildTree(List<Map<String, Object>> nodes) {
        Map<String, List<Map<String, Object>>> kids = new HashMap<>();
        for (Map<String, Object> n : nodes) {
            String k = n.get("parentId") == null ? "__ROOT__" : s(n.get("parentId"));
            kids.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }
        List<Map<String, Object>> roots = new ArrayList<>(kids.getOrDefault("__ROOT__", new ArrayList<>()));
        if (roots.isEmpty() && !nodes.isEmpty()) {
            Map<String, Object> head = nodes.stream().filter(n -> "gm".equals(n.get("funcKey")))
                    .findFirst().orElse(nodes.get(0));
            head.put("parentId", null);
            roots.add(head);
        }
        Comparator<Map<String, Object>> byNo = Comparator.comparingInt(n -> (Integer) n.get("no"));
        Set<String> visited = new HashSet<>();
        walk(roots, new HashSet<>(), visited, kids, byNo);
        for (Map<String, Object> n : nodes) {
            if (!visited.contains(s(n.get("id")))) {
                n.put("children", new ArrayList<Map<String, Object>>());
                roots.add(n);
                visited.add(s(n.get("id")));
            }
        }
        roots.sort(byNo);
        return roots;
    }

    private void walk(List<Map<String, Object>> list, Set<String> ancestors, Set<String> visited,
                      Map<String, List<Map<String, Object>>> kids, Comparator<Map<String, Object>> byNo) {
        for (Map<String, Object> n : list) {
            String id = s(n.get("id"));
            if (visited.contains(id) || ancestors.contains(id)) continue;
            visited.add(id);
            List<Map<String, Object>> cs = new ArrayList<>(
                    kids.getOrDefault(id, new ArrayList<>()))
                    .stream().filter(c -> !s(c.get("id")).equals(id) && !ancestors.contains(s(c.get("id"))))
                    .sorted(byNo).collect(Collectors.toList());
            n.put("children", cs);
            Set<String> next = new HashSet<>(ancestors);
            next.add(id);
            walk(cs, next, visited, kids, byNo);
        }
    }

    /** 系统已录入人员名册（人员一律从名册选取，不手动输入） */
    public List<Map<String, Object>> people() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT `id`,`name`,`telephone`,`post_ids` FROM `person` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s(p.get("id")));
            m.put("name", s(p.get("name")));
            m.put("phone", s(p.get("telephone")));
            out.add(m);
        }
        return out;
    }

    /** 责任制联动预览（由启用岗位动态生成，禁用/兼并岗位自动剔除） */
    public List<Map<String, Object>> responsibility() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) chart().get("nodes");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", n.get("name"));
            r.put("label", n.get("label"));
            r.put("merged", n.get("merged"));
            r.put("goals", n.get("goals"));
            r.put("duties", n.get("duties"));
            r.put("assess", n.get("assess"));
            rows.add(r);
        }
        return rows;
    }

    // ------------------------------------------------------------------ PDF

    /** A4 横向架构图 PDF（真实文件，页面与下载文件同源） */
    public byte[] pdfBytes() {
        return pdfService.render(chartHtml());
    }

    /**
     * PDF / 打印共用的完整 HTML 文档。
     *
     * <p>归档件只保留必要信息：企业名、归档日期、标题、架构图、数据来源。
     * 不输出组树说明、启用/禁用统计、「编制/审核」落款等多余文字；
     * 未配置人员的岗位不列入，其下级上提到最近的已配置祖先（见 archiveTree）。
     */
    public String chartHtml() {
        Map<String, Object> c = chart();
        String company = s(c.get("company"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> archive = (List<Map<String, Object>>) c.get("archiveTree");
        String png = OrgChartPng.dataUri(company, archive == null ? new ArrayList<>() : archive);
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\" />")
                .append("<title>").append(esc(company)).append(" 安全管理组织架构</title><style>")
                .append("@page{size:A4 landscape;margin:12mm;}")
                .append("body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',sans-serif;")
                .append("font-size:10pt;color:#000;margin:0;}")
                .append(".hd{text-align:center;border-bottom:2px solid #000;padding-bottom:6px;margin-bottom:8px;}")
                .append(".hd .org{font-size:15pt;font-weight:bold;letter-spacing:1px;}")
                .append(".hd .meta{font-size:9pt;color:#555;}")
                .append(".ptitle{text-align:center;font-size:20pt;font-weight:bold;letter-spacing:3px;margin:12px 0 16px;}")
                .append(".chart{text-align:center;}")
                .append(".chart img{width:255mm;max-width:100%;}")
                .append(".ft{border-top:1px solid #999;margin-top:12px;padding-top:8px;font-size:9pt;color:#555;}")
                .append("</style></head><body>")
                .append("<div class=\"hd\"><div class=\"org\">").append(esc(company)).append("</div>")
                .append("<div class=\"meta\">安全管理组织架构图　归档日期：").append(esc(s(c.get("date"))))
                .append("</div></div>")
                .append("<div class=\"ptitle\">安全管理组织架构</div>")
                .append("<div class=\"chart\"><img src=\"").append(png).append("\" alt=\"安全管理组织架构图\" /></div>")
                .append("<div class=\"ft\" style=\"text-align:right;\">数据来源：组织架构实时数据</div>")
                .append("</body></html>");
        return h.toString();
    }

    /**
     * 归档用树（PDF / 打印）：隐藏「待配置」岗位（org_member 无人员），
     * 并把其已配置下级沿父链上提到最近的已显示祖先，避免人员被连带丢弃。
     * 屏幕端 /org 页面仍保留待配置岗位，方便管理员通过右键菜单补配人员。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> archiveTree(List<Map<String, Object>> roots) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> n : roots) liftConfigured(n, out, "");
        sortArchive(out);
        return out;
    }

    /**
     * @param siblings     当前层的兄弟列表
     * @param newParentId  本节点若被隐藏，其子节点应挂到的上级 id（空串表示根）
     */
    private static void liftConfigured(Map<String, Object> n, List<Map<String, Object>> siblings,
                                       String newParentId) {
        boolean configured = !s(n.get("person")).isEmpty();
        // 上提后的父由调用方给定；未发生上提时保留数据库原层级
        String effectiveParent = newParentId.isEmpty() ? s(n.get("parentId")) : newParentId;
        Map<String, Object> copy = new LinkedHashMap<>(n);
        copy.put("parentId", effectiveParent.isEmpty() ? null : effectiveParent);
        List<Map<String, Object>> kidsCopy = new ArrayList<>();
        copy.put("children", kidsCopy);
        if (configured) siblings.add(copy);
        Object raw = n.get("children");
        if (!(raw instanceof List<?>)) return;
        // 自身被隐藏时，子节点挂到同一层（相当于上提一级），并改写其 parentId 指向新上级
        List<Map<String, Object>> target = configured ? kidsCopy : siblings;
        String childParent = configured ? s(n.get("id")) : newParentId;
        for (Object o : (List<?>) raw) {
            if (o instanceof Map<?, ?>) liftConfigured((Map<String, Object>) o, target, childParent);
        }
    }

    private static void sortArchive(List<Map<String, Object>> list) {
        list.sort(Comparator.comparingInt(n -> n.get("no") instanceof Number
                ? ((Number) n.get("no")).intValue() : 999));
        for (Map<String, Object> n : list) {
            Object c = n.get("children");
            if (c instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> kids = (List<Map<String, Object>>) c;
                sortArchive(kids);
            }
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ 写

    /** 编辑岗位（名称 / 上级 / 兼并 / 权限 · 目标 · 职责 · 考核 / 关联制度） */
    @Transactional
    @SuppressWarnings("unchecked")
    public void updatePost(long id, Map<String, Object> b) {
        long t = tid();
        Map<String, Object> p = single("SELECT * FROM `org_post` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, t);
        if (p == null) throw new BizException("岗位不存在");
        String funcKey = s(p.get("func_key"));
        String name = s(b.getOrDefault("name", p.get("post_name"))).trim();
        if (name.isEmpty()) throw new BizException("岗位名称不能为空");

        // 未提交 parentId 的局部保存（如只改关联制度）必须保留数据库原层级
        String parentId = b.containsKey("parentId") ? s(b.get("parentId")) : s(p.get("parent_id"));
        if ("0".equals(parentId) || "null".equals(parentId)) parentId = "";
        if (parentId.equals(s(p.get("id")))) throw new BizException("上级岗位不能选择自身");
        // 防环：上级不能是自己的下级
        Set<String> desc = descendants(s(p.get("id")), t);
        if (!parentId.isEmpty() && !"0".equals(parentId) && desc.contains(parentId)) {
            throw new BizException("上级岗位不能选择自己的下级");
        }
        long parent = (parentId.isEmpty() || "0".equals(parentId)) ? 0L : Long.parseLong(parentId);

        // 兼并：总经理 / 安全员不可参与（既不可兼并他人，也不可被兼并）
        List<String> newMerged = b.get("mergedKeys") instanceof List<?>
                ? new ArrayList<>((List<String>) b.get("mergedKeys")) : parseMerged(p.get("merged_func_ids"));
        List<String> oldMerged = parseMerged(p.get("merged_func_ids"));
        boolean canMerge = !"gm".equals(funcKey) && !"safe".equals(funcKey);
        if (canMerge) {
            for (String k : newMerged) {
                if (oldMerged.contains(k) || "gm".equals(k) || "safe".equals(k)) continue;
                Map<String, Object> target = single(
                        "SELECT * FROM `org_post` WHERE `tenant_id`=? AND `func_key`=? AND `deleted`=0", t, k);
                if (target == null) continue;
                jdbc.update("UPDATE `org_post` SET `enabled`=0,`parent_id`=? WHERE `id`=?", id,
                        ((Number) target.get("id")).longValue());
            }
            for (String k : oldMerged) {
                if (newMerged.contains(k)) continue;
                Map<String, Object> target = single(
                        "SELECT * FROM `org_post` WHERE `tenant_id`=? AND `func_key`=? AND `deleted`=0", t, k);
                if (target == null) continue;
                jdbc.update("UPDATE `org_post` SET `enabled`=1,`parent_id`=? WHERE `id`=?",
                        defaultParentId(t, k), ((Number) target.get("id")).longValue());
            }
        } else {
            newMerged = oldMerged;
        }

        String docs;
        try {
            docs = mapper.writeValueAsString(b.get("docs") instanceof List<?>
                    ? b.get("docs") : parseDocs(p.get("docs")));
        } catch (Exception e) {
            docs = "[]";
        }
        jdbc.update("UPDATE `org_post` SET `post_name`=?,`parent_id`=?,`merged_func_ids`=?," +
                        "`permissions`=?,`goals`=?,`duties`=?,`assess_std`=?,`docs`=? WHERE `id`=?",
                name, parent, String.join(",", newMerged),
                s(b.getOrDefault("permissions", p.get("permissions"))),
                s(b.getOrDefault("goals", p.get("goals"))),
                s(b.getOrDefault("duties", p.get("duties"))),
                s(b.getOrDefault("assess", p.get("assess_std"))),
                docs, id);
        revisionService.mark(t, "org", "调整岗位「" + name + "」（层级 / 兼并 / 目标职责考核 / 关联制度），已联动责任制", null);
    }

    private long defaultParentId(long t, String funcKey) {
        String pf = DEFAULT_PARENT.get(funcKey);
        if (pf == null) return 0L;
        Map<String, Object> m = single(
                "SELECT `id` FROM `org_post` WHERE `tenant_id`=? AND `func_key`=? AND `deleted`=0 LIMIT 1", t, pf);
        return m == null ? 0L : ((Number) m.get("id")).longValue();
    }

    private Set<String> descendants(String id, long t) {
        Set<String> out = new HashSet<>();
        List<Map<String, Object>> all = jdbc.queryForList(
                "SELECT `id`,`parent_id` FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0", t);
        collectDescendants(id, all, out);
        return out;
    }

    private void collectDescendants(String id, List<Map<String, Object>> all, Set<String> out) {
        for (Map<String, Object> p : all) {
            String pid = s(p.get("parent_id"));
            String cid = s(p.get("id"));
            if (pid.equals(id) && !"0".equals(pid) && out.add(cid)) collectDescendants(cid, all, out);
        }
    }

    /** 更换岗位人员：只从系统已录入人员选取，落 org_member（与一人一档同源） */
    @Transactional
    public void assignPerson(long postId, String personId) {
        long t = tid();
        Map<String, Object> p = single("SELECT * FROM `org_post` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", postId, t);
        if (p == null) throw new BizException("岗位不存在");
        if (personId != null && !personId.isEmpty()) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `person` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                    Integer.class, Long.parseLong(personId), t);
            if (exists == null || exists == 0) throw new BizException("人员不在系统已录入人员名册中");
        }
        jdbc.update("UPDATE `org_member` SET `deleted`=1 WHERE `tenant_id`=? AND `post_id`=? AND `deleted`=0",
                t, postId);
        if (personId != null && !personId.isEmpty()) {
            jdbc.update("INSERT INTO `org_member` (`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,0,0)",
                    IdWorker.getId(), t, Long.parseLong(personId), postId);
        }
        revisionService.mark(t, "org", "调整岗位「" + s(p.get("post_name")) + "」人员，已联动一人一档 / 责任书待签", null);
    }

    /** 重置演示数据：按 V16 初始态恢复启用、层级、人员与制度关联 */
    @Transactional
    public void resetDemo() {
        long t = tid();
        if (t != 5001L) throw new BizException("仅演示企业可重置架构演示数据");
        String[] defaults = {"gm", "safe", "disp", "mon", "captain", "driver", "clerk"};
        Set<String> on = new HashSet<>(Arrays.asList(defaults));
        List<Map<String, Object>> posts = jdbc.queryForList(
                "SELECT `id`,`func_key` FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0", t);
        Map<String, Long> idByFunc = new HashMap<>();
        for (Map<String, Object> p : posts) idByFunc.put(s(p.get("func_key")), ((Number) p.get("id")).longValue());
        for (Map<String, Object> p : posts) {
            String k = s(p.get("func_key"));
            Long parent = null;
            String pf = DEFAULT_PARENT.get(k);
            if (pf != null) parent = idByFunc.get(pf);
            jdbc.update("UPDATE `org_post` SET `enabled`=?,`parent_id`=?,`post_name`=?,`docs`=?," +
                            "`merged_func_ids`=NULL WHERE `id`=?",
                    on.contains(k) ? 1 : 0, parent == null ? 0L : parent,
                    DEMO_NAMES.getOrDefault(k, k), DEMO_DOCS.getOrDefault(k, "[]"), p.get("id"));
        }
        jdbc.update("UPDATE `org_member` SET `deleted`=1 WHERE `tenant_id`=? AND `deleted`=0", t);
        String[][] assign = {
                {"gm", "8808000000000004"}, {"safe", "8808000000000003"},
                {"mon", "8808000000000002"}, {"driver", "8808000000000001"}};
        for (String[] a : assign) {
            Long pid = idByFunc.get(a[0]);
            if (pid == null) continue;
            jdbc.update("INSERT INTO `org_member` (`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,0,0)",
                    IdWorker.getId(), t, Long.parseLong(a[1]), pid);
        }
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> list = jdbc.queryForList(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }
}
