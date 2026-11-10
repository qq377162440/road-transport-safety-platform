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
 * B4 一日三检 · 完整流程闭环（详细设计 11）。
 *
 * <p>与 {@link BizFlowController} 的早期三检接口分工：BizFlowController 保留
 * 「/api/flow/check/task」「/api/flow/check/record」「/api/flow/check/monthly-report」
 * 作为小程序/演练的历史入口（幂等采集 + 异常转隐患）；本控制器负责 PC 端
 * 出车周期状态机、三检串行、配置开关、检查项字典、提醒子表、PDF 成品与装盒归档。
 *
 * <p>设计决策 C1~C16：出车才生成任务（C12）、跨日出车周期不中断（C10）、
 * 三检串行（C11）、车头挂分离（C5）、换头换挂只改当天驾驶员（C9）、
 * 车牌模糊搜索（C13）、异常转隐患（C6）、超管纠错（C8）、
 * 月报汇总表装盒而明细现查现打（C14）、摄像头/声明开关（C15/C16）。
 *
 * <p>小程序边界（用户 2026-11-10 确认）：仅 /expiring 与 /mine 两个聚合 GET 预留数据接口，
 * 不新增、不改动小程序端页面与功能。
 */
@RestController
@RequestMapping("/api/flow/dailycheck")
public class CheckFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public CheckFlowController(JdbcTemplate jdbc, PdfService pdfService,
                               StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    private static final String[] PHASES = {"out", "mid", "in"};
    private static final Map<String, String> PHASE_NAME = Map.of(
            "out", "出车前", "mid", "行车中", "in", "收车后");
    private static final List<String[]> FLOW = List.of(
            new String[]{"not_out", "未出车"}, new String[]{"doing", "出车中"},
            new String[]{"done", "已完成"});

    // ==================================================================
    // 0. 通用工具
    // ==================================================================
    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return t == null ? 0L : t;
    }

    private String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private long l(Object o) {
        if (o == null) return 0L;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private int i(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private String esc(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private List<Map<String, Object>> q(String sql, Object... a) {
        return jdbc.queryForList(sql, a);
    }

    private Map<String, Object> one(String sql, Object... a) {
        List<Map<String, Object>> r = q(sql, a);
        return r.isEmpty() ? Collections.emptyMap() : r.get(0);
    }

    private long count(String sql, Object... a) {
        Long n = jdbc.queryForObject(sql, Long.class, a);
        return n == null ? 0L : n;
    }

    private void exec(String sql, Object... a) { jdbc.update(sql, a); }

    private String company() {
        try { return sealService.companyName(); } catch (Exception e) { return "本企业"; }
    }

    private String date10(Object o) { return s(o).length() >= 10 ? s(o).substring(0, 10) : ""; }

    private String operator() {
        TenantContextHolder.Context c = TenantContextHolder.get();
        return c == null ? "" : s(c.getUsername());
    }

    private String currentMonth() { return LocalDate.now().toString().substring(0, 7); }

    /** 状态归一：兼容历史种子里的 ongoing */
    private String normStatus(String raw) {
        if ("ongoing".equals(raw)) return "doing";
        if ("finish".equals(raw) || "finished".equals(raw)) return "done";
        return raw == null || raw.isBlank() ? "not_out" : raw;
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("msg", msg);
        return m;
    }

    private void log(long taskId, Object checkDay, String phase, String action,
                     String from, String to, String detail) {
        try {
            exec("INSERT INTO `check_flow_log` (`id`,`tenant_id`,`task_id`,`check_day`,`phase`," +
                            "`action`,`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), taskId, checkDay, phase, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
    }

    /** 当前出车周期任务（含车辆/驾驶员信息） */
    private Map<String, Object> taskOf(long id) {
        return one("SELECT t.*, v.`plate_no`, v.`vehicle_class`, v.`biz_type`, v.`business_status`, " +
                        "v.`is_trailer` AS v_is_trailer, d.`name` AS driver_name, e.`name` AS escort_name " +
                        "FROM `check_task` t LEFT JOIN `vehicle` v ON v.`id`=t.`vehicle_id` " +
                        "LEFT JOIN `person` d ON d.`id`=t.`driver_id` " +
                        "LEFT JOIN `person` e ON e.`id`=t.`escort_id` " +
                        "WHERE t.`id`=? AND t.`tenant_id`=? AND t.`deleted`=0", id, tid());
    }

    /** 某任务某天的三时段完成情况 */
    private Map<String, Object> dayProgress(long taskId, String day) {
        Map<String, Object> out = new LinkedHashMap<>();
        int done = 0;
        for (String p : PHASES) {
            Map<String, Object> r = one("SELECT `id`,`result`,`check_time` FROM `check_record` " +
                            "WHERE `task_id`=? AND `check_day`=? AND `phase`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1",
                    taskId, day, p, tid());
            out.put(p, r.isEmpty() ? null : r);
            if (!r.isEmpty()) done++;
        }
        out.put("done", done);
        out.put("total", 3);
        out.put("complete", done == 3);
        return out;
    }

    // ==================================================================
    // 1. 配置（C12/C15/C16）——惰性初始化，不依赖迁移种子
    // ==================================================================
    private Map<String, Object> configRow() {
        Map<String, Object> c = one("SELECT * FROM `check_config` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        if (c.isEmpty()) {
            exec("INSERT INTO `check_config` (`id`,`tenant_id`,`default_policy`,`force_camera`," +
                            "`allow_declare`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), "not_out", 0, 0,
                    LocalDateTime.now(), TenantContextHolder.userId());
            c = one("SELECT * FROM `check_config` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        }
        return c;
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> c = configRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultPolicy", s(c.get("default_policy")));
        out.put("forceCamera", i(c.get("force_camera")) == 1);
        out.put("allowDeclare", i(c.get("allow_declare")) == 1);
        return Result.ok(out);
    }

    @PutMapping("/config")
    public Result<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> b) {
        String policy = s(b.get("defaultPolicy"));
        if (!List.of("not_out", "not_check").contains(policy)) policy = "not_out";
        Map<String, Object> c = configRow();
        exec("UPDATE `check_config` SET `default_policy`=?,`force_camera`=?,`allow_declare`=?," +
                        "`updated_at`=? WHERE `id`=?",
                policy, Boolean.TRUE.equals(b.get("forceCamera")) ? 1 : 0,
                Boolean.TRUE.equals(b.get("allowDeclare")) ? 1 : 0,
                LocalDateTime.now(), l(c.get("id")));
        return config();
    }

    // ==================================================================
    // 2. 概览 + 任务清单（统计卡 / 状态机 / 筛选 / 按月）
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String trailer) {

        String m = (month == null || month.isBlank()) ? currentMonth() : month;
        List<Map<String, Object>> tasks = tasks(m, keyword, status, trailer);

        int notOut = 0, doing = 0, done = 0, abnormal = 0, reminded = 0, phasesDone = 0;
        for (Map<String, Object> t : tasks) {
            String st = s(t.get("status"));
            if ("not_out".equals(st)) notOut++;
            else if ("done".equals(st)) done++;
            else doing++;
            abnormal += i(t.get("abnormalCount"));
            reminded += i(t.get("remindCount"));
            phasesDone += i(t.get("phaseDone"));
        }
        int totalPhases = tasks.size() * 3;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", tasks.size());
        stats.put("notOut", notOut);
        stats.put("doing", doing);
        stats.put("done", done);
        stats.put("abnormal", abnormal);
        stats.put("reminded", reminded);
        stats.put("percent", totalPhases == 0 ? 0 : (int) Math.round(phasesDone * 100.0 / totalPhases));
        stats.put("vehicleCount", count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("dictCount", count("SELECT COUNT(*) FROM `check_item_dict` WHERE `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?)", tid()));

        List<Map<String, Object>> flow = new ArrayList<>();
        for (String[] f : FLOW) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", f[0]); n.put("name", f[1]); flow.add(n);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("month", m);
        out.put("tasks", tasks);
        out.put("stats", stats);
        out.put("flow", flow);
        out.put("config", config().getData());
        out.put("expiring", expiringList());
        out.put("archives", archives(m));
        return Result.ok(out);
    }

    private List<Map<String, Object>> tasks(String month, String keyword, String status, String trailer) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.*, v.`plate_no`, v.`vehicle_class`, v.`biz_type`, d.`name` AS driver_name, " +
                        "e.`name` AS escort_name FROM `check_task` t " +
                        "LEFT JOIN `vehicle` v ON v.`id`=t.`vehicle_id` " +
                        "LEFT JOIN `person` d ON d.`id`=t.`driver_id` " +
                        "LEFT JOIN `person` e ON e.`id`=t.`escort_id` " +
                        "WHERE t.`tenant_id`=? AND t.`deleted`=0");
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (month != null && !month.isBlank()) {
            sql.append(" AND (DATE_FORMAT(t.`start_date`,'%Y-%m')=? OR (t.`end_date` IS NOT NULL AND DATE_FORMAT(t.`end_date`,'%Y-%m')=?))");
            args.add(month); args.add(month);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (v.`plate_no` LIKE ? OR d.`name` LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        if ("0".equals(trailer) || "1".equals(trailer)) {
            sql.append(" AND t.`is_trailer`=?"); args.add(Integer.parseInt(trailer));
        }
        sql.append(" ORDER BY t.`start_date` DESC, t.`id` DESC LIMIT 300");

        List<Map<String, Object>> rows = q(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long id = l(r.get("id"));
            String st = normStatus(s(r.get("status")));
            // 三时段按「周期首日」展示；跨日任务另给 days 明细
            String firstDay = date10(r.get("start_date"));
            if (firstDay.isBlank()) firstDay = LocalDate.now().toString();
            Map<String, Object> pg = dayProgress(id, firstDay);

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", String.valueOf(id));
            t.put("vehicleId", String.valueOf(l(r.get("vehicle_id"))));
            t.put("plateNo", s(r.get("plate_no")));
            t.put("vehicleClass", s(r.get("vehicle_class")));
            t.put("bizType", s(r.get("biz_type")));
            t.put("driverId", String.valueOf(l(r.get("driver_id"))));
            t.put("driverName", s(r.get("driver_name")));
            t.put("escortName", s(r.get("escort_name")));
            t.put("isTrailer", i(r.get("is_trailer")));
            t.put("startDate", date10(r.get("start_date")));
            t.put("endDate", date10(r.get("end_date")));
            t.put("status", st);
            t.put("statusText", statusText(st));
            t.put("remindCount", i(r.get("remind_count")));
            t.put("mileageStart", i(r.get("mileage_start")));
            t.put("mileageEnd", i(r.get("mileage_end")));
            t.put("mileage", i(r.get("mileage")));
            t.put("cargoName", s(r.get("cargo_name")));
            t.put("origin", s(r.get("origin")));
            t.put("destination", s(r.get("destination")));
            t.put("phases", phaseCells(pg));
            t.put("phaseDone", pg.get("done"));
            t.put("abnormalCount", (int) count("SELECT COUNT(*) FROM `check_record` WHERE `task_id`=? AND `result`='abnormal' AND `tenant_id`=? AND `deleted`=0", id, tid()));
            t.put("dayCount", (int) count("SELECT COUNT(DISTINCT `check_day`) FROM `check_record` WHERE `task_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid()));
            t.put("notOutWay", s(r.get("not_out_way")));
            out.add(t);
        }
        if (status != null && !status.isBlank()) {
            out.removeIf(t -> !status.equals(t.get("status")));
        }
        return out;
    }

    private List<Map<String, Object>> phaseCells(Map<String, Object> pg) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (String p : PHASES) {
            Map<String, Object> c = new LinkedHashMap<>();
            Object raw = pg.get(p);
            c.put("phase", p);
            c.put("name", PHASE_NAME.get(p));
            if (raw instanceof Map<?, ?> rm) {
                c.put("done", true);
                c.put("result", s(rm.get("result")));
                c.put("time", s(rm.get("check_time")));
            } else {
                c.put("done", false);
                c.put("result", "");
                c.put("time", "");
            }
            cells.add(c);
        }
        return cells;
    }

    private String statusText(String st) {
        return switch (st) {
            case "not_out" -> "未出车";
            case "done" -> "已完成";
            default -> "出车中";
        };
    }

    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> taskList(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String trailer) {
        return Result.ok(tasks(month, keyword, status, trailer));
    }

    // ==================================================================
    // 3. 任务详情（多 Tab：行车日志 / 三时段 / 检查项明细 / 提醒 / 流转日志）
    // ==================================================================
    @GetMapping("/task/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> raw = taskOf(id);
        if (raw.isEmpty()) return Result.fail("任务不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", tasks(null, null, null, null).stream()
                .filter(t -> String.valueOf(id).equals(t.get("id"))).findFirst().orElse(raw));

        // 行车日志字段（设计 11 §3.5 逐字段对齐线下台账）
        Map<String, Object> log = new LinkedHashMap<>();
        for (String k : List.of("mileage_start", "mileage_end", "mileage", "cargo_name", "cargo_qty",
                "origin", "stopover", "destination", "start_date", "end_date")) log.put(k, s(raw.get(k)));
        out.put("logistics", log);

        // 按天分组的三时段结果（C10 跨日）
        List<Map<String, Object>> days = new ArrayList<>();
        for (Map<String, Object> d : q("SELECT DISTINCT `check_day` FROM `check_record` " +
                        "WHERE `task_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `check_day`", id, tid())) {
            String day = date10(d.get("check_day"));
            Map<String, Object> pg = dayProgress(id, day);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("phases", phaseCells(pg));
            row.put("done", pg.get("done"));
            row.put("complete", pg.get("complete"));
            days.add(row);
        }
        if (days.isEmpty()) {
            String day = date10(raw.get("start_date"));
            if (day.isBlank()) day = LocalDate.now().toString();
            Map<String, Object> pg = dayProgress(id, day);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day); row.put("phases", phaseCells(pg));
            row.put("done", pg.get("done")); row.put("complete", pg.get("complete"));
            days.add(row);
        }
        out.put("days", days);
        out.put("nextPhase", nextPhase(id, days.get(days.size() - 1).get("day").toString()));

        // 检查记录 + 逐项明细
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `check_record` WHERE `task_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `check_day`, FIELD(`phase`,'out','mid','in')", id, tid())) {
            long rid = l(r.get("id"));
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", String.valueOf(rid));
            rec.put("phase", s(r.get("phase")));
            rec.put("phaseName", PHASE_NAME.getOrDefault(s(r.get("phase")), s(r.get("phase"))));
            rec.put("checkDay", date10(r.get("check_day")));
            rec.put("result", s(r.get("result")));
            rec.put("checkTime", s(r.get("check_time")));
            rec.put("abnormalNote", s(r.get("abnormal_note")));
            rec.put("abnormalVoice", s(r.get("abnormal_voice")));
            rec.put("abnormalPhotos", s(r.get("abnormal_photos")));
            rec.put("items", q("SELECT i.`id`, i.`checked`, i.`abnormal`, d.`item_name`, d.`item_code` " +
                            "FROM `check_item_result` i LEFT JOIN `check_item_dict` d ON d.`id`=i.`item_id` " +
                            "WHERE i.`record_id`=? AND i.`tenant_id`=? AND i.`deleted`=0 ORDER BY i.`id`",
                    rid, tid()));
            records.add(rec);
        }
        out.put("records", records);
        out.put("reminds", q("SELECT `id`,`remind_date`,`remind_way`,`remind_content` FROM `check_remind` " +
                "WHERE `task_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `remind_date` DESC", id, tid()));
        out.put("logs", q("SELECT `id`,`check_day`,`phase`,`action`,`from_status`,`to_status`,`detail`," +
                "`operator`,`created_at` FROM `check_flow_log` WHERE `task_id`=? AND `tenant_id`=? " +
                "AND `deleted`=0 ORDER BY `id` DESC LIMIT 100", id, tid()));
        out.put("dangers", q("SELECT `id`,`title`,`status`,`danger_level`,`find_time` FROM `danger_record` " +
                "WHERE `check_type`='check_daily' AND `ref_id` IN (SELECT `id` FROM `check_record` WHERE `task_id`=?) " +
                "AND `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC", id, tid()));
        out.put("items", itemsFor(s(raw.get("vehicle_class")), s(raw.get("biz_type")), null));
        out.put("vehicles", vehicleOptions(""));
        out.put("drivers", driverOptions(""));
        return Result.ok(out);
    }

    /** 串行推下一时段：当天 out→mid→in；当天集齐则下一天 out */
    private String nextPhase(long taskId, String day) {
        Map<String, Object> pg = dayProgress(taskId, day);
        for (String p : PHASES) if (pg.get(p) == null) return p;
        return "out";
    }

    // ==================================================================
    // 4. 任务生成 / 状态机（C5/C9/C10/C11/C12/C16）
    // ==================================================================
    @PostMapping("/task/start")
    public Result<Map<String, Object>> start(@RequestBody Map<String, Object> b) {
        long vehicleId = l(b.get("vehicleId"));
        if (vehicleId == 0L) return Result.ok(fail("请选择车辆"));
        Map<String, Object> v = one("SELECT * FROM `vehicle` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                vehicleId, tid());
        if (v.isEmpty()) return Result.ok(fail("车辆不存在"));
        if ("stop".equals(s(v.get("business_status"))) || "停运".equals(s(v.get("business_status"))))
            return Result.ok(fail("停运车辆不生成三检任务"));

        String day = s(b.get("startDate"));
        if (day.isBlank()) day = LocalDate.now().toString();
        int trailer = b.containsKey("isTrailer") ? i(b.get("isTrailer")) : i(v.get("is_trailer"));

        // 幂等：同车同日同车头挂只允许一个出车周期（uk_task）
        Map<String, Object> exist = one("SELECT `id`,`status` FROM `check_task` WHERE `tenant_id`=? " +
                        "AND `vehicle_id`=? AND `is_trailer`=? AND `start_date`=? AND `deleted`=0 LIMIT 1",
                tid(), vehicleId, trailer, day);
        if (!exist.isEmpty()) {
            long id = l(exist.get("id"));
            String st = normStatus(s(exist.get("status")));
            if ("not_out".equals(st)) {
                exec("UPDATE `check_task` SET `status`='doing',`not_out_by`=NULL,`not_out_at`=NULL," +
                        "`not_out_way`=NULL,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
                log(id, day, null, "重新出车", "not_out", "doing", "撤销未出车标记，恢复出车");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("taskId", String.valueOf(id));
            out.put("idempotent", true); out.put("status", "doing");
            return Result.ok(out);
        }

        // 人车绑定预填（C4/C9）：只取当前活跃绑定，不写回静态绑定
        Map<String, Object> bind = one("SELECT `driver_id`,`escort_id` FROM `vehicle_driver` " +
                        "WHERE `vehicle_id`=? AND `tenant_id`=? AND `unbind_date` IS NULL AND `deleted`=0 " +
                        "ORDER BY `bind_date` DESC LIMIT 1", vehicleId, tid());
        long driver = l(b.get("driverId"));
        if (driver == 0L) driver = l(bind.get("driver_id"));
        long escort = l(b.get("escortId"));
        if (escort == 0L) escort = l(bind.get("escort_id"));

        long id = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `check_task` (`id`,`tenant_id`,`vehicle_id`,`driver_id`,`escort_id`,`is_trailer`," +
                        "`start_date`,`status`,`remind_count`,`mileage_start`,`mileage_end`,`cargo_name`," +
                        "`cargo_qty`,`origin`,`stopover`,`destination`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,0)",
                id, tid(), vehicleId, driver == 0L ? null : driver, escort == 0L ? null : escort,
                trailer, day, "doing",
                b.get("mileageStart"), b.get("mileageEnd"), s(b.get("cargoName")), s(b.get("cargoQty")),
                s(b.get("origin")), s(b.get("stopover")), s(b.get("destination")),
                LocalDateTime.now(), TenantContextHolder.userId());
        log(id, day, null, "标记出车", "", "doing",
                "车辆 " + s(v.get("plate_no")) + " 出车" + (driver == 0L ? "（无绑定驾驶员，待补录）" : ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("taskId", String.valueOf(id));
        out.put("idempotent", false);
        out.put("status", "doing");
        out.put("boundDriver", driver);
        return Result.ok(out);
    }

    @PostMapping("/task/{id}/not-out")
    public Result<Map<String, Object>> markNotOut(@PathVariable long id,
                                                   @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        boolean declare = b != null && Boolean.TRUE.equals(b.get("declare"));
        if (declare && i(configRow().get("allow_declare")) != 1)
            return Result.ok(fail("企业未开启「司机声明未出车」开关"));
        if (declare && count("SELECT COUNT(*) FROM `check_record` WHERE `task_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid()) > 0)
            return Result.ok(fail("当天已有三检记录，声明不生效（以实际三检为准）"));

        String way = declare ? "declare" : "pc";
        String from = normStatus(s(t.get("status")));
        exec("UPDATE `check_task` SET `status`='not_out',`not_out_by`=?,`not_out_at`=?," +
                        "`not_out_way`=?,`updated_at`=? WHERE `id`=?",
                TenantContextHolder.userId(), LocalDateTime.now(), way, LocalDateTime.now(), id);
        log(id, date10(t.get("start_date")), null, declare ? "司机声明未出车" : "PC标记未出车",
                from, "not_out", "当日豁免三检（" + (declare ? "司机声明" : "PC 标记") + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", "not_out"); out.put("way", way);
        return Result.ok(out);
    }

    @PutMapping("/task/{id}/driver")
    public Result<Map<String, Object>> switchDriver(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        long driver = l(b.get("driverId"));
        if (driver == 0L) return Result.ok(fail("请选择驾驶员"));
        String old = s(t.get("driver_name"));
        exec("UPDATE `check_task` SET `driver_id`=?,`escort_id`=?,`updated_at`=? WHERE `id`=?",
                driver, l(b.get("escortId")) == 0L ? null : l(b.get("escortId")), LocalDateTime.now(), id);
        Map<String, Object> p = one("SELECT `name` FROM `person` WHERE `id`=?", driver);
        log(id, date10(t.get("start_date")), null, "切换当天驾驶员", "", "",
                "由 " + (old.isBlank() ? "未指定" : old) + " 改为 " + s(p.get("name")) + "（不改长期人车绑定）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("driverName", s(p.get("name")));
        return Result.ok(out);
    }

    @PutMapping("/task/{id}/logistics")
    public Result<Map<String, Object>> saveLogistics(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        int ms = i(b.get("mileageStart")), me = i(b.get("mileageEnd"));
        if (ms > 0 && me > 0 && me < ms) return Result.ok(fail("收车公里数不能小于出车公里数"));
        exec("UPDATE `check_task` SET `mileage_start`=?,`mileage_end`=?,`mileage`=?,`cargo_name`=?," +
                        "`cargo_qty`=?,`origin`=?,`stopover`=?,`destination`=?,`updated_at`=? WHERE `id`=?",
                ms == 0 ? null : ms, me == 0 ? null : me, (ms > 0 && me > 0) ? me - ms : null,
                s(b.get("cargoName")), s(b.get("cargoQty")), s(b.get("origin")),
                s(b.get("stopover")), s(b.get("destination")), LocalDateTime.now(), id);
        log(id, date10(t.get("start_date")), null, "补录行车日志", "", "",
                "里程 " + ms + "→" + me + "，货物 " + s(b.get("cargoName")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("mileage", (ms > 0 && me > 0) ? me - ms : 0);
        return Result.ok(out);
    }

    // ==================================================================
    // 5. 三检逐条提交（C6/C10/C11 串行 + 子表 + 异常转隐患）
    // ==================================================================
    @PostMapping("/task/{id}/phase")
    public Result<Map<String, Object>> submitPhase(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        String phase = s(b.get("phase"));
        if (!PHASE_NAME.containsKey(phase)) return Result.ok(fail("时段应为 out/mid/in"));
        String result = "abnormal".equals(s(b.get("result"))) ? "abnormal" : "pass";
        String day = s(b.get("checkDay"));
        if (day.isBlank()) day = LocalDate.now().toString();
        String status = normStatus(s(t.get("status")));

        // 未出车任务一旦实际做了三检，声明自动作废（C16 防滥用）
        if ("not_out".equals(status)) {
            exec("UPDATE `check_task` SET `status`='doing',`not_out_by`=NULL,`not_out_at`=NULL," +
                    "`not_out_way`=NULL,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
            log(id, day, null, "声明自动作废", "not_out", "doing", "司机实际执行了三检，未出车声明作废");
            status = "doing";
        }
        if ("done".equals(status) && !Boolean.TRUE.equals(b.get("force")))
            return Result.ok(fail("该出车周期已完成，请先重新出车或由超管纠错"));

        // C11 串行：同日必须 out→mid→in；跨新的一天要求上一天三时段集齐
        Map<String, Object> pg = dayProgress(id, day);
        int idx = List.of(PHASES).indexOf(phase);
        for (int k = 0; k < idx; k++) {
            if (pg.get(PHASES[k]) == null)
                return Result.ok(fail("请先完成「" + PHASE_NAME.get(PHASES[k]) + "」检查（三检串行）"));
        }
        String maxDay = s(one("SELECT MAX(`check_day`) AS d FROM `check_record` WHERE `task_id`=? " +
                "AND `tenant_id`=? AND `deleted`=0", id, tid()).get("d"));
        if (!maxDay.isBlank() && day.compareTo(date10(maxDay)) > 0) {
            Map<String, Object> last = dayProgress(id, date10(maxDay));
            if (!Boolean.TRUE.equals(last.get("complete")))
                return Result.ok(fail("请先结束上一次检查（" + date10(maxDay) + " 三时段未集齐）"));
        }

        // 幂等：同任务同日同时段只保留一条
        Map<String, Object> exist = one("SELECT `id` FROM `check_record` WHERE `task_id`=? AND `phase`=? " +
                "AND `check_day`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", id, phase, day, tid());
        if (!exist.isEmpty()) {
            long rid = l(exist.get("id"));
            saveItems(rid, b.get("items"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("recordId", String.valueOf(rid));
            out.put("idempotent", true); out.put("phase", phase); out.put("result", result);
            out.put("progress", dayProgress(id, day)); out.put("status", status);
            return Result.ok(out);
        }

        long rid = IdUtil.getSnowflakeNextId();
        String note = s(b.get("abnormalNote"));
        String voice = s(b.get("abnormalVoice"));
        String photos = s(b.get("abnormalPhotos"));
        exec("INSERT INTO `check_record` (`id`,`tenant_id`,`task_id`,`phase`,`check_day`,`result`," +
                        "`check_time`,`abnormal_note`,`abnormal_voice`,`abnormal_photos`," +
                        "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)",
                rid, tid(), id, phase, day, result, LocalDateTime.now(), note, voice, photos,
                LocalDateTime.now(), TenantContextHolder.userId());
        saveItems(rid, b.get("items"));

        // C6 异常 → B5 隐患排查（语音 + 文字 + 照片 + 车辆 + 发现人一并流转）
        Long dangerId = null;
        if ("abnormal".equals(result)) {
            long did = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `danger_record` (`id`,`tenant_id`,`check_type`,`ref_id`,`title`," +
                            "`danger_level`,`vehicle_id`,`finder_id`,`find_time`,`photos`,`voice_url`," +
                            "`status`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    did, tid(), "check_daily", rid,
                    "三检异常：" + (note.isBlank() ? "未填写说明" : note), "一般",
                    l(t.get("vehicle_id")), l(t.get("driver_id")) == 0L ? null : l(t.get("driver_id")),
                    LocalDateTime.now(), photos, voice, "pending", LocalDateTime.now(),
                    TenantContextHolder.userId());
            dangerId = did;
        }

        // 收车后（或显式 finish）结束出车周期
        boolean finish = "in".equals(phase) && (Boolean.TRUE.equals(b.get("finish")) || "done".equals(s(b.get("nextStatus"))));
        if (finish) {
            int ms = i(t.get("mileage_start")), me = i(t.get("mileage_end"));
            exec("UPDATE `check_task` SET `status`='done',`end_date`=?,`mileage`=?,`updated_at`=? WHERE `id`=?",
                    day, (ms > 0 && me > 0) ? me - ms : null, LocalDateTime.now(), id);
            status = "done";
        } else if (!"doing".equals(status)) {
            exec("UPDATE `check_task` SET `status`='doing',`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
            status = "doing";
        }
        log(id, day, phase, "提交" + PHASE_NAME.get(phase) + "检查", "", status,
                (result.equals("pass") ? "全部正常" : "异常：" + (note.isBlank() ? "未填写说明" : note))
                        + (dangerId != null ? "，已生成隐患单 " + dangerId : ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("recordId", String.valueOf(rid));
        out.put("phase", phase);
        out.put("result", result);
        out.put("dangerId", dangerId == null ? null : String.valueOf(dangerId));
        out.put("progress", dayProgress(id, day));
        out.put("status", status);
        out.put("nextPhase", nextPhase(id, day));
        return Result.ok(out);
    }

    private void saveItems(long recordId, Object raw) {
        if (!(raw instanceof List<?> list)) return;
        exec("UPDATE `check_item_result` SET `deleted`=1 WHERE `record_id`=? AND `tenant_id`=?", recordId, tid());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            long itemId = l(m.get("itemId"));
            if (itemId == 0L) itemId = l(m.get("id"));
            if (itemId == 0L) continue;
            int abnormal = "abnormal".equals(s(m.get("result"))) || i(m.get("abnormal")) == 1 ? 1 : 0;
            int checked = m.containsKey("checked") ? i(m.get("checked")) : (abnormal == 1 ? 0 : 1);
            exec("INSERT INTO `check_item_result` (`id`,`tenant_id`,`record_id`,`item_id`,`checked`," +
                            "`abnormal`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), recordId, itemId, checked, abnormal,
                    LocalDateTime.now(), TenantContextHolder.userId());
        }
    }

    @PostMapping("/task/{id}/finish")
    public Result<Map<String, Object>> finish(@PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        String end = b == null ? "" : s(b.get("endDate"));
        if (end.isBlank()) end = LocalDate.now().toString();
        String from = normStatus(s(t.get("status")));
        exec("UPDATE `check_task` SET `status`='done',`end_date`=?,`updated_at`=? WHERE `id`=?",
                end, LocalDateTime.now(), id);
        log(id, date10(t.get("start_date")), null, "收车结束出车周期", from, "done", "收车日期 " + end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", "done"); out.put("endDate", end);
        return Result.ok(out);
    }

    // ==================================================================
    // 6. 检查项字典（C1 车型拼装 / C7 自定义 + 模板上传）
    // ==================================================================
    private List<Map<String, Object>> itemsFor(String vehicleClass, String bizType, String phase) {
        String vc = vehicleClass == null || vehicleClass.isBlank() ? "heavy" : vehicleClass;
        String sql = "SELECT `id`,`item_code`,`item_name`,`phase`,`vehicle_class`,`biz_type`," +
                "`sort_no`,`is_default`,`tenant_id` FROM `check_item_dict` WHERE `deleted`=0 " +
                "AND (`tenant_id`=0 OR `tenant_id`=?) AND (`is_default`=1 OR `vehicle_class`=? " +
                "OR `vehicle_class`='ALL' OR `vehicle_class`='')";
        List<Object> args = new ArrayList<>();
        args.add(tid()); args.add(vc);
        if (bizType != null && !bizType.isBlank()) {
            sql += " AND (`biz_type` IS NULL OR `biz_type`='' OR `biz_type`=?)";
            args.add(bizType);
        }
        if (phase != null && !phase.isBlank()) { sql += " AND `phase`=?"; args.add(phase); }
        // tenant_id 升序：平台预置(0)先入，企业自定义后入并覆盖同编码预置项（C7 优先级）
        sql += " ORDER BY FIELD(`phase`,'out','mid','in'), `tenant_id`, `sort_no`, `id`";

        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> r : q(sql, args.toArray())) {
            String code = s(r.get("item_code"));
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", String.valueOf(l(r.get("id"))));
            it.put("itemCode", code);
            it.put("itemName", s(r.get("item_name")));
            it.put("phase", s(r.get("phase")));
            it.put("phaseName", PHASE_NAME.getOrDefault(s(r.get("phase")), s(r.get("phase"))));
            it.put("vehicleClass", s(r.get("vehicle_class")));
            it.put("sortNo", i(r.get("sort_no")));
            it.put("isDefault", i(r.get("is_default")) == 1);
            it.put("custom", i(r.get("tenant_id")) != 0);
            // 企业自定义项覆盖同编码平台预置项（C7 优先级）
            byCode.put(code + "|" + s(r.get("phase")), it);
        }
        return new ArrayList<>(byCode.values());
    }

    @GetMapping("/items")
    public Result<Map<String, Object>> items(
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String vehicleClass,
            @RequestParam(required = false) String bizType) {
        List<Map<String, Object>> list = itemsFor(vehicleClass, bizType, phase);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String p : PHASES) groups.put(p, new ArrayList<>());
        for (Map<String, Object> it : list) groups.getOrDefault(s(it.get("phase")), new ArrayList<>()).add(it);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", list);
        out.put("groups", groups);
        out.put("total", list.size());
        out.put("vehicleClass", vehicleClass == null || vehicleClass.isBlank() ? "heavy" : vehicleClass);
        out.put("phases", List.of(Map.of("code", "out", "name", "出车前"),
                Map.of("code", "mid", "name", "行车中"), Map.of("code", "in", "name", "收车后")));
        return Result.ok(out);
    }

    @PutMapping("/items")
    public Result<Map<String, Object>> saveItems(@RequestBody Map<String, Object> b) {
        Object raw = b.get("items");
        if (!(raw instanceof List<?> list)) return Result.ok(fail("请提交 items 数组"));
        int added = 0, removed = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            long id = l(m.get("id"));
            if (Boolean.TRUE.equals(m.get("deleted"))) {
                if (id != 0L) {
                    int n = jdbc.update("UPDATE `check_item_dict` SET `deleted`=1,`updated_at`=? " +
                            "WHERE `id`=? AND `tenant_id`=? AND `tenant_id`<>0", LocalDateTime.now(), id, tid());
                    removed += n;
                }
                continue;
            }
            String code = s(m.get("itemCode")), name = s(m.get("itemName"));
            String phase = s(m.get("phase")), vc = s(m.get("vehicleClass"));
            if (code.isBlank() || name.isBlank() || !PHASE_NAME.containsKey(phase)) continue;
            if (id != 0L) {
                jdbc.update("UPDATE `check_item_dict` SET `item_name`=?,`item_code`=?,`phase`=?," +
                                "`vehicle_class`=?,`sort_no`=?,`updated_at`=? " +
                                "WHERE `id`=? AND `tenant_id`=? AND `tenant_id`<>0",
                        name, code, phase, vc.isBlank() ? "ALL" : vc, i(m.get("sortNo")),
                        LocalDateTime.now(), id, tid());
            } else {
                exec("INSERT INTO `check_item_dict` (`id`,`tenant_id`,`item_code`,`item_name`,`phase`," +
                                "`vehicle_class`,`biz_type`,`sort_no`,`is_default`,`created_at`,`updated_at`,`deleted`) " +
                                "VALUES (?,?,?,?,?,?,?,?,0,?,?,0)",
                        IdUtil.getSnowflakeNextId(), tid(), code, name, phase,
                        vc.isBlank() ? "ALL" : vc, s(m.get("bizType")), i(m.get("sortNo")),
                        LocalDateTime.now(), LocalDateTime.now());
                added++;
            }
        }
        Map<String, Object> out = items(s(b.get("phase")), s(b.get("vehicleClass")), s(b.get("bizType"))).getData();
        out.put("added", added);
        out.put("removed", removed);
        out.put("ok", true);
        return Result.ok(out);
    }

    // ==================================================================
    // 7. 提醒子表（预警引擎 02 接入）
    // ==================================================================
    @PostMapping("/task/{id}/remind")
    public Result<Map<String, Object>> remind(@PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> t = taskOf(id);
        if (t.isEmpty()) return Result.ok(fail("任务不存在"));
        String way = b == null ? "" : s(b.get("way"));
        if (way.isBlank()) way = "小程序推送";
        String content = b == null ? "" : s(b.get("content"));
        if (content.isBlank()) content = "今天三检还没做，抓紧咯";
        long rid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `check_remind` (`id`,`tenant_id`,`task_id`,`remind_date`,`remind_way`," +
                        "`remind_content`,`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,?,0)",
                rid, tid(), id, LocalDateTime.now(), way, content, LocalDateTime.now(),
                TenantContextHolder.userId());
        exec("UPDATE `check_task` SET `remind_count`=`remind_count`+1,`updated_at`=? WHERE `id`=?",
                LocalDateTime.now(), id);
        log(id, date10(t.get("start_date")), null, "一键提醒", "", "", way + "：" + content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("remindId", String.valueOf(rid));
        out.put("remindCount", i(t.get("remind_count")) + 1);
        return Result.ok(out);
    }

    @GetMapping("/reminds")
    public Result<List<Map<String, Object>>> reminds(@RequestParam(required = false) Long taskId) {
        String sql = "SELECT r.*, t.`start_date`, v.`plate_no` FROM `check_remind` r " +
                "LEFT JOIN `check_task` t ON t.`id`=r.`task_id` " +
                "LEFT JOIN `vehicle` v ON v.`id`=t.`vehicle_id` " +
                "WHERE r.`tenant_id`=? AND r.`deleted`=0";
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (taskId != null) { sql += " AND r.`task_id`=?"; args.add(taskId); }
        sql += " ORDER BY r.`remind_date` DESC LIMIT 200";
        return Result.ok(q(sql, args.toArray()));
    }

    // ==================================================================
    // 8. 超管纠错（C8）
    // ==================================================================
    @PutMapping("/record/{id}")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> correct(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> r = one("SELECT * FROM `check_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (r.isEmpty()) return Result.ok(fail("检查记录不存在"));
        String result = "abnormal".equals(s(b.get("result"))) ? "abnormal" : "pass";
        String checkTime = s(b.get("checkTime"));
        if (checkTime.isBlank()) checkTime = s(r.get("check_time"));
        exec("UPDATE `check_record` SET `result`=?,`check_time`=?,`abnormal_note`=?,`updated_at`=? WHERE `id`=?",
                result, checkTime, s(b.get("abnormalNote")), LocalDateTime.now(), id);
        if (b.containsKey("items")) saveItems(id, b.get("items"));
        log(l(r.get("task_id")), date10(r.get("check_day")), s(r.get("phase")), "超管纠错", "", "",
                "结果改为 " + result + "，原结果 " + s(r.get("result")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("result", result);
        return Result.ok(out);
    }

    // ==================================================================
    // 9. 车辆 / 人员选项（C13 模糊搜索、C9 换头换挂）
    // ==================================================================
    private List<Map<String, Object>> vehicleOptions(String kw) {
        String like = "%" + (kw == null ? "" : kw.trim()) + "%";
        List<Map<String, Object>> rows = q("SELECT `id`,`plate_no`,`vehicle_class`,`biz_type`," +
                "`business_status`,`is_trailer` FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND (`plate_no` LIKE ? OR `vehicle_no` LIKE ? OR `vin` LIKE ?) " +
                "ORDER BY `plate_no` LIMIT 50", tid(), like, like, like);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(l(r.get("id"))));
            m.put("plateNo", s(r.get("plate_no")));
            m.put("label", s(r.get("plate_no")) + (i(r.get("is_trailer")) == 1 ? "（挂车）" : ""));
            m.put("vehicleClass", s(r.get("vehicle_class")));
            m.put("bizType", s(r.get("biz_type")));
            m.put("businessStatus", s(r.get("business_status")));
            m.put("isTrailer", i(r.get("is_trailer")));
            // 当前活跃绑定驾驶员，供前端预填
            Map<String, Object> bind = one("SELECT vd.`driver_id`, p.`name` FROM `vehicle_driver` vd " +
                            "LEFT JOIN `person` p ON p.`id`=vd.`driver_id` WHERE vd.`vehicle_id`=? " +
                            "AND vd.`tenant_id`=? AND vd.`unbind_date` IS NULL AND vd.`deleted`=0 " +
                            "ORDER BY vd.`bind_date` DESC LIMIT 1", l(r.get("id")), tid());
            m.put("driverId", String.valueOf(l(bind.get("driver_id"))));
            m.put("driverName", s(bind.get("name")));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/vehicles/search")
    public Result<List<Map<String, Object>>> vehicleSearch(@RequestParam(required = false) String kw) {
        return Result.ok(vehicleOptions(kw));
    }

    private List<Map<String, Object>> driverOptions(String kw) {
        String like = "%" + (kw == null ? "" : kw.trim()) + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`name`,`person_type` FROM `person` " +
                "WHERE `tenant_id`=? AND `deleted`=0 AND `name` LIKE ? ORDER BY `id` LIMIT 100", tid(), like)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(l(r.get("id"))));
            m.put("name", s(r.get("name")));
            m.put("personType", s(r.get("person_type")));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/drivers")
    public Result<List<Map<String, Object>>> drivers(@RequestParam(required = false) String kw) {
        return Result.ok(driverOptions(kw));
    }

    // ==================================================================
    // 10. 月报汇总表 + 行车日志 PDF（C14：月报装盒、明细现查现打）
    // ==================================================================
    private List<Map<String, Object>> monthlyRows(int year, int month) {
        String period = String.format("%d-%02d", year, month);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : q("SELECT t.`id`, t.`vehicle_id`, t.`status`, t.`start_date`, " +
                        "v.`plate_no` FROM `check_task` t LEFT JOIN `vehicle` v ON v.`id`=t.`vehicle_id` " +
                        "WHERE t.`tenant_id`=? AND t.`deleted`=0 AND DATE_FORMAT(t.`start_date`,'%Y-%m')=? " +
                        "ORDER BY v.`plate_no`, t.`start_date`", tid(), period)) {
            long id = l(t.get("id"));
            int days = (int) count("SELECT COUNT(DISTINCT `check_day`) FROM `check_record` " +
                    "WHERE `task_id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
            int abnormal = (int) count("SELECT COUNT(*) FROM `check_record` WHERE `task_id`=? " +
                    "AND `result`='abnormal' AND `tenant_id`=? AND `deleted`=0", id, tid());
            int pass = (int) count("SELECT COUNT(*) FROM `check_record` WHERE `task_id`=? " +
                    "AND `result`='pass' AND `tenant_id`=? AND `deleted`=0", id, tid());
            int notOut = "not_out".equals(normStatus(s(t.get("status")))) ? 1 : 0;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("plateNo", s(t.get("plate_no")));
            r.put("startDate", date10(t.get("start_date")));
            r.put("outDays", notOut == 1 ? 0 : Math.max(1, days));
            r.put("notOutDays", notOut);
            r.put("checks", pass + abnormal);
            r.put("abnormal", abnormal);
            r.put("status", statusText(normStatus(s(t.get("status")))));
            out.add(r);
        }
        return out;
    }

    @GetMapping("/monthly")
    public Result<Map<String, Object>> monthly(@RequestParam int year, @RequestParam int month) {
        List<Map<String, Object>> rows = monthlyRows(year, month);
        int abnormal = 0, notOut = 0, checks = 0;
        for (Map<String, Object> r : rows) {
            abnormal += i(r.get("abnormal")); notOut += i(r.get("notOutDays")); checks += i(r.get("checks"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", String.format("%d-%02d", year, month));
        out.put("rows", rows);
        out.put("vehicleCount", rows.size());
        out.put("checks", checks);
        out.put("abnormal", abnormal);
        out.put("notOutDays", notOut);
        out.put("company", company());
        return Result.ok(out);
    }

    @GetMapping(value = "/monthly/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> monthlyPdf(@RequestParam int year, @RequestParam int month) {
        List<Map<String, Object>> rows = monthlyRows(year, month);
        String period = String.format("%d-%02d", year, month);
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 一日三检月报汇总表</h1>");
        body.append("<p class='tip'>统计月份：").append(period).append("　·　归档位置：电子档案柜 盒九 · 一日三检</p>");
        body.append("<table><tr><th>车牌号</th><th>出车日期</th><th>本月出车天数</th>" +
                "<th>三检完成条数</th><th>异常次数</th><th>未出车天数</th><th>状态</th></tr>");
        if (rows.isEmpty()) {
            body.append("<tr><td colspan='7' style='text-align:center;color:#999'>本月暂无三检任务记录（可打印空白表手写补充）</td></tr>");
        } else {
            for (Map<String, Object> r : rows) {
                body.append("<tr><td>").append(esc(s(r.get("plateNo")))).append("</td><td>")
                        .append(esc(s(r.get("startDate")))).append("</td><td>").append(i(r.get("outDays")))
                        .append("</td><td>").append(i(r.get("checks"))).append("</td><td>")
                        .append(i(r.get("abnormal"))).append("</td><td>").append(i(r.get("notOutDays")))
                        .append("</td><td>").append(esc(s(r.get("status")))).append("</td></tr>");
            }
        }
        body.append("</table>");
        body.append("<p class='foot'>说明：单车单日明细不预先装盒，监管现查现打（列表筛选后打印行车日志）。")
                .append("　制表：").append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page(period + " 三检月报汇总表", body.toString())),
                "check_monthly_" + period + ".pdf");
    }

    @GetMapping(value = "/daily/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> dailyPdf(@RequestParam long taskId, @RequestParam(required = false) String day) {
        Map<String, Object> t = taskOf(taskId);
        String d = (day == null || day.isBlank()) ? date10(t.get("start_date")) : day;
        if (d.isBlank()) d = LocalDate.now().toString();
        Map<String, Object> pg = dayProgress(taskId, d);
        StringBuilder body = new StringBuilder();
        body.append("<h1>企业车辆行车日志</h1>");
        body.append("<p class='tip'>车牌号：").append(esc(s(t.get("plate_no"))))
                .append("　日期：").append(esc(d)).append("　驾驶员：").append(esc(s(t.get("driver_name"))))
                .append("</p>");
        body.append("<table>")
                .append("<tr><td class='n'>出车公里数</td><td>").append(i(t.get("mileage_start")))
                .append("</td><td class='n'>收车公里数</td><td>").append(i(t.get("mileage_end"))).append("</td></tr>")
                .append("<tr><td class='n'>货物名称</td><td>").append(esc(s(t.get("cargo_name"))))
                .append("</td><td class='n'>货物数量</td><td>").append(esc(s(t.get("cargo_qty")))).append("</td></tr>")
                .append("<tr><td class='n'>始发地</td><td>").append(esc(s(t.get("origin"))))
                .append("</td><td class='n'>目的地</td><td>").append(esc(s(t.get("destination")))).append("</td></tr>")
                .append("<tr><td class='n'>中途停靠地</td><td colspan='3'>").append(esc(s(t.get("stopover")))).append("</td></tr>")
                .append("</table>");

        int abnormal = 0;
        for (String p : PHASES) {
            Object raw = pg.get(p);
            String res = "—";
            String detail = "未检查";
            if (raw instanceof Map<?, ?> rm) {
                res = "abnormal".equals(s(rm.get("result"))) ? "异常" : "合格";
                if ("异常".equals(res)) abnormal++;
                detail = s(rm.get("check_time"));
            }
            body.append("<h2>").append(PHASE_NAME.get(p)).append("检查 · ").append(res).append("</h2>");
            body.append("<table><tr><th>检查项</th><th>结果</th><th>检查时间</th></tr>");
            long rid = raw instanceof Map<?, ?> rm2 ? l(rm2.get("id")) : 0L;
            List<Map<String, Object>> items = rid == 0L ? List.of() :
                    q("SELECT i.`abnormal`, d.`item_name` FROM `check_item_result` i " +
                            "LEFT JOIN `check_item_dict` d ON d.`id`=i.`item_id` " +
                            "WHERE i.`record_id`=? AND i.`tenant_id`=? AND i.`deleted`=0 ORDER BY i.`id`", rid, tid());
            if (items.isEmpty()) {
                body.append("<tr><td>").append(res.equals("—") ? "—" : "全部正常（一键提交）")
                        .append("</td><td>").append(res).append("</td><td>").append(esc(detail)).append("</td></tr>");
            } else {
                for (Map<String, Object> it : items) {
                    body.append("<tr><td>").append(esc(s(it.get("item_name")))).append("</td><td>")
                            .append(i(it.get("abnormal")) == 1 ? "异常" : "正常").append("</td><td>")
                            .append(esc(detail)).append("</td></tr>");
                }
            }
            body.append("</table>");
        }
        body.append("<p>日检情况：").append(abnormal > 0 ? "存在 " + abnormal + " 项异常，已转隐患排查" : "正常")
                .append("</p>");
        body.append("<p class='sign'>驾驶员签字：____________　押运员签字：____________　")
                .append(esc(company())).append("（盖章）</p>");
        body.append("<p class='foot'>本表由系统按三检记录实时生成，用于监管现查现打，不预先装盒。")
                .append("　打印日期：").append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("车辆行车日志", body.toString())),
                "check_daily_" + s(t.get("plate_no")) + "_" + d + ".pdf");
    }

    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:30px;color:#222;font-size:12px}" +
                "h1{text-align:center;font-size:19px;letter-spacing:2px;margin:0 0 8px}" +
                "h2{font-size:14px;margin:16px 0 6px;border-left:3px solid #444;padding-left:8px}" +
                "table{width:100%;border-collapse:collapse;margin-top:8px}" +
                "td,th{border:1px solid #666;padding:7px;font-size:12px;text-align:left}" +
                "th{background:#f2f2f2;text-align:center}" +
                ".n{background:#f7f7f7;text-align:center;width:110px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".sign{margin-top:22px;font-size:12px}" +
                "</style></head><body>" + body + "</body></html>";
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 月报装盒：PDF 上传 MinIO + 归档 item + archive_pdf（幂等覆盖同月版本） */
    @PostMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestBody Map<String, Object> b) {
        int year = i(b.get("year")), month = i(b.get("month"));
        if (year == 0 || month == 0) {
            LocalDate now = LocalDate.now(); year = now.getYear(); month = now.getMonthValue();
        }
        String period = String.format("%d-%02d", year, month);
        String title = period + " 三检月报汇总表";
        List<Map<String, Object>> rows = monthlyRows(year, month);

        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 一日三检月报汇总表</h1>");
        body.append("<p class='tip'>统计月份：").append(period).append("　·　盒九 · 一日三检</p>");
        body.append("<table><tr><th>车牌号</th><th>出车日期</th><th>出车天数</th><th>三检条数</th>" +
                "<th>异常次数</th><th>未出车天数</th><th>状态</th></tr>");
        for (Map<String, Object> r : rows) {
            body.append("<tr><td>").append(esc(s(r.get("plateNo")))).append("</td><td>")
                    .append(esc(s(r.get("startDate")))).append("</td><td>").append(i(r.get("outDays")))
                    .append("</td><td>").append(i(r.get("checks"))).append("</td><td>")
                    .append(i(r.get("abnormal"))).append("</td><td>").append(i(r.get("notOutDays")))
                    .append("</td><td>").append(esc(s(r.get("status")))).append("</td></tr>");
        }
        if (rows.isEmpty()) body.append("<tr><td colspan='7' style='text-align:center;color:#999'>本月暂无三检任务记录</td></tr>");
        body.append("</table><p class='foot'>系统按三检记录自动生成 · ").append(LocalDate.now()).append("</p>");

        byte[] pdf = pdfService.render(page(title, body.toString()));
        String key = tid() + "/check/monthly_" + period + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");

        long itemId;
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? " +
                "AND `biz_module`='check' AND `title`=? AND `deleted`=0 LIMIT 1", tid(), title);
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            long catalogId = box9Catalog();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`," +
                            "`biz_module`,`biz_id`,`title`,`occur_date`,`archive_year`,`status`," +
                            "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,2,1,'check',?,?,?,?,1,?,?,0)",
                    itemId, tid(), catalogId, itemId, title, LocalDate.of(year, month, 1), year,
                    LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
        }
        exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=?", itemId, tid());
        long pdfId = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`," +
                        "`file_path`,`file_size`,`page_count`,`template_code`,`gen_time`,`created_at`," +
                        "`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,1,'CHECK_MONTHLY',?,?,?,0)",
                pdfId, tid(), itemId, (short) year, title + ".pdf", key, pdf.length,
                LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("title", title); out.put("objectKey", key);
        out.put("size", pdf.length); out.put("itemId", String.valueOf(itemId));
        out.put("pdfId", String.valueOf(pdfId)); out.put("rows", rows.size());
        return Result.ok(out);
    }

    private long box9Catalog() {
        // 危货企业用「盒十·一日三检」，普货用「盒九·一日三检」（设计 11 §5.4）
        boolean danger = count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? " +
                "AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
        String code = danger ? "DANGER_BOX10" : "GENERAL_BOX9";
        Map<String, Object> c = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", code);
        if (!c.isEmpty()) return l(c.get("id"));
        c = one("SELECT `id` FROM `archive_catalog` WHERE `code` LIKE ? AND `level`=1 LIMIT 1", "%BOX9");
        return c.isEmpty() ? 0L : l(c.get("id"));
    }

    private List<Map<String, Object>> archives(String month) {
        return q("SELECT `id`,`title`,`occur_date`,`status` FROM `archive_item` " +
                        "WHERE `tenant_id`=? AND `biz_module`='check' AND `deleted`=0 " +
                        "AND DATE_FORMAT(`occur_date`,'%Y-%m')=? ORDER BY `id` DESC LIMIT 20", tid(), month);
    }

    // ==================================================================
    // 11. 预警 / 联动数据源 / 小程序预留数据接口
    // ==================================================================
    private List<Map<String, Object>> expiringList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tasks(null, null, null, null)) {
            if ("done".equals(t.get("status")) || "not_out".equals(t.get("status"))) continue;
            if (i(t.get("phaseDone")) >= 3) continue;
            String day = s(t.get("startDate"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.get("id"));
            m.put("plateNo", t.get("plateNo"));
            m.put("driverName", t.get("driverName"));
            m.put("date", day);
            m.put("phaseDone", t.get("phaseDone"));
            m.put("level", "未检");
            m.put("text", "今天三检还没做，抓紧咯（已完成 " + t.get("phaseDone") + "/3）");
            out.add(m);
            if (out.size() >= 20) break;
        }
        return out;
    }

    /** 小程序预留：未检预警聚合（不新增小程序页面） */
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", expiringList());
        out.put("total", expiringList().size());
        return Result.ok(out);
    }

    /** 小程序预留：司机今日任务与进度（与 PC 同源数据，不新增小程序页面） */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Long driverId) {
        String today = LocalDate.now().toString();
        List<Map<String, Object>> all = tasks(null, null, null, null);
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> t : all) {
            if (driverId != null && !String.valueOf(driverId).equals(s(t.get("driverId")))) continue;
            if (!"doing".equals(t.get("status")) && !"done".equals(t.get("status"))) continue;
            mine.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today);
        out.put("tasks", mine);
        out.put("total", mine.size());
        out.put("pending", mine.stream().filter(t -> !"done".equals(t.get("status"))).count());
        out.put("config", config().getData());
        out.put("note", "小程序端仅预留数据接口，具体页面后续统一建设");
        return Result.ok(out);
    }

    /** 跨模块数据源：已发布制度（B3）/ 车辆（B2）/ 驾驶员（B1） */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> institutions = new ArrayList<>();
        try {
            for (Map<String, Object> r : q("SELECT `item_code`,`current_version` FROM `institution_item` " +
                    "WHERE `tenant_id`=? AND `status`=4 AND `deleted`=0 ORDER BY `item_code` LIMIT 30", tid())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", s(r.get("item_code")));
                m.put("version", s(r.get("current_version")));
                institutions.add(m);
            }
        } catch (Exception ignore) { }
        out.put("institutions", institutions);
        out.put("vehicles", vehicleOptions(""));
        out.put("drivers", driverOptions(""));
        out.put("phases", List.of("out", "mid", "in"));
        return Result.ok(out);
    }

    /** 联动引用统计：本模块被哪些模块引用 / 引用了哪些模块 */
    @GetMapping("/references")
    public Result<List<Map<String, Object>>> references() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] refs = {
                {"person", "B1 人员基础信息", "驾驶员 / 押运员", "SELECT COUNT(*) FROM `person` WHERE `tenant_id`=? AND `deleted`=0"},
                {"vehicle", "B2 车辆基础信息", "车辆与人车绑定", "SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0"},
                {"institution", "B3 制度档案引擎", "已发布制度（检查依据）", "SELECT COUNT(*) FROM `institution_item` WHERE `tenant_id`=? AND `status`=4 AND `deleted`=0"},
                {"danger", "B5 隐患排查治理", "三检异常转隐患", "SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `check_type`='check_daily' AND `deleted`=0"},
                {"archive", "B7 电子档案柜", "盒九月报汇总表", "SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='check' AND `deleted`=0"},
        };
        for (String[] r : refs) {
            long n;
            try { n = count(r[3], tid()); } catch (Exception e) { n = 0; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", r[0]); m.put("name", r[1]); m.put("usage", r[2]); m.put("count", n);
            out.add(m);
        }
        return Result.ok(out);
    }
}
