package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.SealService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * B15 应急管理 · 完整流程闭环（详细设计 15，定稿 v1 2026-11-10）。
 *
 * <p>多子域：应急预案（模板组合渲染，只读预览）/ 应急演练（系统自动生成计划+方案+评估，企业只补三要素）/
 * 应急资源（队伍+器材，到期预警）/ 危货专属（备案回执上传、值班制度+值班表打印表单）。
 *
 * <p>原型复刻度（用户拍板）：预案正文=只读预览，变量数据读取企业账号（tenant_info），企业不手输。
 * 演练范式：系统自动生成计划，企业只补"照片+签到+评估"三要素即完成。
 * 小程序：本期做小程序演练签到，且与会议签到统一为同一入口（见 MiniService 统一签到）。
 * 归档粒度（用户拍板）：一键生成「盒七（普货）/盒八（危货）·应急管理」整盒 PDF（带签名+盖章）大归档。
 *
 * <p>档案盒：GENERAL_BOX7=4036（普货·七、应急管理）/ DANGER_BOX8=4085（危货·八、应急管理）。
 * 子项已在 V17 播种：403601 应急预案 / 403602 应急演练计划 / 403603 应急值班表。
 */
@RestController
@RequestMapping("/api/flow/emergency")
public class EmergencyFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public EmergencyFlowController(JdbcTemplate jdbc, PdfService pdfService,
                                   StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    // 预案状态机
    private static final Map<String, String> PLAN_STATUS = new LinkedHashMap<>();
    static { PLAN_STATUS.put("draft", "草稿"); PLAN_STATUS.put("published", "已发布"); PLAN_STATUS.put("archived", "已归档"); }
    // 演练计划状态机
    private static final Map<String, String> DP_STATUS = new LinkedHashMap<>();
    static { DP_STATUS.put("draft", "草稿"); DP_STATUS.put("published", "已发布"); DP_STATUS.put("finished", "已完成"); }
    // 演练执行状态机
    private static final Map<String, String> DRILL_STATUS = new LinkedHashMap<>();
    static { DRILL_STATUS.put("draft", "未开始"); DRILL_STATUS.put("executing", "进行中"); DRILL_STATUS.put("completed", "已完成"); }

    private static final List<String[]> DRILL_TYPES = List.of(
            new String[]{"tabletop", "桌面推演"}, new String[]{"field", "实战演练"}, new String[]{"comprehensive", "综合演练"});

    // ==================================================================
    // 0. 通用工具
    // ==================================================================
    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private long l(Object o) { if (o == null) return 0L; try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; } }
    private int i(Object o) { if (o == null) return 0; try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; } }
    private String esc(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private List<Map<String, Object>> q(String sql, Object... a) { return jdbc.queryForList(sql, a); }
    private Map<String, Object> one(String sql, Object... a) {
        List<Map<String, Object>> r = q(sql, a);
        return r.isEmpty() ? Collections.emptyMap() : r.get(0);
    }
    private long count(String sql, Object... a) { Long n = jdbc.queryForObject(sql, Long.class, a); return n == null ? 0L : n; }
    private void exec(String sql, Object... a) { jdbc.update(sql, a); }
    private String company() { try { return sealService.companyName(); } catch (Exception e) { return "本企业"; } }
    private String date10(Object o) { return s(o).length() >= 10 ? s(o).substring(0, 10) : ""; }
    private String date16(Object o) { return s(o).length() >= 16 ? s(o).substring(0, 16) : s(o).replace('T', ' ').substring(0, 16); }
    /** 安全截断日期：接受 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss（兼容 T 分隔），空返回 null，避免 substring(0,19) 越界 */
    private String safeDate(Object o) {
        String x = s(o).replace('T', ' ').trim();
        if (x.isBlank()) return null;
        return x.length() > 19 ? x.substring(0, 19) : x;
    }
    private String operator() { TenantContextHolder.Context c = TenantContextHolder.get(); return c == null ? "" : s(c.getUsername()); }
    private boolean isDanger() {
        return count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
    }
    private Map<String, Object> fail(String msg) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m; }
    private String statusName(Map<String, String> map, String code) { return map.getOrDefault(code, code == null ? "" : code); }

    private void log(String bizType, long bizId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `emergency_flow_log` (`id`,`tenant_id`,`biz_type`,`biz_id`,`action`," +
                            "`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), bizType, bizId, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
    }

    /** 企业变量（预案正文只读渲染的数据来源） */
    private Map<String, String> entVars() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("companyName", company());
        Map<String, Object> t = one("SELECT `legal_person`,`safety_charge`,`charge_phone`,`address`,`main_route` " +
                "FROM `tenant_info` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        v.put("legalPerson", s(t.get("legal_person")));
        v.put("safetyCharge", s(t.get("safety_charge")));
        v.put("chargePhone", s(t.get("charge_phone")));
        v.put("address", s(t.get("address")));
        v.put("mainRoute", s(t.get("main_route")));
        v.put("year", String.valueOf(LocalDate.now().getYear()));
        return v;
    }
    private String fillVars(String html, Map<String, String> vars) {
        if (html == null) return "";
        String out = html;
        for (Map.Entry<String, String> e : vars.entrySet())
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        return out;
    }

    // ==================================================================
    // 1. 概览 / 场景范本
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("isDanger", isDanger());
        out.put("planStatus", PLAN_STATUS);
        out.put("dpStatus", DP_STATUS);
        out.put("drillStatus", DRILL_STATUS);
        out.put("drillTypes", DRILL_TYPES.stream().map(t -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("code", t[0]); m.put("name", t[1]); return m; }).toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        long plans = count("SELECT COUNT(*) FROM `emergency_plan` WHERE `tenant_id`=? AND `deleted`=0", tid());
        long published = count("SELECT COUNT(*) FROM `emergency_plan` WHERE `tenant_id`=? AND `status`='published' AND `deleted`=0", tid());
        long archived = count("SELECT COUNT(*) FROM `emergency_plan` WHERE `tenant_id`=? AND `status`='archived' AND `deleted`=0", tid());
        long drills = count("SELECT COUNT(*) FROM `emergency_drill` WHERE `tenant_id`=? AND `deleted`=0", tid());
        long completed = count("SELECT COUNT(*) FROM `emergency_drill` WHERE `tenant_id`=? AND `status`='completed' AND `deleted`=0", tid());
        long teams = count("SELECT COUNT(*) FROM `emergency_team` WHERE `tenant_id`=? AND `deleted`=0", tid());
        long equip = count("SELECT COUNT(*) FROM `emergency_equipment` WHERE `tenant_id`=? AND `deleted`=0", tid());
        stats.put("plans", plans); stats.put("planPublished", published); stats.put("planArchived", archived);
        stats.put("drills", drills); stats.put("drillCompleted", completed);
        stats.put("teams", teams); stats.put("equipment", equip);
        stats.put("completionRate", drills == 0 ? 100 : Math.round(completed * 1000.0 / drills) / 10.0);
        out.put("stats", stats);

        out.put("planTpls", planTpls());
        out.put("plans", plans(keyword));
        out.put("drillPlans", drillPlans());
        out.put("drillItems", drillItems());
        out.put("drills", drills(keyword));
        out.put("teams", teams());
        out.put("equipment", equipment());
        out.put("expiringEquip", expiringEquip());
        out.put("dutyList", dutyList());
        out.put("archives", archives());
        out.put("refs", referencesData());
        return Result.ok(out);
    }

    private List<Map<String, Object>> planTpls() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `emergency_plan_tpl` WHERE `deleted`=0 ORDER BY `sort_order`"))
            out.add(new LinkedHashMap<>(r));
        return out;
    }

    @GetMapping("/plan-tpls")
    public Result<List<Map<String, Object>>> planTplsApi() { return Result.ok(planTpls()); }

    // ==================================================================
    // 2. 应急预案：勾选场景组合生成（只读预览）
    // ==================================================================
    @PostMapping("/plan/generate")
    public Result<Map<String, Object>> generatePlan(@RequestBody Map<String, Object> b) {
        List<?> codes = b.get("sceneCodes") instanceof List<?> li ? li : List.of();
        if (codes.isEmpty()) return Result.ok(fail("请至少勾选一个场景预案"));
        String planName = s(b.get("planName"));
        if (planName.isBlank()) planName = LocalDate.now().getYear() + "年度安全生产应急预案";
        Map<String, String> vars = entVars();

        // 综合预案优先，其余按排序
        List<Map<String, Object>> tpls = new ArrayList<>();
        for (Object c : codes) {
            Map<String, Object> t = one("SELECT * FROM `emergency_plan_tpl` WHERE `tpl_code`=? AND `deleted`=0", s(c));
            if (!t.isEmpty()) tpls.add(t);
        }
        tpls.sort((x, y) -> ("comprehensive".equals(s(x.get("tpl_type"))) ? 0 : 1) - ("comprehensive".equals(s(y.get("tpl_type"))) ? 0 : 1));
        StringBuilder html = new StringBuilder();
        for (Map<String, Object> t : tpls) html.append(fillVars(s(t.get("content_html")), vars)).append("\n");

        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `emergency_plan` (`id`,`tenant_id`,`cargo_type`,`plan_name`,`version`,`scene_codes`," +
                        "`content_html`,`variables`,`status`,`created_by`,`created_at`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                pid, tid(), isDanger() ? "danger" : "general", planName, "V1",
                String.join(",", codes.stream().map(String::valueOf).toList()),
                html.toString(), "{}", "draft", TenantContextHolder.userId(), LocalDateTime.now());
        log("plan", pid, "generate", "", "draft", "生成预案：" + planName + "，勾选 " + codes.size() + " 个场景");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("planId", String.valueOf(pid)); out.put("planName", planName);
        out.put("sceneCount", codes.size());
        return Result.ok(out);
    }

    @GetMapping("/plan/{id}/preview")
    public Result<Map<String, Object>> previewPlan(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `emergency_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("预案不存在"));
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("id", String.valueOf(m.get("id")));
        out.put("statusName", statusName(PLAN_STATUS, s(m.get("status"))));
        out.put("contentHtml", s(m.get("content_html")));
        out.put("readOnly", true);
        out.put("signInfo", parseJsonArr(s(m.get("sign_info"))));
        return Result.ok(out);
    }

    /** 一键签名（主要负责人+安全管理员，数据取自企业账号） */
    @PostMapping("/plan/{id}/sign")
    public Result<Map<String, Object>> signPlan(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `emergency_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("预案不存在"));
        Map<String, String> vars = entVars();
        List<Map<String, Object>> signs = new ArrayList<>();
        signs.add(signEntry("主要负责人", vars.get("legalPerson")));
        signs.add(signEntry("安全管理员", vars.get("safetyCharge")));
        exec("UPDATE `emergency_plan` SET `sign_info`=? WHERE `id`=?", toJson(signs), id);
        log("plan", id, "sign", s(m.get("status")), s(m.get("status")), "一键签名：主要负责人/安全管理员");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("signInfo", signs);
        return Result.ok(out);
    }
    private Map<String, Object> signEntry(String role, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role); m.put("name", name); m.put("time", LocalDateTime.now().toString());
        return m;
    }

    @PostMapping("/plan/{id}/publish")
    public Result<Map<String, Object>> publishPlan(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `emergency_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("预案不存在"));
        if (!"draft".equals(s(m.get("status")))) return Result.ok(fail("仅草稿可发布，当前 " + statusName(PLAN_STATUS, s(m.get("status")))));
        exec("UPDATE `emergency_plan` SET `status`='published',`effective_date`=?,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), LocalDateTime.now(), id);
        log("plan", id, "publish", "draft", "published", "发布预案");
        return Result.ok(Map.of("ok", true, "status", "published"));
    }

    /** 超管纠错（企业 403） */
    @PutMapping("/plan/{id}/correct")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> correctPlan(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `emergency_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("预案不存在"));
        List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
        if (b.containsKey("planName")) { sets.add("`plan_name`=?"); args.add(s(b.get("planName"))); }
        if (b.containsKey("status")) {
            String st = s(b.get("status"));
            if (!PLAN_STATUS.containsKey(st)) return Result.ok(fail("状态值不合法"));
            sets.add("`status`=?"); args.add(st);
        }
        if (sets.isEmpty()) return Result.ok(Map.of("ok", true));
        sets.add("`updated_at`=?"); args.add(LocalDateTime.now());
        StringBuilder sql = new StringBuilder("UPDATE `emergency_plan` SET ").append(String.join(",", sets)).append(" WHERE `id`=?");
        args.add(id); exec(sql.toString(), args.toArray());
        log("plan", id, "correct", s(m.get("status")), s(b.get("status")), "超管纠错");
        return Result.ok(Map.of("ok", true, "status", s(b.get("status"))));
    }

    private List<Map<String, Object>> plans(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM `emergency_plan` WHERE `tenant_id`=? AND `deleted`=0");
        List<Object> args = new ArrayList<>(); args.add(tid());
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND `plan_name` LIKE ?"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY `id` DESC LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(PLAN_STATUS, s(r.get("status"))));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/plan/list")
    public Result<List<Map<String, Object>>> planList(@RequestParam(required = false) String keyword) {
        return Result.ok(plans(keyword));
    }

    // ==================================================================
    // 3. 应急演练：系统自动生成计划 + 方案 + 评估模板
    // ==================================================================
    @PostMapping("/drill-plan/generate")
    public Result<Map<String, Object>> generateDrillPlan(@RequestBody Map<String, Object> b) {
        int year = b.get("year") != null ? i(b.get("year")) : LocalDate.now().getYear();
        if (year == 0) year = LocalDate.now().getYear();
        // 已有同年计划则复用
        Map<String, Object> exist = one("SELECT `id` FROM `emergency_drill_plan` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0 LIMIT 1", tid(), year);
        long planId;
        if (!exist.isEmpty()) planId = l(exist.get("id"));
        else {
            planId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `emergency_drill_plan` (`id`,`tenant_id`,`plan_name`,`year`,`cargo_type`,`status`,`created_by`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,0)",
                    planId, tid(), year + "年度应急演练计划", year, isDanger() ? "danger" : "general", "draft", TenantContextHolder.userId(), LocalDateTime.now());
            log("drill_plan", planId, "generate", "", "draft", "系统自动生成" + year + "年度演练计划");
        }
        // 为每个场景范本生成季度演练项目（默认每季度 1 次，覆盖主要场景）
        List<Map<String, Object>> tpls = q("SELECT * FROM `emergency_plan_tpl` WHERE `tpl_type` IN ('special','site') AND `deleted`=0 ORDER BY `sort_order` LIMIT 4");
        String[] quarters = {"-03-15", "-06-15", "-09-15", "-12-15"};
        int n = 0;
        for (int q = 0; q < quarters.length; q++) {
            Map<String, Object> tpl = tpls.get(q % Math.max(1, tpls.size()));
            String scene = s(tpl.get("tpl_code"));
            String drillName = year + "年" + (q + 1) + "季度" + s(tpl.get("tpl_name"));
            String scheme = "<h3>演练目的</h3><p>检验" + s(tpl.get("tpl_name")) + "应急处置流程的可行性与人员协同能力。</p>" +
                    "<h3>演练场景</h3><p>" + s(tpl.get("tpl_name")) + "（" + scene + "）</p>" +
                    "<h3>演练步骤</h3><p>1. 发布演练通知；2. 按预案分工就位；3. 模拟处置；4. 总结评估。</p>" +
                    "<h3>职责分工</h3><p>总指挥：主要负责人；现场：安全管理员；保障：应急队伍。</p>";
            String eval = "演练评估表（企业手填）：\n演练时间：\n参演人数：\n演练过程评价：\n存在问题：\n改进措施：\n评估人：";
            long itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `emergency_drill_item` (`id`,`tenant_id`,`plan_id`,`drill_name`,`scene_code`,`drill_type`," +
                            "`plan_time`,`scheme`,`eval_template`,`created_at`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    itemId, tid(), planId, drillName, scene, "field",
                    year + quarters[q] + " 09:00:00", scheme, eval, LocalDateTime.now());
            n++;
        }
        exec("UPDATE `emergency_drill_plan` SET `status`='published' WHERE `id`=?", planId);
        log("drill_plan", planId, "auto-items", "draft", "published", "自动生成 " + n + " 个演练项目（含方案+评估表模板）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("planId", String.valueOf(planId));
        out.put("itemCount", n); out.put("year", year);
        return Result.ok(out);
    }

    private List<Map<String, Object>> drillPlans() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `emergency_drill_plan` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `year` DESC", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(DP_STATUS, s(r.get("status"))));
            long items = count("SELECT COUNT(*) FROM `emergency_drill_item` WHERE `plan_id`=? AND `tenant_id`=? AND `deleted`=0", l(r.get("id")), tid());
            m.put("itemCount", items);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/drill-plan/list")
    public Result<List<Map<String, Object>>> drillPlanList() { return Result.ok(drillPlans()); }

    /** 演练项目（计划项）列表：含关联演练执行状态与三要素计数，供前端「开展/照片/评估/完成」逐条操作 */
    private List<Map<String, Object>> drillItems() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> it : q("SELECT i.*, p.`plan_name` FROM `emergency_drill_item` i " +
                "LEFT JOIN `emergency_drill_plan` p ON p.`id`=i.`plan_id` " +
                "WHERE i.`tenant_id`=? AND i.`deleted`=0 ORDER BY i.`id` DESC", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(it);
            m.put("id", String.valueOf(it.get("id")));
            long itemId = l(it.get("id"));
            Map<String, Object> dr = one("SELECT `id`,`status`,`eval_result` FROM `emergency_drill` " +
                    "WHERE `plan_item_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", itemId, tid());
            if (dr.isEmpty()) {
                m.put("drillId", "");
                m.put("status", "none");
                m.put("statusName", "未开展");
                m.put("photos", 0); m.put("signs", 0); m.put("canComplete", false);
            } else {
                long drillId = l(dr.get("id"));
                m.put("drillId", String.valueOf(drillId));
                m.put("status", s(dr.get("status")));
                m.put("statusName", statusName(DRILL_STATUS, s(dr.get("status"))));
                long photos = count("SELECT COUNT(*) FROM `emergency_drill_photo` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0", drillId, tid());
                long signs = count("SELECT COUNT(*) FROM `emergency_drill_sign` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0 AND `sign_status`='signed'", drillId, tid());
                boolean hasEval = !s(dr.get("eval_result")).isBlank();
                m.put("photos", photos); m.put("signs", signs);
                m.put("canComplete", photos > 0 && signs > 0 && hasEval);
            }
            out.add(m);
        }
        return out;
    }
    @GetMapping("/drill-item/list")
    public Result<List<Map<String, Object>>> drillItemList() { return Result.ok(drillItems()); }

    @PostMapping("/drill/{id}/start")
    public Result<Map<String, Object>> startDrill(@PathVariable long id) {
        Map<String, Object> item = one("SELECT * FROM `emergency_drill_item` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (item.isEmpty()) return Result.ok(fail("演练项目不存在"));
        long did = count("SELECT COUNT(*) FROM `emergency_drill` WHERE `plan_item_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        long drillId;
        if (did > 0) {
            Map<String, Object> d = one("SELECT `id`,`status` FROM `emergency_drill` WHERE `plan_item_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", id, tid());
            drillId = l(d.get("id"));
            if ("completed".equals(s(d.get("status")))) return Result.ok(fail("该演练已完成"));
        } else {
            drillId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `emergency_drill` (`id`,`tenant_id`,`plan_item_id`,`drill_name`,`drill_type`,`status`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,0)",
                    drillId, tid(), id, s(item.get("drill_name")), s(item.get("drill_type")), "executing", LocalDateTime.now());
            log("drill", drillId, "start", "", "executing", "开展演练：" + s(item.get("drill_name")));
        }
        return Result.ok(Map.of("ok", true, "drillId", String.valueOf(drillId), "status", "executing"));
    }

    @PostMapping("/drill/{id}/photo")
    public Result<Map<String, Object>> drillPhoto(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `emergency_drill` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("演练不存在"));
        String url = s(b.get("photoUrl"));
        if (url.isBlank()) return Result.ok(fail("请上传照片"));
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `emergency_drill_photo` (`id`,`tenant_id`,`drill_id`,`photo_url`,`caption`,`sort_order`,`created_at`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,0)",
                pid, tid(), id, url, s(b.get("caption")), i(b.get("sortOrder")), LocalDateTime.now());
        log("drill", id, "photo", "executing", "executing", "上传演练照片");
        return Result.ok(Map.of("ok", true, "photoId", String.valueOf(pid)));
    }

    @PostMapping("/drill/{id}/eval")
    public Result<Map<String, Object>> drillEval(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `emergency_drill` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("演练不存在"));
        exec("UPDATE `emergency_drill` SET `eval_result`=?,`updated_at`=? WHERE `id`=?", s(b.get("evalResult")), LocalDateTime.now(), id);
        log("drill", id, "eval", "executing", "executing", "填写演练评估表");
        return Result.ok(Map.of("ok", true));
    }

    /** 完成判定：照片≥1 + 签到≥1 + 评估非空 */
    @PostMapping("/drill/{id}/complete")
    public Result<Map<String, Object>> completeDrill(@PathVariable long id) {
        Map<String, Object> d = one("SELECT * FROM `emergency_drill` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("演练不存在"));
        if ("completed".equals(s(d.get("status")))) return Result.ok(Map.of("ok", true, "status", "completed", "msg", "已完成"));
        long photos = count("SELECT COUNT(*) FROM `emergency_drill_photo` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        long signs = count("SELECT COUNT(*) FROM `emergency_drill_sign` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0 AND `sign_status`='signed'", id, tid());
        boolean hasEval = !s(d.get("eval_result")).isBlank();
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("photo", photos > 0); comp.put("sign", signs > 0); comp.put("eval", hasEval);
        if (photos == 0 || signs == 0 || !hasEval)
            return Result.ok(Map.of("ok", false, "msg", "三要素不齐：照片/签到/评估需全部具备", "completeness", comp));
        exec("UPDATE `emergency_drill` SET `status`='completed',`drill_date`=?,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), LocalDateTime.now(), id);
        log("drill", id, "complete", "executing", "completed", "演练三要素齐备，标记完成");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", "completed"); out.put("completeness", comp);
        return Result.ok(out);
    }

    private List<Map<String, Object>> drills(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT d.*, (SELECT COUNT(*) FROM `emergency_drill_photo` p WHERE p.`drill_id`=d.`id` AND p.`tenant_id`=? AND p.`deleted`=0) AS photos," +
                "(SELECT COUNT(*) FROM `emergency_drill_sign` s WHERE s.`drill_id`=d.`id` AND s.`tenant_id`=? AND s.`deleted`=0 AND s.`sign_status`='signed') AS signs " +
                "FROM `emergency_drill` d WHERE d.`tenant_id`=? AND d.`deleted`=0");
        List<Object> args = new ArrayList<>(); args.add(tid()); args.add(tid()); args.add(tid());
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND d.`drill_name` LIKE ?"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY d.`id` DESC LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(DRILL_STATUS, s(r.get("status"))));
            boolean comp = l(r.get("photos")) > 0 && l(r.get("signs")) > 0 && !s(r.get("eval_result")).isBlank();
            m.put("canComplete", comp);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/drill/list")
    public Result<List<Map<String, Object>>> drillList(@RequestParam(required = false) String keyword) {
        return Result.ok(drills(keyword));
    }

    @GetMapping("/drill/{id}")
    public Result<Map<String, Object>> drillDetail(@PathVariable long id) {
        Map<String, Object> d = one("SELECT * FROM `emergency_drill` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("演练不存在"));
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("id", String.valueOf(d.get("id")));
        out.put("statusName", statusName(DRILL_STATUS, s(d.get("status"))));
        List<Map<String, Object>> signs = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT s.*, p.`name` FROM `emergency_drill_sign` s LEFT JOIN `person` p ON p.`id`=s.`user_id` " +
                "WHERE s.`drill_id`=? AND s.`tenant_id`=? AND s.`deleted`=0 ORDER BY s.`id`", id, tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", s(r.get("name")));
            signs.add(m);
        }
        out.put("signs", signs);
        List<Map<String, Object>> photos = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `emergency_drill_photo` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `sort_order`", id, tid()))
            photos.add(new LinkedHashMap<>(r));
        out.put("photos", photos);
        return Result.ok(out);
    }

    // ==================================================================
    // 4. 应急资源：队伍 / 器材（到期预警）
    // ==================================================================
    @PostMapping("/team")
    public Result<Map<String, Object>> saveTeam(@RequestBody Map<String, Object> b) {
        String name = s(b.get("teamName"));
        if (name.isBlank()) return Result.ok(fail("队伍名称不能为空"));
        long id = b.get("id") != null && l(b.get("id")) > 0 ? l(b.get("id")) : IdUtil.getSnowflakeNextId();
        if (l(b.get("id")) > 0)
            exec("UPDATE `emergency_team` SET `team_name`=?,`team_role`=?,`leader_id`=?,`updated_at`=? WHERE `id`=?", name, s(b.get("teamRole")), l(b.get("leaderId")), LocalDateTime.now(), id);
        else
            exec("INSERT INTO `emergency_team` (`id`,`tenant_id`,`team_name`,`team_role`,`leader_id`,`created_at`,`deleted`) VALUES (?,?,?,?,?,?,0)",
                    id, tid(), name, s(b.get("teamRole")), l(b.get("leaderId")), LocalDateTime.now());
        log("team", id, "save", "", "", "维护应急队伍：" + name);
        return Result.ok(Map.of("ok", true, "teamId", String.valueOf(id)));
    }

    @PostMapping("/team/{id}/member")
    public Result<Map<String, Object>> addMember(@PathVariable long id, @RequestBody Map<String, Object> b) {
        long uid = l(b.get("userId"));
        if (uid == 0L) return Result.ok(fail("请选择成员"));
        exec("INSERT INTO `emergency_team_member` (`id`,`tenant_id`,`team_id`,`user_id`,`duty`,`phone`,`created_at`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, uid, s(b.get("duty")), s(b.get("phone")), LocalDateTime.now());
        return Result.ok(Map.of("ok", true));
    }

    private List<Map<String, Object>> teams() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT t.*,(SELECT COUNT(*) FROM `emergency_team_member` m WHERE m.`team_id`=t.`id` AND m.`tenant_id`=? AND m.`deleted`=0) AS members " +
                "FROM `emergency_team` t WHERE t.`tenant_id`=? AND t.`deleted`=0 ORDER BY t.`id`", tid(), tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            out.add(m);
        }
        return out;
    }
    @GetMapping("/team/list") public Result<List<Map<String, Object>>> teamList() { return Result.ok(teams()); }

    @PostMapping("/equipment")
    public Result<Map<String, Object>> saveEquip(@RequestBody Map<String, Object> b) {
        String name = s(b.get("equipName"));
        if (name.isBlank()) return Result.ok(fail("器材名称不能为空"));
        long id = b.get("id") != null && l(b.get("id")) > 0 ? l(b.get("id")) : IdUtil.getSnowflakeNextId();
        if (l(b.get("id")) > 0)
            exec("UPDATE `emergency_equipment` SET `equip_name`=?,`equip_type`=?,`quantity`=?,`unit`=?,`valid_until`=?,`location`=?,`keeper_id`=?,`updated_at`=? WHERE `id`=?",
                    name, s(b.get("equipType")), i(b.get("quantity")), s(b.get("unit")), safeDate(b.get("validUntil")),
                    s(b.get("location")), l(b.get("keeperId")), LocalDateTime.now(), id);
        else
            exec("INSERT INTO `emergency_equipment` (`id`,`tenant_id`,`equip_name`,`equip_type`,`quantity`,`unit`,`valid_until`,`location`,`keeper_id`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    id, tid(), name, s(b.get("equipType")), i(b.get("quantity")), s(b.get("unit")),
                    safeDate(b.get("validUntil")),
                    s(b.get("location")), l(b.get("keeperId")), LocalDateTime.now());
        return Result.ok(Map.of("ok", true, "equipId", String.valueOf(id)));
    }

    private List<Map<String, Object>> equipment() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `emergency_equipment` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            boolean expiring = s(r.get("valid_until")).length() >= 10 && LocalDate.parse(s(r.get("valid_until")).substring(0, 10)).isBefore(LocalDate.now().plusMonths(1));
            m.put("expiring", expiring);
            out.add(m);
        }
        return out;
    }
    private List<Map<String, Object>> expiringEquip() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : equipment()) if (Boolean.TRUE.equals(r.get("expiring"))) out.add(r);
        return out;
    }
    @GetMapping("/equipment/list") public Result<List<Map<String, Object>>> equipList() { return Result.ok(equipment()); }

    // ==================================================================
    // 5. 危货专属：备案回执上传 / 值班制度 + 值班表（打印表单手写登记）
    // ==================================================================
    @PostMapping("/duty-rule")
    public Result<Map<String, Object>> saveDutyRule(@RequestBody Map<String, Object> b) {
        if (!isDanger()) return Result.ok(fail("仅危货企业需维护应急值班制度"));
        String name = s(b.get("ruleName"));
        if (name.isBlank()) name = "应急值班制度";
        long id = b.get("id") != null && l(b.get("id")) > 0 ? l(b.get("id")) : IdUtil.getSnowflakeNextId();
        if (l(b.get("id")) > 0)
            exec("UPDATE `emergency_duty_rule` SET `rule_name`=?,`content_html`=?,`effective_date`=?,`updated_at`=? WHERE `id`=?",
                    name, s(b.get("contentHtml")), safeDate(b.get("effectiveDate")), LocalDateTime.now(), id);
        else
            exec("INSERT INTO `emergency_duty_rule` (`id`,`tenant_id`,`rule_name`,`content_html`,`effective_date`,`created_at`,`deleted`) VALUES (?,?,?,?,?,?,0)",
                    id, tid(), name, s(b.get("contentHtml")), safeDate(b.get("effectiveDate")), LocalDateTime.now());
        return Result.ok(Map.of("ok", true, "ruleId", String.valueOf(id)));
    }

    @PostMapping("/duty")
    public Result<Map<String, Object>> saveDuty(@RequestBody Map<String, Object> b) {
        if (!isDanger()) return Result.ok(fail("仅危货企业需维护应急值班表"));
        if (s(b.get("dutyDate")).isBlank()) return Result.ok(fail("请填写值班日期"));
        long id = b.get("id") != null && l(b.get("id")) > 0 ? l(b.get("id")) : IdUtil.getSnowflakeNextId();
        if (l(b.get("id")) > 0)
            exec("UPDATE `emergency_duty` SET `duty_date`=?,`user_id`=?,`phone`=?,`shift`=?,`updated_at`=? WHERE `id`=?",
                    s(b.get("dutyDate")), l(b.get("userId")), s(b.get("phone")), s(b.get("shift")), LocalDateTime.now(), id);
        else
            exec("INSERT INTO `emergency_duty` (`id`,`tenant_id`,`duty_date`,`user_id`,`phone`,`shift`,`created_at`,`deleted`) VALUES (?,?,?,?,?,?,?,0)",
                    id, tid(), s(b.get("dutyDate")), l(b.get("userId")), s(b.get("phone")), s(b.get("shift")), LocalDateTime.now());
        return Result.ok(Map.of("ok", true, "dutyId", String.valueOf(id)));
    }

    private List<Map<String, Object>> dutyList() {
        if (!isDanger()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT d.*, p.`name` FROM `emergency_duty` d LEFT JOIN `person` p ON p.`id`=d.`user_id` " +
                "WHERE d.`tenant_id`=? AND d.`deleted`=0 ORDER BY d.`duty_date` DESC", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", s(r.get("name")));
            out.add(m);
        }
        return out;
    }
    @GetMapping("/duty/list") public Result<List<Map<String, Object>>> dutyListApi() { return Result.ok(dutyList()); }

    /** 危货空白打印表单（手写登记）：值班制度 / 值班表 */
    @GetMapping(value = "/print/{type}", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printForm(@PathVariable String type) {
        String title = "应急值班制度".equals(type) || "duty-rule".equals(type) ? "应急值班制度（空白登记表）" : "应急值班表（空白登记表）";
        String body;
        if ("duty-rule".equals(type) || "制度".equals(type)) {
            body = "<h2>应急值班制度</h2><table>" +
                    "<tr><td class='n'>制度名称</td><td colspan='3'>________________________</td></tr>" +
                    "<tr><td class='n'>适用企业</td><td>" + esc(company()) + "</td><td class='n'>生效日期</td><td>____年__月__日</td></tr>" +
                    "<tr><td class='n'>值班要求</td><td colspan='3'>1. 24 小时值班，不得脱岗；2. 遇突发事件立即报告主要负责人并启动预案；3. 做好值班记录。</td></tr>" +
                    "</table><div class='record'>（以下由企业打印后手写登记）</div>" +
                    "<p class='ph'>编制：__________ 审核：__________ 批准：__________</p>";
        } else {
            body = "<h2>应急值班表</h2><table><tr><th>日期</th><th>班次</th><th>值班人</th><th>联系电话</th><th>备注</th></tr>" +
                    "<tr><td>____/__/__</td><td></td><td></td><td></td><td></td></tr>" +
                    "<tr><td>____/__/__</td><td></td><td></td><td></td><td></td></tr>" +
                    "<tr><td>____/__/__</td><td></td><td></td><td></td><td></td></tr>" +
                    "<tr><td>____/__/__</td><td></td><td></td><td></td><td></td></tr></table>" +
                    "<p class='ph'>（本表由企业打印后手写登记，值班人签字确认）</p>";
        }
        body = "<h1>" + esc(company()) + " · " + esc(title) + "</h1>" + body +
                "<p class='foot'>系统生成空白表单 · 打印后手写登记 · " + LocalDate.now() + "</p>";
        return pdfResponse(pdfService.render(page("应急值班表单", body)), "emergency_" + type + ".pdf");
    }

    // ==================================================================
    // 6. 一键大归档：盒七（普货）/ 盒八（危货）· 应急管理 整盒 PDF
    // ==================================================================
    @PostMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestBody Map<String, Object> b) {
        String box = isDanger() ? "DANGER_BOX8" : "GENERAL_BOX7";
        Map<String, Object> cat = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long catalogId = l(cat.get("id"));
        if (catalogId == 0L) return Result.ok(fail("未找到归档目录：" + box));

        String title = (isDanger() ? "危货·" : "") + company() + "·应急管理（整盒归档）";
        byte[] pdf = pdfService.render(page("应急管理整盒归档", bigArchiveHtml()));

        String key = tid() + "/emergency/box_" + LocalDate.now() + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='emergency' AND `biz_id`=? AND `deleted`=0 LIMIT 1", tid(), catalogId);
        long itemId;
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`biz_module`,`biz_id`," +
                            "`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,2,1,'emergency',?,?,?,?,1,?,?,0)",
                    itemId, tid(), catalogId, itemId, title, LocalDate.now(), LocalDate.now().getYear(), LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
            exec("UPDATE `archive_item` SET `catalog_id`=?,`title`=?,`occur_date`=? WHERE `id`=?", catalogId, title, LocalDate.now(), itemId);
        }
        exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=?", itemId, tid());
        long pdfId = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,1,'EMERGENCY_BOX',?,?,?,?,0)",
                pdfId, tid(), itemId, (short) LocalDate.now().getYear(), title + ".pdf", key, pdf.length,
                null, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        // 整盒归档后，把本盒相关预案/演练标记 archived
        exec("UPDATE `emergency_plan` SET `status`='archived',`updated_at`=? WHERE `tenant_id`=? AND `status`='published' AND `deleted`=0", LocalDateTime.now(), tid());
        exec("UPDATE `emergency_drill` SET `status`='completed',`updated_at`=? WHERE `tenant_id`=? AND `status`='executing' AND `deleted`=0", LocalDateTime.now(), tid());
        log("archive", itemId, "archive", "", "archived", "整盒归档到" + box + "（带签名+盖章）");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true); res.put("box", box); res.put("catalogId", String.valueOf(catalogId));
        res.put("itemId", String.valueOf(itemId)); res.put("pdfId", String.valueOf(pdfId));
        res.put("size", pdf.length); res.put("title", title);
        return Result.ok(res);
    }

    private String bigArchiveHtml() {
        Map<String, String> vars = entVars();
        StringBuilder body = new StringBuilder();
        // 1. 应急预案
        body.append("<h2>一、应急预案</h2>");
        List<Map<String, Object>> plans = q("SELECT * FROM `emergency_plan` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid());
        if (plans.isEmpty()) body.append("<p class='ph'>（暂无预案）</p>");
        for (Map<String, Object> p : plans) {
            body.append("<h3>").append(esc(s(p.get("plan_name")))).append("（").append(statusName(PLAN_STATUS, s(p.get("status")))).append("）</h3>");
            body.append("<div class='record'>").append(fillVars(s(p.get("content_html")), vars).replace("\n", "")).append("</div>");
            String sign = s(p.get("sign_info"));
            if (!sign.isBlank() && !"[]".equals(sign)) {
                body.append("<p class='ph'>签名：");
                for (Object o : cn.hutool.json.JSONUtil.parseArray(sign)) {
                    cn.hutool.json.JSONObject jo = (cn.hutool.json.JSONObject) o;
                    body.append(esc(jo.getStr("role"))).append(" ").append(esc(jo.getStr("name"))).append(" / ");
                }
                body.append("</p>");
            }
        }
        // 2. 应急队伍架构
        body.append("<h2>二、应急队伍架构</h2><table><tr><th>队伍</th><th>角色</th><th>成员</th></tr>");
        for (Map<String, Object> t : teams()) {
            StringBuilder mem = new StringBuilder();
            for (Map<String, Object> m : q("SELECT p.`name`,x.`duty` FROM `emergency_team_member` x LEFT JOIN `person` p ON p.`id`=x.`user_id` " +
                    "WHERE x.`team_id`=? AND x.`tenant_id`=? AND x.`deleted`=0", l(t.get("id")), tid())) {
                if (mem.length() > 0) mem.append("、");
                mem.append(esc(s(m.get("name")))).append(s(m.get("duty")).isBlank() ? "" : "(" + esc(s(m.get("duty"))) + ")");
            }
            body.append("<tr><td>").append(esc(s(t.get("team_name")))).append("</td><td>").append(esc(s(t.get("team_role"))))
                    .append("</td><td>").append(mem.length() == 0 ? "—" : mem).append("</td></tr>");
        }
        if (teams().isEmpty()) body.append("<tr><td colspan='3' style='text-align:center;color:#999'>暂无队伍</td></tr>");
        body.append("</table>");
        // 3. 应急救援器材
        body.append("<h2>三、应急救援器材</h2><table><tr><th>器材名称</th><th>类型</th><th>数量</th><th>有效期</th><th>存放位置</th></tr>");
        for (Map<String, Object> e : equipment())
            body.append("<tr><td>").append(esc(s(e.get("equip_name")))).append("</td><td>").append(esc(s(e.get("equip_type"))))
                    .append("</td><td>").append(s(e.get("quantity"))).append(esc(s(e.get("unit")))).append("</td><td>")
                    .append(date10(e.get("valid_until"))).append("</td><td>").append(esc(s(e.get("location")))).append("</td></tr>");
        if (equipment().isEmpty()) body.append("<tr><td colspan='5' style='text-align:center;color:#999'>暂无器材</td></tr>");
        body.append("</table>");
        // 4. 应急演练计划 + 演练
        body.append("<h2>四、应急演练计划与演练</h2>");
        for (Map<String, Object> dp : drillPlans()) {
            body.append("<h3>").append(esc(s(dp.get("plan_name")))).append("（").append(s(dp.get("itemCount"))).append(" 个演练项目）</h3>");
            for (Map<String, Object> it : q("SELECT * FROM `emergency_drill_item` WHERE `plan_id`=? AND `tenant_id`=? AND `deleted`=0", l(dp.get("id")), tid())) {
                body.append("<p class='ph'><b>").append(esc(s(it.get("drill_name")))).append("</b> · 计划时间 ").append(date16(it.get("plan_time"))).append("</p>");
                body.append("<div class='record'>").append(esc(s(it.get("scheme"))).replace("\n", "<br/>")).append("</div>");
                // 对应演练执行
                Map<String, Object> dr = one("SELECT * FROM `emergency_drill` WHERE `plan_item_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", l(it.get("id")), tid());
                if (!dr.isEmpty()) {
                    body.append("<p class='ph'>演练状态：").append(statusName(DRILL_STATUS, s(dr.get("status")))).append("</p>");
                    if (!s(dr.get("eval_result")).isBlank())
                        body.append("<div class='record'>评估：").append(esc(s(dr.get("eval_result"))).replace("\n", "<br/>")).append("</div>");
                    for (Map<String, Object> ph : q("SELECT `photo_url` FROM `emergency_drill_photo` WHERE `drill_id`=? AND `tenant_id`=? AND `deleted`=0", l(dr.get("id")), tid()))
                        body.append("<p class='ph'>照片：").append(esc(s(ph.get("photo_url")))).append("</p>");
                }
            }
        }
        // 5. 应急培训（复用培训模块，标应急类型）
        body.append("<h2>五、应急培训</h2>");
        try {
            List<Map<String, Object>> tr = q("SELECT `plan_name` FROM `training_plan` WHERE `tenant_id`=? AND (`tags` LIKE '%应急%' OR `plan_name` LIKE '%应急%') AND `deleted`=0 LIMIT 10", tid());
            if (tr.isEmpty()) body.append("<p class='ph'>（应急培训复用线上学习模块，类型：应急；本期无独立记录）</p>");
            else for (Map<String, Object> r : tr) body.append("<p class='ph'>· ").append(esc(s(r.get("plan_name")))).append("</p>");
        } catch (Exception ignore) { body.append("<p class='ph'>（应急培训复用线上学习模块，类型：应急）</p>"); }
        // 6. 危货专属
        if (isDanger()) {
            body.append("<h2>六、危货专属</h2>");
            Map<String, Object> rec = one("SELECT `record_no`,`record_file` FROM `emergency_plan` WHERE `tenant_id`=? AND `cargo_type`='danger' AND `record_no` IS NOT NULL AND `deleted`=0 LIMIT 1", tid());
            body.append("<p class='ph'>应急预案备案回执：").append(rec.isEmpty() ? "（待上传）" : esc(s(rec.get("record_no"))) + " " + esc(s(rec.get("record_file")))).append("</p>");
            for (Map<String, Object> r : q("SELECT `rule_name`,`effective_date` FROM `emergency_duty_rule` WHERE `tenant_id`=? AND `deleted`=0", tid()))
                body.append("<p class='ph'>值班制度：").append(esc(s(r.get("rule_name")))).append("（生效 ").append(date10(r.get("effective_date"))).append("）</p>");
            for (Map<String, Object> r : dutyList())
                body.append("<p class='ph'>值班表：").append(date10(r.get("duty_date"))).append(" ").append(esc(s(r.get("name")))).append(" ").append(esc(s(r.get("shift")))).append("</p>");
        }
        body.append(sealService.sealHtml() == null ? "" : "<div class='seal'>" + sealService.sealHtml() + "</div>");
        body.append("<p class='foot'>本盒由系统按应急管理留痕实时生成 · 带签名与盖章 · ").append(LocalDate.now()).append("</p>");
        return body.toString();
    }

    // ==================================================================
    // 7. 联动数据源 / 小程序预留
    // ==================================================================
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> persons = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`name`,`person_type` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` LIMIT 100", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r); m.put("id", String.valueOf(r.get("id"))); persons.add(m);
        }
        out.put("persons", persons);
        out.put("planTpls", planTpls().stream().map(t -> t.get("tpl_code")).toList());
        out.put("isDanger", isDanger());
        return Result.ok(out);
    }

    private List<Map<String, Object>> archives() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`occur_date`,`status` FROM `archive_item` " +
                "WHERE `tenant_id`=? AND `biz_module`='emergency' AND `deleted`=0 ORDER BY `id` DESC LIMIT 20", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r); m.put("id", String.valueOf(r.get("id"))); out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> referencesData() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] refs = {
                {"person", "B1 人员基础信息", "队伍成员/演练签到人", "SELECT COUNT(*) FROM `emergency_team_member` WHERE `tenant_id`=?"},
                {"archive", "B7 电子档案柜", "盒七/盒八应急管理归档", "SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='emergency' AND `deleted`=0"},
                {"vehicle", "车辆基础信息", "危货判定（盒八）", "SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `biz_type`='danger' AND `deleted`=0"},
        };
        for (String[] r : refs) {
            long n; try { n = count(r[3], tid()); } catch (Exception e) { n = 0; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", r[0]); m.put("name", r[1]); m.put("usage", r[2]); m.put("count", n); out.add(m);
        }
        return out;
    }
    @GetMapping("/references") public Result<List<Map<String, Object>>> references() { return Result.ok(referencesData()); }

    /** 小程序预留：待归档预警聚合 */
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`plan_name`,`status` FROM `emergency_plan` WHERE `tenant_id`=? AND `deleted`=0 AND `status`='published' LIMIT 20", tid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bizId", String.valueOf(r.get("id"))); m.put("title", s(r.get("plan_name")));
            m.put("type", "plan"); m.put("status", s(r.get("status")));
            m.put("text", "已发布预案，待整盒归档"); list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("list", list); out.put("total", list.size());
        return Result.ok(out);
    }

    /** 小程序预留：我参与的（演练签到等） */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Long userId) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT d.`id`,d.`drill_name`,d.`status`," +
                " (SELECT COUNT(*) FROM `emergency_drill_sign` s WHERE s.`drill_id`=d.`id` AND s.`tenant_id`=? AND s.`user_id`=? AND s.`deleted`=0 AND s.`sign_status`='signed') AS signed " +
                " FROM `emergency_drill` d WHERE d.`tenant_id`=? AND d.`status` IN ('executing','completed') ");
        List<Object> args = new ArrayList<>(); args.add(tid()); args.add(userId == null ? 0L : userId); args.add(tid());
        if (userId != null) sql.append(" AND EXISTS(SELECT 1 FROM `emergency_drill_sign` s WHERE s.`drill_id`=d.`id` AND s.`user_id`=? AND s.`deleted`=0)");
        if (userId != null) args.add(userId);
        sql.append(" ORDER BY d.`id` DESC LIMIT 30");
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("bizId", String.valueOf(r.get("id")));
            m.put("bizModule", "drill"); m.put("title", s(r.get("drill_name")));
            m.put("statusName", statusName(DRILL_STATUS, s(r.get("status"))));
            m.put("signed", l(r.get("signed")) > 0);
            tasks.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", LocalDate.now().toString()); out.put("tasks", tasks); out.put("total", tasks.size());
        out.put("note", "小程序端统一签到入口聚合展示，具体签到页后续统一建设");
        return Result.ok(out);
    }

    // ==================================================================
    // 8. PDF 工具
    // ==================================================================
    private List<String> parseJsonArr(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || "[]".equals(raw)) return out;
        try { for (Object o : cn.hutool.json.JSONUtil.parseArray(raw)) out.add(String.valueOf(o)); } catch (Exception ignore) { }
        return out;
    }
    private String toJson(Object o) {
        cn.hutool.json.JSONArray arr = new cn.hutool.json.JSONArray();
        if (o instanceof List<?> li) for (Object x : li) arr.add(x);
        return arr.toString();
    }
    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:30px;color:#222;font-size:12px}" +
                "h1{text-align:center;font-size:19px;letter-spacing:2px;margin:0 0 8px}" +
                "h2{font-size:15px;margin:18px 0 6px;border-left:3px solid #444;padding-left:8px}" +
                "h3{font-size:13px;margin:10px 0 4px;color:#333}" +
                "table{width:100%;border-collapse:collapse;margin-top:6px}" +
                "td,th{border:1px solid #666;padding:6px;font-size:11px;text-align:left;vertical-align:top}" +
                "th{background:#f2f2f2;text-align:center}" +
                ".n{background:#f7f7f7;text-align:center;width:96px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".record{margin-top:8px;line-height:1.9;white-space:pre-wrap}" +
                ".ph{font-size:11px;color:#444}" +
                ".seal{text-align:right;margin-top:10px;font-size:12px;color:#333}" +
                ".seal-box img{max-height:80px}" +
                "</style></head><body>" + body + "</body></html>";
    }
    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }
}
