package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
 * B5 隐患排查治理 · 完整流程闭环（详细设计 13）。
 *
 * <p>与既有接口分工：{@link BizFlowController} 保留 "/api/flow/danger/{id}/rectify"、
 * "/api/flow/danger/{id}/accept" 与 "/api/flow/check/record" 三检异常转隐患作为历史入口；
 * DangerSpecialController（"/api/danger"）是 D4 危货专项台账/电子运单，与本模块无关。
 * 本控制器统一前缀 "/api/flow/danger"，负责 PC 端：8 类排查类型字典、系统级清单范本（H8）、
 * 排查批次逐项判定与不合格自动联动建单（H6）、隐患状态机（H3）、闭环五要素（H7）、
 * 超期督办、闭环率、超管纠错（H4）、PDF 成品与装盒八归档、跨模块联动。
 *
 * <p>状态机：pending 待整改 → rectifying 整改中 → verify 待验收 → closed 已闭环；
 * 验收 reject 退回 rectifying；超过 deadline 未闭环自动标记 overdue 并督办。
 * 每次流转写 danger_flow_log，隐患登记时自动联动生成整改单 danger_rectify（H6）。
 *
 * <p>小程序边界（用户 2026-11-10 确认）：仅 /expiring 与 /mine 两个聚合 GET 预留数据接口，
 * 不新增、不改动小程序端页面与功能。
 */
