package com.safetrans.phase3;

import cn.hutool.core.util.IdUtil;
import com.safetrans.biz.ArchiveRegistrar;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * D1 动态监控台账（20 号设计）。
 *
 * <p>边界（用户确认）：车辆实时定位、超速/疲劳/偏离线路监测阈值均不归本系统管，
 * 由外部动态监控平台负责。本模块只做「台账上传留存 → 违规车辆识别统计 →
 * 违规驾驶员登记 → 靶向教育联动 → 教育落实跟踪 → 归档盒六」这条合规闭环。
 *
 * <p>monitor_violation / training_plan / training_video 为 V3/V4 既有表，此处复用，
 * 不重复建表；monitor_ledger / org_outsource 由 V8 补齐。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController extends PhaseDSupport {

    private static final String CATALOG = "BOX6";
    private static final List<String> EDU_FLOW = List.of("none", "pushed", "learning", "passed", "archived");

    public MonitorController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                             PdfService pdfService, StorageService storage) {
        super(jdbc, registrar, pdfService, storage);
    }

    // ==================================================================
    // 台账上传留存（M2）
    // ==================================================================
    @PostMapping("/ledger")
    @OperLog(module = "monitor", action = "upload_ledger")
    public Result<Map<String, Object>> uploadLedger(@RequestBody Map<String, Object> b) {
        String name = requiredStr(b, "ledger_name");
        Map<String, Object> r = base();
        r.put("ledger_name", name);
        r.put("ledger_type", str(b, "ledger_type", "工作记录"));
        r.put("source", str(b, "source", "人工录入"));
        r.put("period", str(b, "period", ""));
        r.put("file_url", str(b, "file_url", ""));
        r.put("uploader_id", uid());
        r.put("uploaded_at", LocalDateTime.now());
        r.put("violation_count", 0);
        long id = insert("monitor_ledger", r);
        registrar.register("monitor", CATALOG, id, "动态监控台账：" + name);
        return Result.ok(Map.of("id", id, "ledgerName", name, "catalog", CATALOG));
    }

    @GetMapping("/ledger/list")
    public Result<List<Map<String, Object>>> ledgerList() {
        return Result.ok(jdbc.queryForList(
                "SELECT l.*, (SELECT COUNT(*) FROM `monitor_violation` v " +
                        "WHERE v.`ledger_id`=l.`id` AND v.`deleted`=0) AS parsed_count " +
                        "FROM `monitor_ledger` l WHERE l.`tenant_id`=? AND l.`deleted`=0 ORDER BY l.`id` DESC LIMIT 200",
                tid()));
    }

    @GetMapping("/ledger/{id}")
    public Result<Map<String, Object>> ledgerDetail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `monitor_ledger` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex();
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("violations", jdbc.queryForList(
                "SELECT * FROM `monitor_violation` WHERE `ledger_id`=? AND `tenant_id`=? AND `deleted`=0 " +
                        "ORDER BY `violation_time` DESC", id, tid()));
        return Result.ok(out);
    }

    // ==================================================================
    // 违规登记 / 按车牌归并统计（M3/M4/M5）
    // ==================================================================
    @PostMapping("/violation")
    @OperLog(module = "monitor", action = "register_violation")
    public Result<Map<String, Object>> registerViolation(@RequestBody Map<String, Object> b) {
        String plate = str(b, "vehicle_plate", "");
        String vtype = str(b, "violation_type", "超速行驶");
        Map<String, Object> r = base();
        r.put("ledger_id", lng(b, "ledger_id", 0));
        r.put("vehicle_id", lng(b, "vehicle_id", 0));
        r.put("vehicle_plate", plate);
        r.put("driver_id", lng(b, "driver_id", 0));
        r.put("violation_type", vtype);
        r.put("violation_time", str(b, "violation_time") == null
                ? LocalDateTime.now() : LocalDateTime.parse(str(b, "violation_time").replace(' ', 'T')));
        r.put("description", str(b, "description", ""));
        r.put("source", str(b, "source", "人工录入"));
        r.put("status", "recorded");
        r.put("education_status", "none");
        long id = insert("monitor_violation", r);

        // 按车牌归并：累计违规次数写回本条记录（M3 统计口径）
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `monitor_violation` WHERE `tenant_id`=? AND `vehicle_plate`=? AND `deleted`=0",
                Integer.class, tid(), plate);
        jdbc.update("UPDATE `monitor_violation` SET `violation_count`=? WHERE `id`=?", cnt == null ? 1 : cnt, id);
        jdbc.update("UPDATE `monitor_ledger` SET `violation_count`=`violation_count`+1 WHERE `id`=?",
                lng(b, "ledger_id", 0));
        registrar.register("monitor", CATALOG, id, "动态违规记录：" + plate + "·" + vtype);
        return Result.ok(Map.of("id", id, "vehiclePlate", plate, "violationCount", cnt == null ? 1 : cnt));
    }

    @GetMapping("/violation/list")
    public Result<List<Map<String, Object>>> violationList() {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM `monitor_violation` WHERE `tenant_id`=? AND `deleted`=0 " +
                        "ORDER BY `violation_time` DESC LIMIT 300", tid()));
    }

    /** 违规车辆识别统计：按车牌归并 + 教育落实率（M3/M7） */
    @GetMapping("/violation/stats")
    public Result<Map<String, Object>> violationStats() {
        List<Map<String, Object>> byPlate = jdbc.queryForList(
                "SELECT `vehicle_plate`, COUNT(*) AS times, " +
                        "SUM(CASE WHEN `education_status`='archived' THEN 1 ELSE 0 END) AS educated " +
                        "FROM `monitor_violation` WHERE `tenant_id`=? AND `deleted`=0 " +
                        "GROUP BY `vehicle_plate` ORDER BY times DESC", tid());
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `monitor_violation` WHERE `tenant_id`=? AND `deleted`=0", Long.class, tid());
        Long done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `monitor_violation` WHERE `tenant_id`=? AND `deleted`=0 " +
                        "AND `education_status`='archived'", Long.class, tid());
        long t = total == null ? 0 : total, d = done == null ? 0 : done;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalViolations", t);
        out.put("vehicleCount", byPlate.size());
        out.put("educatedCount", d);
        out.put("educationRate", t == 0 ? 100 : Math.round(d * 100.0 / t));
        out.put("byPlate", byPlate);
        return Result.ok(out);
    }

    // ==================================================================
    // 靶向教育联动 + 落实跟踪（M6/M7）
    // ==================================================================
    @PostMapping("/violation/{id}/edu")
    @OperLog(module = "monitor", action = "targeted_education")
    public Result<Map<String, Object>> targetedEducation(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `monitor_violation` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex();
        Map<String, Object> v = rows.get(0);
        Object existing = v.get("education_plan_id");
        // 幂等：重复点击/重试不重复建培训计划
        if (existing instanceof Number n && n.longValue() > 0) {
            return Result.ok(Map.of("id", id, "planId", n.longValue(), "educationStatus",
                    String.valueOf(v.get("education_status")), "idempotent", true));
        }
        String vtype = String.valueOf(v.get("violation_type") == null ? "" : v.get("violation_type"));
        Integer matched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `training_video` WHERE `deleted`=0 AND `violation_type`=?", Integer.class, vtype);
        Integer minutes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(`duration`),0) FROM `training_video` WHERE `deleted`=0 AND `violation_type`=?",
                Integer.class, vtype);

        Map<String, Object> p = base();
        p.put("plan_name", "违规靶向教育-" + vtype + "-" + String.valueOf(v.get("vehicle_plate")));
        p.put("plan_type", "targeted");
        p.put("violation_type", vtype);
        p.put("learn_hours", minutes == null || minutes == 0 ? 2 : minutes);
        p.put("force_learn", 1);
        p.put("pass_score", 60);
        p.put("status", "published");
        long planId = insert("training_plan", p);

        jdbc.update("UPDATE `monitor_violation` SET `education_plan_id`=?, `education_status`='pushed', " +
                "`status`='educated' WHERE `id`=?", planId, id);
        registrar.register("monitor", CATALOG, id, "针对性违规教育：" + vtype);
        registrar.register("monitor", CATALOG, planId, "靶向教育计划：" + vtype);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("planId", planId);
        out.put("matchedVideos", matched == null ? 0 : matched);
        out.put("learnHours", p.get("learn_hours"));
        out.put("educationStatus", "pushed");
        return Result.ok(out);
    }

    /** 落实跟踪：none → pushed → learning → passed → archived（M7） */
    @PutMapping("/violation/{id}/edu-status")
    @OperLog(module = "monitor", action = "education_progress")
    public Result<Map<String, Object>> eduStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String next = requiredStr(b, "status");
        if (!EDU_FLOW.contains(next)) throw ErrorCode.PARAM_INVALID.ex("education_status 非法：" + next);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `education_status` FROM `monitor_violation` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                id, tid());
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex();
        String cur = String.valueOf(rows.get(0).get("education_status"));
        if (EDU_FLOW.indexOf(next) < EDU_FLOW.indexOf(cur)) {
            throw ErrorCode.BIZ_STATE_INVALID.ex("教育状态不可回退（" + cur + " → " + next + "）");
        }
        if ("archived".equals(next)) {
            jdbc.update("UPDATE `monitor_violation` SET `education_status`=?, `education_completed_at`=? WHERE `id`=?",
                    next, LocalDateTime.now(), id);
            registrar.register("monitor", CATALOG, id, "违规教育落实归档");
        } else {
            jdbc.update("UPDATE `monitor_violation` SET `education_status`=? WHERE `id`=?", next, id);
        }
        return Result.ok(Map.of("id", id, "from", cur, "to", next, "flow", EDU_FLOW));
    }

    // ==================================================================
    // 委托第三方监控标记 + 归档（M8）
    // ==================================================================
    @GetMapping("/outsource")
    public Result<List<Map<String, Object>>> outsource() {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM `org_outsource` WHERE `tenant_id`=? AND `deleted`=0", tid()));
    }

    @PostMapping("/outsource")
    @OperLog(module = "monitor", action = "outsource")
    public Result<Map<String, Object>> saveOutsource(@RequestBody Map<String, Object> b) {
        String type = str(b, "outsource_type", "monitor");
        String org = str(b, "outsource_org", "");
        int enabled = integer(b, "enabled", 1);
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT `id` FROM `org_outsource` WHERE `tenant_id`=? AND `outsource_type`=? AND `deleted`=0",
                tid(), type);
        long id;
        if (old.isEmpty()) {
            Map<String, Object> r = base();
            r.put("outsource_type", type);
            r.put("outsource_org", org);
            r.put("enabled", enabled);
            id = insert("org_outsource", r);
        } else {
            id = ((Number) old.get(0).get("id")).longValue();
            jdbc.update("UPDATE `org_outsource` SET `outsource_org`=?, `enabled`=? WHERE `id`=?", org, enabled, id);
        }
        return Result.ok(Map.of("id", id, "outsourceType", type, "outsourceOrg", org, "enabled", enabled));
    }

    private static final String ARCHIVE_TITLE = "盒六·动态监控及交通违法管理";

    /** 归档正文（预览与 PDF 共用同一份内容，保证所见即所得）。 */
    private String archiveBody() {
        List<Map<String, Object>> ledgers = jdbc.queryForList(
                "SELECT `ledger_name`,`ledger_type`,`source`,`period`,`violation_count` " +
                        "FROM `monitor_ledger` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid());
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : ledgers) {
            rows.add(Arrays.asList(l.get("ledger_name"), l.get("ledger_type"), l.get("source"),
                    l.get("period"), l.get("violation_count")));
        }
        Map<String, Object> stats = violationStats().getData();
        return "<h2>一、动态监控台账留存</h2>"
                + table(new String[]{"台账名称", "类型", "来源", "周期", "识别违规数"}, rows)
                + "<h2>二、违规识别与靶向教育落实</h2><p>违规总数：" + stats.get("totalViolations")
                + "，涉及车辆：" + stats.get("vehicleCount")
                + "，已完成教育：" + stats.get("educatedCount")
                + "，落实率：" + stats.get("educationRate") + "%</p>";
    }

    @GetMapping("/archive")
    public Result<Map<String, Object>> archivePreview() {
        return Result.ok(Map.of("title", ARCHIVE_TITLE, "catalog", CATALOG,
                "html", html(ARCHIVE_TITLE, archiveBody())));
    }

    @PostMapping("/archive/pdf")
    @OperLog(module = "monitor", action = "archive_pdf")
    public Result<Map<String, Object>> generateArchivePdf(@RequestParam(required = false) Long ledgerId) {
        long bizId = ledgerId == null ? IdUtil.getSnowflakeNextId() : ledgerId;
        return Result.ok(archivePdf("monitor", CATALOG, bizId, ARCHIVE_TITLE, archiveBody()));
    }
}