@RestController
@RequestMapping("/api/flow/danger")
public class DangerFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public DangerFlowController(JdbcTemplate jdbc, PdfService pdfService,
                                StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    // 8 类排查类型（设计 13 §3.2）：三检异常=日级别隐患排查，与专项排查统一闭环
    private static final List<String[]> CHECK_TYPES = List.of(
            new String[]{"check_daily", "一日三检异常", "每天", "车辆", "三检报修自动生成（日级别隐患排查）"},
            new String[]{"driver_report", "司机随手上报", "随时", "任意", "司机小程序随手上报隐患"},
            new String[]{"monthly_full", "月度全面隐患排查", "每月", "企业全面", "每月一次全面排查"},
            new String[]{"monthly_major", "月度重大事故隐患排查（专项）", "每月", "重大事故隐患", "含 6 项一票否决重大事故隐患专项排查项"},
            new String[]{"holiday_pre", "重大节假日前隐患排查", "节假日前", "企业全面", "节假日前专项排查"},
            new String[]{"emergency_supply", "应急物资排查", "定期", "应急物资", "应急物资/器材/设备排查"},
            new String[]{"season_risk", "汛期/暑期重大风险隐患排查", "汛期/暑期", "重大风险", "季节性重大风险专项排查"},
            new String[]{"other_plan", "其他专项/日常检查", "按需", "自定义", "企业自定义的其他排查"});

    private static final Map<String, String> STATUS = new LinkedHashMap<>();
    static {
        STATUS.put("pending", "待整改");
        STATUS.put("rectifying", "整改中");
        STATUS.put("verify", "待验收");
        STATUS.put("closed", "已闭环");
        STATUS.put("overdue", "超期");
    }
    private static final List<String[]> FLOW = List.of(
            new String[]{"pending", "待整改"}, new String[]{"rectifying", "整改中"},
            new String[]{"verify", "待验收"}, new String[]{"closed", "已闭环"});

    // 系统级清单范本（H8 / 设计 §4.6）：{排查类型, 名称, [项目名称, 项目类别, 检查内容与标准, 是否重大(1/0)]}
    private static final Map<String, Object[]> SYS_TEMPLATES = new LinkedHashMap<>();
    static {
        String major = "重大事故隐患排查项", base = "基础管理";
        String[][] majorItems = {
                {"特殊天气", major, "经营地或运营线路途经地已发布台风橙色及以上预警，暴雨、暴雪、冰雹、大雾、沙尘暴、大风、道路结冰红色预警，或地质灾害气象风险红色预警等不具备安全通行条件时，是否执行停运指令或应急预案要求仍擅自安排运输作业", "1"},
                {"企业资质", major, "是否存在未取得经营许可或未按规定备案从事经营活动，或超出许可（备案）事项和有效期经营的情况", "1"},
                {"三超一疲劳", major, "所属驾驶员和车辆是否存在长期三超一疲劳（超速、超员、超载、疲劳驾驶）且未及时提醒纠正、运输结束后一个月内未严肃处理，或存在一次计 10 分及以上诚信考核计分仍继续安排上岗作业", "1"},
                {"夹带危货", major, "所属货运车辆是否故意夹带危险货物或违规运输禁运、限运物品，且未及时提醒纠正、运输结束后一个月内未严肃处理", "1"},
                {"违法超载", major, "所属货运车辆运输过程中是否违法装载导致车货总质量超过 100 吨", "1"},
                {"车辆改装", major, "是否使用报废、擅自改装、拼装、检验检测不合格（含未在有效期内）以及其他不符合国家规定的车辆装备、设施设备从事经营活动", "1"},
        };
        String[][] baseItems = {
                {"安全会议管理", base, "企业安全生产例会是否按制度召开，有会议记录（会议通知、参会人员签到、会议内容材料、会议照片等）", "0"},
                {"培训教育", base, "是否按计划开展从业人员安全培训教育，有培训记录、签到、考核与影像资料", "0"},
                {"一日三检", base, "车辆出车前、行车中、收车后三检是否落实，异常是否登记并闭环整改", "0"},
                {"动态监控", base, "卫星定位动态监控是否正常运行，超速/疲劳报警是否及时处置并留痕", "0"},
                {"应急物资", base, "灭火器、三角警示牌、应急锤、防滑链、急救箱等应急物资是否配置齐全且在有效期内", "0"},
                {"驾驶员管理", base, "驾驶员从业资格证、诚信考核、安全教育是否齐全有效", "0"},
                {"车辆维护", base, "车辆定期维护、二级维护、技术等级评定是否按期执行并有台账", "0"},
                {"隐患闭环", base, "上期排查发现的隐患是否按期整改并验收销号，闭环率是否达标", "0"},
                {"制度台账", base, "安全生产管理制度、岗位职责、操作规程是否健全并及时更新", "0"},
                {"安全投入", base, "安全生产费用是否按规定提取并专款专用，有使用台账", "0"},
        };
        SYS_TEMPLATES.put("monthly_major", new Object[]{"月度重大事故隐患排查清单（专项）", majorItems});
        // 月度全面 = 6 重大 + 10 基础
        String[][] full = Arrays.copyOf(majorItems, majorItems.length + baseItems.length);
        System.arraycopy(baseItems, 0, full, majorItems.length, baseItems.length);
        SYS_TEMPLATES.put("monthly_full", new Object[]{"月度全面隐患排查清单", full});
        SYS_TEMPLATES.put("holiday_pre", new Object[]{"重大节假日前隐患排查清单", join(majorItems, baseItems, 0, 5)});
        SYS_TEMPLATES.put("emergency_supply", new Object[]{"应急物资排查清单", new String[][]{
                {"消防器材", base, "灭火器、消防栓、消防沙等配置齐全，压力正常且在有效期内", "0"},
                {"应急防护", base, "应急锤、反光背心、三角警示牌、防滑链、急救箱配备到位", "0"},
                {"应急照明", base, "应急照明、警示灯具电量充足、功能正常", "0"},
                {"应急预案", base, "应急预案是否更新并组织演练，有演练记录与评估", "0"},
        }});
        SYS_TEMPLATES.put("season_risk", new Object[]{"汛期/暑期重大风险隐患排查清单", new String[][]{
                {"防台防汛", major, "台风、暴雨红色预警时是否停止运输作业并落实车辆停放与加固", "1"},
                {"高温作业", major, "高温天气危货/普货车辆罐体、轮胎、制动是否落实降温与检查，防止自燃爆胎", "1"},
                {"汛期线路", base, "运营线路途经地地质灾害风险排查与绕行预案是否落实", "0"},
                {"汛期物资", base, "防汛沙袋、抽水泵、应急照明等汛期物资是否储备到位", "0"},
        }});
        SYS_TEMPLATES.put("other_plan", new Object[]{"日常/专项通用排查清单", join(majorItems, baseItems, 0, 4)});
        SYS_TEMPLATES.put("check_daily", new Object[]{"一日三检异常（日级别）", new String[][]{
                {"制动系统", base, "制动效能、制动管路、制动异响检查", "0"},
                {"轮胎", base, "胎纹深度、胎压、异常磨损检查", "0"},
        }});
        SYS_TEMPLATES.put("driver_report", new Object[]{"司机随手上报", new String[][]{
                {"随手上报隐患", base, "司机发现的其他隐患（拍照/语音/文字描述）", "0"},
        }});
    }

    private static String[][] join(String[][] a, String[][] b, int bFrom, int bTo) {
        int n = Math.min(bTo, b.length);
        String[][] r = Arrays.copyOf(a, a.length + Math.max(0, n - bFrom));
        System.arraycopy(b, bFrom, r, a.length, Math.max(0, n - bFrom));
        return r;
    }

    // ==================================================================
    // 0. 通用工具
    // ==================================================================
    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private long l(Object o) { if (o == null) return 0L; try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; } }
    private int i(Object o) { if (o == null) return 0; try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; } }
    private String esc(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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
    private String operator() {
        TenantContextHolder.Context c = TenantContextHolder.get();
        return c == null ? "" : s(c.getUsername());
    }
    private Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m;
    }
    private String personName(Object id) {
        if (id == null || l(id) == 0L) return "";
        Map<String, Object> p = one("SELECT `name` FROM `person` WHERE `id`=? AND `deleted`=0", l(id));
        return s(p.get("name"));
    }
    private String typeName(String code) {
        for (String[] t : CHECK_TYPES) if (t[0].equals(code)) return t[1];
        return code == null ? "" : code;
    }
    private String statusName(String code) { return STATUS.getOrDefault(code, code == null ? "" : code); }

    /** 状态流转日志（H7 全流程留痕） */
    private void log(long dangerId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `danger_flow_log` (`id`,`tenant_id`,`danger_id`,`action`," +
                            "`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), dangerId, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
    }

    /** 惰性初始化系统级清单范本（tenant_id=0，幂等，不覆盖企业自定义） */
    private void seedTemplates() {
        for (Map.Entry<String, Object[]> e : SYS_TEMPLATES.entrySet()) {
            long n = count("SELECT COUNT(*) FROM `danger_check_template` WHERE `tenant_id`=0 " +
                    "AND `check_type`=? AND `is_default`=1 AND `deleted`=0", e.getKey());
            if (n > 0) continue;
            String name = s(e.getValue()[0]);
            String[][] items = (String[][]) e.getValue()[1];
            JSONArray arr = new JSONArray();
            for (String[] it : items) {
                JSONObject o = new JSONObject();
                o.set("name", it[0]); o.set("category", it[1]);
                o.set("standard", it[2]); o.set("major", "1".equals(it[3]) ? 1 : 0);
                arr.add(o);
            }
            exec("INSERT INTO `danger_check_template` (`id`,`tenant_id`,`template_name`,`check_type`," +
                            "`item_list`,`is_default`,`created_at`,`created_by`,`deleted`) VALUES (?,0,?,?,?,1,?,0,0)",
                    IdUtil.getSnowflakeNextId(), name, e.getKey(), arr.toString(), LocalDateTime.now());
        }
    }

    /** 超期自动标记 + 自动督办（惰性扫描，幂等；closed 不参与） */
    private void scanOverdue() {
        List<Map<String, Object>> rows = q("SELECT `id`,`status`,`remind_count` FROM `danger_record` " +
                "WHERE `tenant_id`=? AND `deleted`=0 AND `status` NOT IN ('closed','overdue') " +
                "AND `deadline` IS NOT NULL AND `deadline` < ?", tid(), LocalDateTime.now());
        for (Map<String, Object> r : rows) {
            long id = l(r.get("id"));
            exec("UPDATE `danger_record` SET `status`='overdue',`remind_count`=`remind_count`+1," +
                    "`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
            log(id, "overdue", s(r.get("status")), "overdue", "超过整改期限未闭环，自动标记超期并督办");
            exec("INSERT INTO `danger_remind` (`id`,`tenant_id`,`danger_id`,`remind_type`,`content`," +
                            "`operator`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,'overdue',?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), id, "整改已超期，请立即处理并督办责任人",
                    "系统", LocalDateTime.now(), 0L);
        }
    }

    // ==================================================================
    // 1. 概览 / 字典 / 督办看板
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String checkType,
                                                @RequestParam(required = false) String keyword) {
        seedTemplates();
        scanOverdue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("statusMap", STATUS);
        out.put("flow", FLOW.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("code", f[0]); m.put("name", f[1]); return m;
        }).toList());
        out.put("checkTypes", CHECK_TYPES.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t[0]); m.put("name", t[1]); m.put("freq", t[2]);
            m.put("dim", t[3]); m.put("note", t[4]); return m;
        }).toList());

        long total = count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0", tid());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        for (String st : STATUS.keySet())
            stats.put(st, count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 AND `status`=?", tid(), st));
        long closed = l(stats.get("closed"));
        stats.put("open", total - closed);
        stats.put("closureRate", total == 0 ? 100 : Math.round(closed * 1000.0 / total) / 10.0);
        stats.put("major", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 AND `danger_level`='重大'", tid()));
        stats.put("plans", count("SELECT COUNT(*) FROM `danger_check_plan` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        out.put("stats", stats);

        out.put("records", records(status, checkType, keyword));
        out.put("plans", plans());
        out.put("templates", templates());
        out.put("board", board());
        out.put("archives", archives());
        out.put("refs", referencesData());
        return Result.ok(out);
    }

    @GetMapping("/check-types")
    public Result<List<Map<String, Object>>> checkTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] t : CHECK_TYPES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t[0]); m.put("name", t[1]); m.put("freq", t[2]);
            m.put("dim", t[3]); m.put("note", t[4]); out.add(m);
        }
        return Result.ok(out);
    }

    /** 督办看板：所有未闭环隐患，超期高亮 + 临期 */
    private List<Map<String, Object>> board() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`danger_level`,`status`,`deadline`," +
                "`remind_count`,`find_time` FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `status`<>'closed' ORDER BY FIELD(`status`,'overdue','verify','rectifying','pending')," +
                "FIELD(`danger_level`,'重大','较大','一般'),`id` DESC LIMIT 50", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(s(r.get("status"))));
            boolean near = r.get("deadline") != null && !"overdue".equals(s(r.get("status")));
            m.put("near", near);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        scanOverdue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("board", board());
        out.put("total", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 AND `status`<>'closed'", tid()));
        out.put("overdue", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 AND `status`='overdue'", tid()));
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 排查清单范本（H8：系统级只读 + 企业自定义覆盖）
    // ==================================================================
    private List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`tenant_id`,`template_name`,`check_type`,`item_list`," +
                "`is_default` FROM `danger_check_template` WHERE (`tenant_id`=0 OR `tenant_id`=?) " +
                "AND `deleted`=0 ORDER BY `is_default` DESC,`check_type`,`id`", tid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("scope", l(r.get("tenant_id")) == 0L ? "system" : "enterprise");
            m.put("templateName", s(r.get("template_name")));
            m.put("checkType", s(r.get("check_type")));
            m.put("checkTypeName", typeName(s(r.get("check_type"))));
            m.put("isDefault", i(r.get("is_default")) == 1);
            m.put("items", parseItems(s(r.get("item_list"))));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> parseItems(String raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        try {
            JSONArray arr = JSONUtil.parseArray(raw);
            for (Object o : arr) {
                JSONObject j = (JSONObject) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", j.getStr("name")); m.put("category", j.getStr("category"));
                m.put("standard", j.getStr("standard")); m.put("major", j.getInt("major", 0) == 1);
                out.add(m);
            }
        } catch (Exception ignore) { }
        return out;
    }

    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> templatesApi(@RequestParam(required = false) String checkType) {
        seedTemplates();
        List<Map<String, Object>> all = templates();
        if (checkType != null && !checkType.isBlank())
            return Result.ok(all.stream().filter(t -> checkType.equals(t.get("checkType"))).toList());
        return Result.ok(all);
    }

    @GetMapping("/template/{id}")
    public Result<Map<String, Object>> templateDetail(@PathVariable long id) {
        Map<String, Object> r = one("SELECT * FROM `danger_check_template` WHERE `id`=? " +
                "AND (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0", id, tid());
        if (r.isEmpty()) return Result.ok(fail("范本不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.get("id")));
        m.put("templateName", s(r.get("template_name")));
        m.put("checkType", s(r.get("check_type")));
        m.put("scope", l(r.get("tenant_id")) == 0L ? "system" : "enterprise");
        m.put("items", parseItems(s(r.get("item_list"))));
        return Result.ok(m);
    }

    /** 复制系统范本为企业自定义 / 新建范本（H8：企业可覆盖系统默认，不修改 tenant_id=0） */
    @PostMapping("/template")
    public Result<Map<String, Object>> saveTemplate(@RequestBody Map<String, Object> b) {
        String type = s(b.get("checkType"));
        String name = s(b.get("templateName"));
        if (type.isBlank() || name.isBlank()) return Result.ok(fail("排查类型与清单名称不能为空"));
        Object rawItems = b.get("items");
        List<?> items = rawItems instanceof List<?> li ? li : List.of();
        JSONArray arr = new JSONArray();
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> im)) continue;
            JSONObject j = new JSONObject();
            j.set("name", s(im.get("name"))); j.set("category", s(im.get("category")));
            j.set("standard", s(im.get("standard")));
            j.set("major", Boolean.TRUE.equals(im.get("major")) || "1".equals(s(im.get("major"))) ? 1 : 0);
            arr.add(j);
        }
        long src = l(b.get("sourceId"));
        long nid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `danger_check_template` (`id`,`tenant_id`,`template_name`,`check_type`," +
                        "`item_list`,`is_default`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,0,?,?,0)",
                nid, tid(), name, type, arr.toString(), LocalDateTime.now(), TenantContextHolder.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("id", String.valueOf(nid));
        out.put("items", arr.size()); out.put("copiedFrom", src == 0 ? "" : String.valueOf(src));
        return Result.ok(out);
    }

    @PutMapping("/template/{id}")
    public Result<Map<String, Object>> updateTemplate(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> r = one("SELECT * FROM `danger_check_template` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (r.isEmpty()) return Result.ok(fail("系统级范本不可修改，请复制为企业自定义范本"));
        String name = s(b.getOrDefault("templateName", r.get("template_name")));
        Object rawItems = b.get("items");
        String itemJson = s(r.get("item_list"));
        if (rawItems instanceof List<?> li) {
            JSONArray arr = new JSONArray();
            for (Object it : li) {
                if (!(it instanceof Map<?, ?> im)) continue;
                JSONObject j = new JSONObject();
                j.set("name", s(im.get("name"))); j.set("category", s(im.get("category")));
                j.set("standard", s(im.get("standard")));
                j.set("major", Boolean.TRUE.equals(im.get("major")) || "1".equals(s(im.get("major"))) ? 1 : 0);
                arr.add(j);
            }
            itemJson = arr.toString();
        }
        exec("UPDATE `danger_check_template` SET `template_name`=?,`item_list`=?,`updated_at`=? WHERE `id`=?",
                name, itemJson, LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true, "id", String.valueOf(id)));
    }

    @DeleteMapping("/template/{id}")
    public Result<Map<String, Object>> deleteTemplate(@PathVariable long id) {
        Map<String, Object> r = one("SELECT * FROM `danger_check_template` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (r.isEmpty()) return Result.ok(fail("系统级范本不可删除，或范本不存在"));
        exec("UPDATE `danger_check_template` SET `deleted`=1,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true));
    }

    // ==================================================================
    // 3. 排查批次：发起排查 → 带出范本 → 逐项判定 → 不合格联动建单（H6）
    // ==================================================================
    @PostMapping("/plan")
    public Result<Map<String, Object>> createPlan(@RequestBody Map<String, Object> b) {
        seedTemplates();
        String type = s(b.get("checkType"));
        if (type.isBlank()) return Result.ok(fail("请选择排查类型"));
        String title = s(b.get("title"));
        if (title.isBlank()) title = LocalDate.now().getYear() + "年" + LocalDate.now().getMonthValue() + "月 " + typeName(type);
        long tplId = l(b.get("templateId"));
        Map<String, Object> tpl = new LinkedHashMap<>();
        if (tplId != 0L) {
            tpl = one("SELECT * FROM `danger_check_template` WHERE `id`=? AND (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0", tplId, tid());
        } else {
            tpl = one("SELECT * FROM `danger_check_template` WHERE `check_type`=? AND (`tenant_id`=? OR `tenant_id`=0) " +
                    "AND `deleted`=0 ORDER BY `tenant_id` DESC,`is_default` DESC,`id` LIMIT 1", type, tid());
        }
        List<Map<String, Object>> items = parseItems(s(tpl.get("item_list")));
        if (items.isEmpty()) {
            Object[] seed = SYS_TEMPLATES.get(type);
            if (seed != null) {
                for (String[] it : (String[][]) seed[1]) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", it[0]); m.put("category", it[1]);
                    m.put("standard", it[2]); m.put("major", "1".equals(it[3]));
                    items.add(m);
                }
            }
        }
        long planId = IdUtil.getSnowflakeNextId();
        String planNo = "PC-" + LocalDate.now().toString().replace("-", "").substring(0, 6)
                + "-" + (planId % 10000);
        String checkDate = s(b.get("checkDate"));
        if (checkDate.isBlank()) checkDate = LocalDate.now().toString();
        exec("INSERT INTO `danger_check_plan` (`id`,`tenant_id`,`plan_no`,`title`,`check_type`,`template_id`," +
                        "`template_name`,`checker_id`,`check_date`,`item_total`,`problem_total`,`danger_total`," +
                        "`status`,`remark`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,0,0,'doing',?,?,?,0)",
                planId, tid(), planNo, title, type, l(tpl.get("id")), s(tpl.get("template_name")),
                l(b.get("checkerId")) == 0L ? null : l(b.get("checkerId")), checkDate,
                items.size(), s(b.get("remark")), LocalDateTime.now(), TenantContextHolder.userId());
        int sort = 0;
        for (Map<String, Object> it : items) {
            sort++;
            exec("INSERT INTO `danger_check_item` (`id`,`tenant_id`,`plan_id`,`category`,`item_name`," +
                            "`standard`,`is_major`,`result`,`sort_no`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,'pending',?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), planId, s(it.get("category")), s(it.get("name")),
                    s(it.get("standard")), Boolean.TRUE.equals(it.get("major")) ? 1 : 0,
                    sort, LocalDateTime.now());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("planId", String.valueOf(planId));
        out.put("planNo", planNo); out.put("items", items.size());
        out.put("templateName", s(tpl.get("template_name")));
        return Result.ok(out);
    }

    private List<Map<String, Object>> plans() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT p.*, (SELECT COUNT(*) FROM `danger_check_item` x " +
                "WHERE x.`plan_id`=p.`id` AND x.`result`='fail' AND x.`deleted`=0) AS problems " +
                "FROM `danger_check_plan` p WHERE p.`tenant_id`=? AND p.`deleted`=0 ORDER BY p.`id` DESC LIMIT 30", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("checkTypeName", typeName(s(r.get("check_type"))));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/plans")
    public Result<List<Map<String, Object>>> plansApi() { seedTemplates(); return Result.ok(plans()); }

    @GetMapping("/plan/{id}")
    public Result<Map<String, Object>> planDetail(@PathVariable long id) {
        Map<String, Object> p = one("SELECT * FROM `danger_check_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (p.isEmpty()) return Result.ok(fail("排查批次不存在"));
        Map<String, Object> out = new LinkedHashMap<>(p);
        out.put("id", String.valueOf(p.get("id")));
        out.put("checkTypeName", typeName(s(p.get("check_type"))));
        out.put("checkerName", personName(p.get("checker_id")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `danger_check_item` WHERE `plan_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `sort_no`,`id`", id, tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("dangerId", r.get("danger_id") == null ? "" : String.valueOf(r.get("danger_id")));
            items.add(m);
        }
        out.put("items", items);
        return Result.ok(out);
    }

    /** 逐项判定：single 单条 / pass 全合格。fail 自动联动生成隐患单 + 整改单（H6） */
    @PutMapping("/plan/{id}/item")
    public Result<Map<String, Object>> judgeItem(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> p = one("SELECT * FROM `danger_check_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (p.isEmpty()) return Result.ok(fail("排查批次不存在"));
        String mode = s(b.get("mode"));
        List<Map<String, Object>> targets = new ArrayList<>();
        if ("pass".equals(mode)) {
            targets = q("SELECT * FROM `danger_check_item` WHERE `plan_id`=? AND `tenant_id`=? " +
                    "AND `result`='pending' AND `deleted`=0", id, tid());
        } else {
            long itemId = l(b.get("itemId"));
            Map<String, Object> it = one("SELECT * FROM `danger_check_item` WHERE `id`=? AND `plan_id`=? AND `tenant_id`=? AND `deleted`=0",
                    itemId, id, tid());
            if (it.isEmpty()) return Result.ok(fail("检查项不存在"));
            targets.add(it);
        }
        String result = "pass".equals(mode) ? "pass" : ("pass".equals(s(b.get("result"))) ? "pass" : "fail");
        int created = 0;
        for (Map<String, Object> it : targets) {
            long itemId = l(it.get("id"));
            long dangerId = 0L;
            if ("fail".equals(result)) {
                // H6：不合格自动联动生成隐患单 + 整改单，无需二次录入
                dangerId = createRecordInternal(
                        s(p.get("check_type")), "排查不合格：" + s(it.get("item_name")),
                        i(it.get("is_major")) == 1 ? "重大" : "一般", "",
                        0L, l(p.get("checker_id")), "", "", id, 0L, "", "");
                created++;
            }
            exec("UPDATE `danger_check_item` SET `result`=?,`remark`=?,`danger_id`=?,`updated_at`=? WHERE `id`=?",
                    result, s(b.get("remark")), dangerId == 0L ? null : dangerId, LocalDateTime.now(), itemId);
        }
        // 回写批次统计
        long probs = count("SELECT COUNT(*) FROM `danger_check_item` WHERE `plan_id`=? AND `result`='fail' AND `deleted`=0", id);
        long dangers = count("SELECT COUNT(*) FROM `danger_record` WHERE `check_plan_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        exec("UPDATE `danger_check_plan` SET `problem_total`=?,`danger_total`=?,`updated_at`=? WHERE `id`=?",
                probs, dangers, LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true, "judged", targets.size(), "created", created,
                "problemTotal", (int) probs, "dangerTotal", (int) dangers));
    }

    @PostMapping("/plan/{id}/finish")
    public Result<Map<String, Object>> finishPlan(@PathVariable long id) {
        Map<String, Object> p = one("SELECT * FROM `danger_check_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (p.isEmpty()) return Result.ok(fail("排查批次不存在"));
        exec("UPDATE `danger_check_plan` SET `status`='done',`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true, "status", "done"));
    }

    // ==================================================================
    // 4. 隐患主表：登记（联动整改单）/ 列表 / 详情（五要素）
    // ==================================================================
    /** 内部建单：登记隐患 + 自动生成整改单（H6）+ 流转日志 + 归档登记，返回隐患 ID */
    private long createRecordInternal(String checkType, String title, String level, String location,
                                      long vehicleId, long finderId, String photos, String voice,
                                      long planId, long refId, String rectifyRequire, String note) {
        long did = IdUtil.getSnowflakeNextId();
        LocalDateTime now = LocalDateTime.now();
        exec("INSERT INTO `danger_record` (`id`,`tenant_id`,`check_type`,`ref_id`,`check_plan_id`,`title`," +
                        "`danger_level`,`location`,`vehicle_id`,`finder_id`,`find_time`,`photos`,`voice_url`," +
                        "`status`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'pending',?,?,0)",
                did, tid(), checkType, refId == 0L ? null : refId, planId == 0L ? null : planId,
                title, level, location, vehicleId == 0L ? null : vehicleId,
                finderId == 0L ? null : finderId, now, photos, voice, now, TenantContextHolder.userId());
        // H6：自动生成整改单（默认 7 天期限），后续 /rectify 下发时更新同一张
        long rid = IdUtil.getSnowflakeNextId();
        String rectifyNo = "ZG-" + LocalDate.now().toString().replace("-", "") + "-" + (did % 10000);
        String req = rectifyRequire.isBlank() ? "限期整改并反馈整改凭证，完成后由安全员验收销号" : rectifyRequire;
        exec("INSERT INTO `danger_rectify` (`id`,`tenant_id`,`danger_id`,`rectify_no`,`rectify_require`," +
                        "`deadline`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,0)",
                rid, tid(), did, rectifyNo, req, now.plusDays(7), now, TenantContextHolder.userId());
        exec("UPDATE `danger_record` SET `deadline`=? WHERE `id`=?", now.plusDays(7), did);
        log(did, "create", "", "pending", "登记隐患（" + typeName(checkType) + "）并自动联动生成整改单 " + rectifyNo
                + (note.isBlank() ? "" : "；" + note));
        return did;
    }

    /** 统一登记入口（各排查类型）：POST /record */
    @PostMapping("/record")
    public Result<Map<String, Object>> createRecord(@RequestBody Map<String, Object> b) {
        String type = s(b.get("checkType"));
        String title = s(b.get("title"));
        if (type.isBlank()) return Result.ok(fail("请选择排查类型"));
        if (title.isBlank()) return Result.ok(fail("请填写隐患名称/描述"));
        long did = createRecordInternal(type, title,
                s(b.get("dangerLevel")).isBlank() ? "一般" : s(b.get("dangerLevel")),
                s(b.get("location")), l(b.get("vehicleId")), l(b.get("finderId")),
                s(b.get("photos")), s(b.get("voiceUrl")), l(b.get("planId")), l(b.get("refId")),
                s(b.get("rectifyRequire")), "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("dangerId", String.valueOf(did));
        out.put("rectifyNo", s(one("SELECT `rectify_no` FROM `danger_rectify` WHERE `danger_id`=? AND `deleted`=0 LIMIT 1", did).get("rectify_no")));
        out.put("status", "pending");
        out.put("note", "已自动联动生成整改单（H6）");
        return Result.ok(out);
    }

    /** 三检异常显式幂等入口（B4 CheckFlowController 已直写，此接口供联动/小程序预留，按 refId 去重） */
    @PostMapping("/from-check")
    public Result<Map<String, Object>> fromCheck(@RequestBody Map<String, Object> b) {
        long refId = l(b.get("refId"));
        if (refId == 0L) return Result.ok(fail("缺少三检记录 refId"));
        Map<String, Object> dup = one("SELECT `id` FROM `danger_record` WHERE `tenant_id`=? " +
                "AND `check_type`='check_daily' AND `ref_id`=? AND `deleted`=0 LIMIT 1", tid(), refId);
        if (!dup.isEmpty())
            return Result.ok(Map.of("ok", true, "dangerId", String.valueOf(dup.get("id")), "duplicated", true));
        long did = createRecordInternal("check_daily",
                s(b.get("title")).isBlank() ? "三检异常：" + s(b.get("note")) : s(b.get("title")),
                "一般", "", l(b.get("vehicleId")), l(b.get("driverId")),
                s(b.get("photos")), s(b.get("voiceUrl")), 0L, refId, "", s(b.get("note")));
        return Result.ok(Map.of("ok", true, "dangerId", String.valueOf(did), "duplicated", false));
    }

    private List<Map<String, Object>> records(String status, String checkType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT d.*, v.`plate_no`, p.`name` AS finder_name " +
                "FROM `danger_record` d LEFT JOIN `vehicle` v ON v.`id`=d.`vehicle_id` " +
                "LEFT JOIN `person` p ON p.`id`=d.`finder_id` " +
                "WHERE d.`tenant_id`=? AND d.`deleted`=0");
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (status != null && !status.isBlank()) { sql.append(" AND d.`status`=?"); args.add(status); }
        if (checkType != null && !checkType.isBlank()) { sql.append(" AND d.`check_type`=?"); args.add(checkType); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (d.`title` LIKE ? OR v.`plate_no` LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY FIELD(d.`status`,'overdue','pending','rectifying','verify','closed')," +
                "FIELD(d.`danger_level`,'重大','较大','一般'), d.`id` DESC LIMIT 100");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("checkTypeName", typeName(s(r.get("check_type"))));
            m.put("statusName", statusName(s(r.get("status"))));
            m.put("findTime", s(r.get("find_time")));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/records")
    public Result<List<Map<String, Object>>> recordsApi(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String checkType,
                                                        @RequestParam(required = false) String keyword) {
        scanOverdue();
        return Result.ok(records(status, checkType, keyword));
    }

    /** 隐患详情：闭环五要素 + 整改单 + 验收记录 + 流转时间线 */
    @GetMapping("/record/{id}")
    public Result<Map<String, Object>> recordDetail(@PathVariable long id) {
        Map<String, Object> d = one("SELECT d.*, v.`plate_no`, p.`name` AS finder_name " +
                "FROM `danger_record` d LEFT JOIN `vehicle` v ON v.`id`=d.`vehicle_id` " +
                "LEFT JOIN `person` p ON p.`id`=d.`finder_id` " +
                "WHERE d.`id`=? AND d.`tenant_id`=? AND d.`deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("id", String.valueOf(d.get("id")));
        out.put("checkTypeName", typeName(s(d.get("check_type"))));
        out.put("statusName", statusName(s(d.get("status"))));

        // 整改单（H6 自动生成，取最新一条）
        List<Map<String, Object>> rectifies = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT r.*, p.`name` AS responsible_name FROM `danger_rectify` r " +
                "LEFT JOIN `person` p ON p.`id`=r.`responsible_id` " +
                "WHERE r.`danger_id`=? AND r.`tenant_id`=? AND r.`deleted`=0 ORDER BY r.`id` DESC", id, tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id"))); rectifies.add(m);
        }
        // 验收记录（可多条：退回后重新验收）
        List<Map<String, Object>> accepts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT a.*, p.`name` AS acceptor_name FROM `danger_accept` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`acceptor_id` " +
                "WHERE a.`danger_id`=? AND a.`tenant_id`=? AND a.`deleted`=0 ORDER BY a.`id`", id, tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id"))); accepts.add(m);
        }
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `action`,`from_status`,`to_status`,`detail`,`operator`,`created_at` " +
                "FROM `danger_flow_log` WHERE `danger_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `id`", id, tid())) {
            logs.add(new LinkedHashMap<>(r));
        }
        List<Map<String, Object>> reminds = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `remind_type`,`content`,`operator`,`created_at` FROM `danger_remind` " +
                "WHERE `danger_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC", id, tid())) {
            reminds.add(new LinkedHashMap<>(r));
        }
        out.put("rectifies", rectifies);
        out.put("accepts", accepts);
        out.put("logs", logs);
        out.put("reminds", reminds);

        // 闭环五要素（H7）：隐患情况 / 检查人 / 整改情况 / 整改人 / 验收人
        Map<String, Object> five = new LinkedHashMap<>();
        five.put("dangerInfo", s(d.get("title")) + "（" + s(d.get("danger_level")) + "）");
        five.put("finder", s(d.get("finder_name")).isBlank() ? personName(d.get("finder_id")) : s(d.get("finder_name")));
        five.put("rectifyInfo", rectifies.isEmpty() ? "" : s(rectifies.get(0).get("rectify_note")));
        five.put("responsible", rectifies.isEmpty() ? "" : s(rectifies.get(0).get("responsible_name")));
        five.put("acceptor", accepts.isEmpty() ? "" : s(accepts.get(accepts.size() - 1).get("acceptor_name")));
        out.put("five", five);
        return Result.ok(out);
    }

    // ==================================================================
    // 5. 闭环状态机：下发整改 → 整改反馈 → 验收（通过/退回）→ 督办 → 纠错
    // ==================================================================
    @PostMapping("/record/{id}/rectify")
    public Result<Map<String, Object>> rectify(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `danger_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        if ("closed".equals(s(d.get("status")))) return Result.ok(fail("已闭环隐患不可再次下发整改"));
        long responsible = l(b.get("responsibleId"));
        String deadlineStr = s(b.get("deadline"));
        LocalDateTime deadline;
        try {
            String v = deadlineStr.length() == 10 ? deadlineStr + "T00:00:00"
                    : deadlineStr.replace(' ', 'T').substring(0, Math.min(19, deadlineStr.length()));
            deadline = v.length() >= 16 ? LocalDateTime.parse(v) : LocalDateTime.now().plusDays(7);
        } catch (Exception e) { deadline = LocalDateTime.now().plusDays(7); }
        String req = s(b.get("rectifyRequire")).isBlank()
                ? "限期整改并反馈整改凭证，完成后由安全员验收销号" : s(b.get("rectifyRequire"));
        // H6 联动生成的整改单：更新同一张，不重复插入
        Map<String, Object> r = one("SELECT `id` FROM `danger_rectify` WHERE `danger_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `id` DESC LIMIT 1", id, tid());
        if (r.isEmpty()) {
            exec("INSERT INTO `danger_rectify` (`id`,`tenant_id`,`danger_id`,`rectify_no`,`rectify_require`," +
                            "`responsible_id`,`deadline`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), id, "ZG-" + System.currentTimeMillis(), req,
                    responsible == 0L ? null : responsible, deadline, LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            exec("UPDATE `danger_rectify` SET `rectify_require`=?,`responsible_id`=?,`deadline`=?,`updated_at`=? WHERE `id`=?",
                    req, responsible == 0L ? null : responsible, deadline, LocalDateTime.now(), l(r.get("id")));
        }
        String from = s(d.get("status"));
        exec("UPDATE `danger_record` SET `status`='rectifying',`deadline`=?,`updated_at`=? WHERE `id`=?",
                deadline, LocalDateTime.now(), id);
        log(id, "rectify", from, "rectifying", "下发整改：责任人 " + personName(responsible)
                + "，期限 " + deadline.toString().substring(0, 10) + "，要求 " + req);
        return Result.ok(Map.of("ok", true, "status", "rectifying", "deadline", deadline.toString()));
    }

    @PostMapping("/record/{id}/feedback")
    public Result<Map<String, Object>> feedback(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `danger_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        String st = s(d.get("status"));
        if (!List.of("rectifying", "overdue").contains(st))
            return Result.ok(fail("仅整改中/超期状态可提交整改反馈，当前 " + statusName(st)));
        String note = s(b.get("rectifyNote"));
        String photos = s(b.get("rectifyPhotos"));
        Map<String, Object> r = one("SELECT `id` FROM `danger_rectify` WHERE `danger_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `id` DESC LIMIT 1", id, tid());
        if (!r.isEmpty()) {
            exec("UPDATE `danger_rectify` SET `rectify_note`=?,`rectify_photos`=?,`submit_time`=?,`updated_at`=? WHERE `id`=?",
                    note, photos, LocalDateTime.now(), LocalDateTime.now(), l(r.get("id")));
        }
        exec("UPDATE `danger_record` SET `status`='verify',`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
        log(id, "feedback", st, "verify", "整改反馈：" + (note.isBlank() ? "已提交整改凭证" : note));
        return Result.ok(Map.of("ok", true, "status", "verify"));
    }

    @PostMapping("/record/{id}/accept")
    public Result<Map<String, Object>> accept(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `danger_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        String st = s(d.get("status"));
        if (!"verify".equals(st)) return Result.ok(fail("仅待验收状态可验收，当前 " + statusName(st)));
        String result = s(b.get("acceptResult"));
        if (!List.of("pass", "reject").contains(result)) return Result.ok(fail("acceptResult 应为 pass/reject"));
        long acceptor = l(b.get("acceptorId"));
        String note = s(b.get("acceptNote"));
        exec("INSERT INTO `danger_accept` (`id`,`tenant_id`,`danger_id`,`acceptor_id`,`accept_result`," +
                        "`accept_note`,`accept_time`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, acceptor == 0L ? null : acceptor,
                result, note, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        String to = "pass".equals(result) ? "closed" : "rectifying";
        if ("pass".equals(result)) {
            exec("UPDATE `danger_record` SET `status`='closed',`closed_time`=?,`updated_at`=? WHERE `id`=?",
                    LocalDateTime.now(), LocalDateTime.now(), id);
        } else {
            exec("UPDATE `danger_record` SET `status`='rectifying',`updated_at`=? WHERE `id`=?",
                    LocalDateTime.now(), id);
        }
        log(id, "pass".equals(result) ? "accept" : "reject", st, to,
                ("pass".equals(result) ? "验收通过，隐患销号闭环" : "验收不通过，退回整改") + "；验收人 "
                        + personName(acceptor) + (note.isBlank() ? "" : "；意见 " + note));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", to); out.put("closed", "pass".equals(result));
        return Result.ok(out);
    }

    @PostMapping("/record/{id}/remind")
    public Result<Map<String, Object>> remind(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `danger_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        if ("closed".equals(s(d.get("status")))) return Result.ok(fail("已闭环隐患无需督办"));
        String content = s(b.get("content")).isBlank() ? "请尽快完成整改并反馈凭证" : s(b.get("content"));
        exec("INSERT INTO `danger_remind` (`id`,`tenant_id`,`danger_id`,`remind_type`,`content`," +
                        "`operator`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,'manual',?,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, content, operator(), LocalDateTime.now(), TenantContextHolder.userId());
        exec("UPDATE `danger_record` SET `remind_count`=`remind_count`+1,`updated_at`=? WHERE `id`=?",
                LocalDateTime.now(), id);
        log(id, "remind", s(d.get("status")), s(d.get("status")), "督办提醒：" + content);
        return Result.ok(Map.of("ok", true, "remindCount", i(d.get("remind_count")) + 1));
    }

    /** 超管纠错（H4 / @RequireRole），企业账号 403 */
    @PutMapping("/record/{id}")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> correct(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> d = one("SELECT * FROM `danger_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (d.isEmpty()) return Result.ok(fail("隐患记录不存在"));
        String title = s(b.getOrDefault("title", d.get("title")));
        String level = s(b.getOrDefault("dangerLevel", d.get("danger_level")));
        String status = s(b.getOrDefault("status", d.get("status")));
        if (!STATUS.containsKey(status)) return Result.ok(fail("状态值不合法"));
        exec("UPDATE `danger_record` SET `title`=?,`danger_level`=?,`status`=?,`updated_at`=? WHERE `id`=?",
                title, level, status, LocalDateTime.now(), id);
        log(id, "correct", s(d.get("status")), status, "超管纠错：调整隐患信息/状态");
        return Result.ok(Map.of("ok", true, "status", status));
    }

    // ==================================================================
    // 6. PDF 成品 + 装盒八归档（普货 GENERAL_BOX8 / 危货 DANGER_BOX9）
    // ==================================================================
    @GetMapping(value = "/record/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> recordPdf(@PathVariable long id) {
        Map<String, Object> d = one("SELECT d.*, v.`plate_no`, p.`name` AS finder_name " +
                "FROM `danger_record` d LEFT JOIN `vehicle` v ON v.`id`=d.`vehicle_id` " +
                "LEFT JOIN `person` p ON p.`id`=d.`finder_id` " +
                "WHERE d.`id`=? AND d.`tenant_id`=? AND d.`deleted`=0", id, tid());
        Map<String, Object> r = one("SELECT r.*, p.`name` AS responsible_name FROM `danger_rectify` r " +
                "LEFT JOIN `person` p ON p.`id`=r.`responsible_id` " +
                "WHERE r.`danger_id`=? AND r.`tenant_id`=? AND r.`deleted`=0 ORDER BY r.`id` DESC LIMIT 1", id, tid());
        Map<String, Object> a = one("SELECT a.*, p.`name` AS acceptor_name FROM `danger_accept` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`acceptor_id` " +
                "WHERE a.`danger_id`=? AND a.`tenant_id`=? AND a.`deleted`=0 ORDER BY a.`id` DESC LIMIT 1", id, tid());
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 隐患排查治理闭环单</h1>");
        body.append("<p class='tip'>排查类型：").append(esc(typeName(s(d.get("check_type")))))
                .append("　·　当前状态：").append(esc(statusName(s(d.get("status")))))
                .append("　·　归档盒：八、风险分级管控与隐患排查治理</p>");
        body.append("<h2>一、隐患情况</h2><table>")
                .append("<tr><td class='n'>隐患名称</td><td colspan='3'>").append(esc(s(d.get("title")))).append("</td></tr>")
                .append("<tr><td class='n'>隐患等级</td><td>").append(esc(s(d.get("danger_level"))))
                .append("</td><td class='n'>隐患位置</td><td>").append(esc(s(d.get("location")))).append("</td></tr>")
                .append("<tr><td class='n'>关联车辆</td><td>").append(esc(s(d.get("plate_no"))))
                .append("</td><td class='n'>发现时间</td><td>").append(esc(s(d.get("find_time")))).append("</td></tr>")
                .append("<tr><td class='n'>检查人</td><td colspan='3'>").append(esc(s(d.get("finder_name")))).append("</td></tr>")
                .append("</table>");
        body.append("<h2>二、整改情况（整改通知书）</h2><table>")
                .append("<tr><td class='n'>整改单号</td><td>").append(esc(s(r.get("rectify_no"))))
                .append("</td><td class='n'>整改期限</td><td>").append(esc(date10(r.get("deadline")))).append("</td></tr>")
                .append("<tr><td class='n'>整改要求</td><td colspan='3'>").append(esc(s(r.get("rectify_require")))).append("</td></tr>")
                .append("<tr><td class='n'>整改人</td><td>").append(esc(s(r.get("responsible_name"))))
                .append("</td><td class='n'>整改说明</td><td>").append(esc(s(r.get("rectify_note")))).append("</td></tr>")
                .append("</table>");
        body.append("<h2>三、验收销号（验收单）</h2><table>")
                .append("<tr><td class='n'>验收结果</td><td>")
                .append("pass".equals(s(a.get("accept_result"))) ? "通过" : ("reject".equals(s(a.get("accept_result"))) ? "退回整改" : "—"))
                .append("</td><td class='n'>验收人</td><td>").append(esc(s(a.get("acceptor_name")))).append("</td></tr>")
                .append("<tr><td class='n'>验收意见</td><td colspan='3'>").append(esc(s(a.get("accept_note")))).append("</td></tr>")
                .append("</table>");
        body.append("<p class='sign'>检查人签字：____________　整改人签字：____________　验收人签字：____________</p>");
        body.append("<div class='seal'>").append(esc(company())).append("（盖章）<br/>")
                .append(LocalDate.now()).append("</div>");
        body.append(sealService.sealHtml());
        body.append("<p class='foot'>本表由系统按隐患闭环记录实时生成 · 闭环五要素全程留痕 · ").append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("隐患排查治理闭环单", body.toString())),
                "danger_record_" + id + ".pdf");
    }

    @GetMapping(value = "/plan/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> planPdf(@PathVariable long id) {
        Map<String, Object> p = one("SELECT * FROM `danger_check_plan` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        List<Map<String, Object>> items = q("SELECT * FROM `danger_check_item` WHERE `plan_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `sort_no`,`id`", id, tid());
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 隐患排查记录表</h1>");
        body.append("<p class='tip'>检查名称：").append(esc(s(p.get("title"))))
                .append("　排查类型：").append(esc(typeName(s(p.get("check_type")))))
                .append("　检查日期：").append(esc(date10(p.get("check_date"))))
                .append("　清单范本：").append(esc(s(p.get("template_name")))).append("</p>");
        body.append("<table><tr><th>序号</th><th>项目类别</th><th>检查项目</th><th>检查内容与检查标准</th><th>检查结果</th><th>备注</th></tr>");
        int n = 0;
        for (Map<String, Object> it : items) {
            n++;
            String res = "pass".equals(s(it.get("result"))) ? "合格"
                    : ("fail".equals(s(it.get("result"))) ? "不合格" : "未判定");
            String cat = s(it.get("category"));
            if ("1".equals(s(it.get("is_major")))) cat = "<b>" + cat + "</b>";
            body.append("<tr><td>").append(n).append("</td><td>").append(cat)
                    .append("</td><td>").append(esc(s(it.get("item_name"))))
                    .append("</td><td>").append(esc(s(it.get("standard"))))
                    .append("</td><td>").append(res).append("</td><td>")
                    .append(esc(s(it.get("remark")))).append("</td></tr>");
        }
        if (items.isEmpty()) body.append("<tr><td colspan='6' style='text-align:center;color:#999'>暂无检查项</td></tr>");
        body.append("</table>");
        body.append("<p class='sign'>检查人签字：____________　负责人签字：____________　")
                .append(esc(company())).append("（盖章）</p>");
        body.append("<p class='foot'>重大事故隐患排查项为一票否决类，判定不合格自动标记重大隐患并督办整改 · ")
                .append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("隐患排查记录表", body.toString())),
                "danger_plan_" + id + ".pdf");
    }

    @GetMapping(value = "/ledger/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> ledgerPdf() {
        List<Map<String, Object>> rows = records(null, null, null);
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 隐患排查治理台账</h1>");
        body.append("<p class='tip'>共 ").append(rows.size()).append(" 条隐患记录 · ")
                .append(LocalDate.now()).append("</p>");
        body.append("<table><tr><th>序号</th><th>隐患名称</th><th>排查类型</th><th>等级</th><th>状态</th><th>发现时间</th></tr>");
        int n = 0;
        for (Map<String, Object> r : rows) {
            n++;
            body.append("<tr><td>").append(n).append("</td><td>").append(esc(s(r.get("title"))))
                    .append("</td><td>").append(esc(s(r.get("checkTypeName"))))
                    .append("</td><td>").append(esc(s(r.get("danger_level"))))
                    .append("</td><td>").append(esc(s(r.get("statusName"))))
                    .append("</td><td>").append(esc(date10(r.get("find_time")))).append("</td></tr>");
        }
        if (rows.isEmpty()) body.append("<tr><td colspan='6' style='text-align:center;color:#999'>暂无隐患记录</td></tr>");
        body.append("</table><p class='foot'>").append(esc(company())).append(" · 系统自动生成 · ")
                .append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("隐患排查治理台账", body.toString())), "danger_ledger.pdf");
    }

    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:30px;color:#222;font-size:12px}" +
                "h1{text-align:center;font-size:19px;letter-spacing:2px;margin:0 0 8px}" +
                "h2{font-size:14px;margin:16px 0 6px;border-left:3px solid #444;padding-left:8px}" +
                "table{width:100%;border-collapse:collapse;margin-top:8px}" +
                "td,th{border:1px solid #666;padding:7px;font-size:11px;text-align:left;vertical-align:top}" +
                "th{background:#f2f2f2;text-align:center}" +
                ".n{background:#f7f7f7;text-align:center;width:100px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".sign{margin-top:22px;font-size:12px}" +
                ".seal{text-align:right;margin-top:10px;font-size:12px;color:#333}" +
                "</style></head><body>" + body + "</body></html>";
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 一键归档：隐患排查记录（S01）+ 隐患排查治理台账（S02）两份 PDF 装盒八，幂等覆盖 */
    @PostMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestBody Map<String, Object> b) {
        String period = s(b.get("period"));
        if (period.isBlank()) period = LocalDate.now().toString().substring(0, 7);
        boolean danger = count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? " +
                "AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
        String box = danger ? "DANGER_BOX9" : "GENERAL_BOX8";
        Map<String, Object> parent = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long parentId = l(parent.get("id"));
        List<Map<String, Object>> subs = parentId == 0L ? List.of() :
                q("SELECT `id`,`code`,`name` FROM `archive_catalog` WHERE `parent_id`=? AND `level`=2 " +
                        "ORDER BY `id` LIMIT 2", parentId);

        // 排查记录 PDF（清单逐项）+ 治理台账 PDF
        String recTitle = period + " 隐患排查记录（排查记录表）";
        String ledTitle = period + " 隐患排查治理台账（整改通知书/验收单）";
        byte[] recPdf = pdfService.render(page("隐患排查记录",
                "<h1>" + esc(company()) + " · 隐患排查记录</h1><p class='tip'>" + period
                        + " · 共 " + count("SELECT COUNT(*) FROM `danger_check_plan` WHERE `tenant_id`=? AND `deleted`=0", tid())
                        + " 次排查</p>"));
        byte[] ledPdf = pdfService.render(page("隐患排查治理台账",
                "<h1>" + esc(company()) + " · 隐患排查治理台账</h1><p class='tip'>" + period
                        + " · 共 " + count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0", tid())
                        + " 条隐患</p>"));

        List<Map<String, Object>> out = new ArrayList<>();
        byte[][] pdfs = {recPdf, ledPdf};
        String[] titles = {recTitle, ledTitle};
        String[] codes = {"DANGER_RECORD", "DANGER_LEDGER"};
        for (int k = 0; k < 2; k++) {
            long catalogId = k < subs.size() ? l(subs.get(k).get("id")) : parentId;
            String key = tid() + "/danger/" + codes[k].toLowerCase() + "_" + period + "_" + System.currentTimeMillis() + ".pdf";
            storage.upload(key, pdfs[k], "application/pdf");
            Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? " +
                    "AND `biz_module`='danger' AND `title`=? AND `deleted`=0 LIMIT 1", tid(), titles[k]);
            long itemId;
            if (item.isEmpty()) {
                itemId = IdUtil.getSnowflakeNextId();
                exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`," +
                                "`biz_module`,`biz_id`,`title`,`occur_date`,`archive_year`,`status`," +
                                "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,2,1,'danger',?,?,?,?,1,?,?,0)",
                        itemId, tid(), catalogId, itemId, titles[k], LocalDate.now(),
                        LocalDate.now().getYear(), LocalDateTime.now(), TenantContextHolder.userId());
            } else {
                itemId = l(item.get("id"));
                exec("UPDATE `archive_item` SET `catalog_id`=? WHERE `id`=?", catalogId, itemId);
            }
            exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=?", itemId, tid());
            long pdfId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`," +
                            "`file_path`,`file_size`,`page_count`,`template_code`,`gen_time`,`created_at`," +
                            "`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,1,?,?,?,?,0)",
                    pdfId, tid(), itemId, (short) LocalDate.now().getYear(), titles[k] + ".pdf",
                    key, pdfs[k].length, codes[k], LocalDateTime.now(), LocalDateTime.now(),
                    TenantContextHolder.userId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", titles[k]); m.put("itemId", String.valueOf(itemId));
            m.put("pdfId", String.valueOf(pdfId)); m.put("objectKey", key);
            m.put("size", pdfs[k].length); m.put("catalogId", String.valueOf(catalogId));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true); res.put("box", box); res.put("period", period);
        res.put("items", out);
        return Result.ok(res);
    }

    private List<Map<String, Object>> archives() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`occur_date`,`status` FROM `archive_item` " +
                "WHERE `tenant_id`=? AND `biz_module`='danger' AND `deleted`=0 ORDER BY `id` DESC LIMIT 20", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id"))); out.add(m);
        }
        return out;
    }

    // ==================================================================
    // 7. 联动数据源 / 小程序预留数据接口
    // ==================================================================
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> persons = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`name`,`person_type` FROM `person` WHERE `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `id` LIMIT 100", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id"))); persons.add(m);
        }
        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`plate_no`,`biz_type` FROM `vehicle` WHERE `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `id` LIMIT 100", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id"))); vehicles.add(m);
        }
        List<Map<String, Object>> institutions = new ArrayList<>();
        try {
            for (Map<String, Object> r : q("SELECT `item_code`,`current_version` FROM `institution_item` " +
                    "WHERE `tenant_id`=? AND `status`=4 AND `deleted`=0 ORDER BY `item_code` LIMIT 30", tid())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", s(r.get("item_code"))); m.put("version", s(r.get("current_version")));
                institutions.add(m);
            }
        } catch (Exception ignore) { }
        out.put("persons", persons);
        out.put("vehicles", vehicles);
        out.put("institutions", institutions);
        out.put("checkTypes", CHECK_TYPES.stream().map(t -> t[0]).toList());
        out.put("levels", List.of("一般", "较大", "重大"));
        return Result.ok(out);
    }

    private List<Map<String, Object>> referencesData() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] refs = {
                {"check", "B4 一日三检", "三检异常转隐患", "SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `check_type`='check_daily' AND `deleted`=0"},
                {"vehicle", "B2 车辆基础信息", "车辆隐患关联车牌", "SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `vehicle_id` IS NOT NULL AND `deleted`=0"},
                {"person", "B1 人员基础信息", "检查人/整改人/验收人", "SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `finder_id` IS NOT NULL AND `deleted`=0"},
                {"institution", "B3 制度档案引擎", "排查依据（已发布制度）", "SELECT COUNT(*) FROM `institution_item` WHERE `tenant_id`=? AND `status`=4 AND `deleted`=0"},
                {"archive", "B7 电子档案柜", "盒八隐患排查归档", "SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='danger' AND `deleted`=0"},
        };
        for (String[] r : refs) {
            long n;
            try { n = count(r[3], tid()); } catch (Exception e) { n = 0; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", r[0]); m.put("name", r[1]); m.put("usage", r[2]); m.put("count", n);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/references")
    public Result<List<Map<String, Object>>> references() { return Result.ok(referencesData()); }

    /** 小程序预留：未闭环/超期隐患预警聚合（不新增小程序页面） */
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring() {
        scanOverdue();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`danger_level`,`status`,`deadline`,`remind_count` " +
                "FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 AND `status`<>'closed' " +
                "ORDER BY FIELD(`status`,'overdue','verify','rectifying','pending'),`id` DESC LIMIT 20", tid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dangerId", String.valueOf(r.get("id")));
            m.put("title", s(r.get("title")));
            m.put("level", s(r.get("danger_level")));
            m.put("status", s(r.get("status")));
            m.put("text", "overdue".equals(s(r.get("status")))
                    ? "整改已超期，请立即处理" : "隐患未闭环，请尽快整改并反馈");
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list); out.put("total", list.size());
        return Result.ok(out);
    }

    /** 小程序预留：我的整改任务（按责任人聚合，与 PC 同源数据） */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Long responsibleId) {
        StringBuilder sql = new StringBuilder("SELECT d.`id`,d.`title`,d.`danger_level`,d.`status`,d.`deadline`," +
                "r.`responsible_id`,r.`rectify_no` FROM `danger_record` d " +
                "JOIN `danger_rectify` r ON r.`danger_id`=d.`id` AND r.`deleted`=0 " +
                "WHERE d.`tenant_id`=? AND d.`deleted`=0 AND d.`status`<>'closed'");
        List<Object> args = new ArrayList<>(); args.add(tid());
        if (responsibleId != null) { sql.append(" AND r.`responsible_id`=?"); args.add(responsibleId); }
        sql.append(" ORDER BY d.`id` DESC LIMIT 30");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("dangerId", String.valueOf(r.get("id")));
            m.put("statusName", statusName(s(r.get("status"))));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", LocalDate.now().toString());
        out.put("tasks", list); out.put("total", list.size());
        out.put("note", "小程序端仅预留数据接口，具体页面后续统一建设");
        return Result.ok(out);
    }
}
