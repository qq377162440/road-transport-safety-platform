package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 专用业务闭环（B/C 阶段）。
 *
 * <p>通用 CRUD 引擎负责「录入/查询/归档登记」，本控制器负责有状态流转与汇总的模块：
 * B1 危货强制体检、B2 一车一档、B4 三检月报、B5 隐患闭环、B6 会议四段式、
 * B7 电子档案柜、B8 服务商代录、C1 培训学时/考试、C5 驾驶舱、C6 自定义签署。
 *
 * <p>所有产生记录的操作均调用 ArchiveRegistrar 登记归档项（铁律 6）。
 */
@RestController
@RequestMapping("/api/flow")
public class BizFlowController {

    private final JdbcTemplate jdbc;
    private final ArchiveRegistrar registrar;
    private final PdfService pdfService;
    private final StorageService storage;
    private final BizController bizMeta;

    public BizFlowController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                             PdfService pdfService, StorageService storage, BizController bizMeta) {
        this.jdbc = jdbc;
        this.registrar = registrar;
        this.pdfService = pdfService;
        this.storage = storage;
        this.bizMeta = bizMeta;
    }

    // ==================================================================
    // B1 一人一档：危货强制职业健康检查
    // ==================================================================
    @GetMapping("/person/{id}/archive-check")
    public Result<Map<String, Object>> personArchiveCheck(@PathVariable long id,
                                                          @RequestParam(defaultValue = "GENERAL") String bizType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.`item_code`, d.`item_name`, d.`is_optional`, p.`id` AS item_id, p.`status` " +
                        "FROM `person_archive_dict` d " +
                        "LEFT JOIN `person_archive_item` p ON p.`item_code`=d.`item_code` AND p.`person_id`=? " +
                        "AND p.`tenant_id`=? AND p.`deleted`=0 " +
                        "WHERE d.`deleted`=0 ORDER BY d.`sort_no`", id, tid());
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean has = r.get("item_id") != null && "1".equals(String.valueOf(r.get("status")));
            boolean healthItem = String.valueOf(r.get("item_name")).contains("职业健康");
            boolean forced = "DANGER".equalsIgnoreCase(bizType) && healthItem;
            if (!has && (forced || !"1".equals(String.valueOf(r.get("is_optional"))))) {
                Map<String, Object> m = new HashMap<>(r);
                m.put("forced", forced);
                missing.add(m);
            }
        }
        return Result.ok(Map.of("personId", id, "bizType", bizType,
                "total", rows.size(), "missing", missing, "qualified", missing.isEmpty()));
    }

    // ==================================================================
    // B2 一车一档：13 类归档项检查（含补齐的车辆维修保养）
    // ==================================================================
    private static final List<String> VEHICLE_DOC_ITEMS = List.of(
            "机动车行驶证", "道路运输证", "车辆登记证书", "购车发票", "车辆购置税完税证明",
            "机动车交通事故责任强制保险", "承运人责任险", "商业保险", "车辆技术等级评定",
            "二级维护竣工合格证", "车辆综合性能检测报告", "卫星定位装置安装证明", "车辆维修保养记录");

    @GetMapping("/vehicle/{id}/archive-check")
    public Result<Map<String, Object>> vehicleArchiveCheck(@PathVariable long id) {
        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT `doc_type`,`file_url` FROM `vehicle_doc` WHERE `vehicle_id`=? AND `deleted`=0", id);
        Set<String> have = new HashSet<>();
        for (Map<String, Object> d : docs) if (d.get("doc_type") != null) have.add(String.valueOf(d.get("doc_type")));
        List<String> missing = VEHICLE_DOC_ITEMS.stream().filter(i -> !have.contains(i)).toList();
        Long repairs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `vehicle_repair` WHERE `vehicle_id`=? AND `deleted`=0", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vehicleId", id);
        out.put("total", VEHICLE_DOC_ITEMS.size());
        out.put("existing", docs.size());
        out.put("missing", missing);
        out.put("repairRecords", repairs == null ? 0 : repairs);
        out.put("qualified", missing.isEmpty());
        return Result.ok(out);
    }

    // ==================================================================
    // B4 一日三检：任务下发 / 结果采集 / 月报汇总（明细不装盒，只装月报）
    // ==================================================================
    @PostMapping("/check/task")
    public Result<Map<String, Object>> createCheckTask(@RequestBody Map<String, Object> b) {
        // 幂等（E3 试运行实测）：check_task 有唯一键 (tenant_id, vehicle_id, is_trailer, start_date)。
        // 司机重复点击 / 断网重试 / 演练脚本重跑时，直接返回已有任务，
        // 不再插入触发 DuplicateKeyException → HTTP 500。
        Object vehicleId = b.get("vehicle_id");
        Object startDate = b.get("start_date");
        if (vehicleId != null && startDate != null) {
            Long tid = TenantContextHolder.tenantId();
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT `id` FROM `check_task` WHERE `tenant_id`=? AND `vehicle_id`=? " +
                            "AND `is_trailer`=? AND `start_date`=? AND `deleted`=0 LIMIT 1",
                    tid == null ? 0L : tid, vehicleId,
                    b.get("is_trailer") == null ? 0 : b.get("is_trailer"), startDate);
            if (!existing.isEmpty()) {
                return Result.ok(Map.of("id", existing.get(0).get("id"),
                        "status", "existing", "idempotent", true));
            }
        }
        long id = insert("check_task", row(b, "vehicle_id", "driver_id", "escort_id", "is_trailer",
                "start_date", "end_date", "status", "mileage", "mileage_start", "mileage_end",
                "cargo_name", "cargo_qty", "origin", "stopover", "destination"));
        return Result.ok(Map.of("id", id, "status", "created"));
    }

    /** 一次请求完成一次三检（适老化：大按钮、最少字段，30 秒内可完成） */
    @PostMapping("/check/record")
    public Result<Map<String, Object>> submitCheckRecord(@RequestBody Map<String, Object> b) {
        Long taskId = num(b.get("task_id"));
        String phase = str(b.get("phase"));
        String result = str(b.get("result"));
        if (taskId == null || phase == null || result == null) throw ErrorCode.FIELD_REQUIRED.ex("task_id/phase/result");
        if (!List.of("out", "mid", "in").contains(phase)) throw ErrorCode.PARAM_INVALID.ex("phase 应为 out/mid/in");

        Map<String, Object> r = base();
        r.put("task_id", taskId);
        r.put("phase", phase);
        r.put("result", result);
        r.put("check_day", b.getOrDefault("check_day", LocalDate.now()));
        r.put("check_time", LocalDateTime.now());
        r.put("abnormal_note", b.get("abnormal_note"));
        r.put("abnormal_voice", b.get("abnormal_voice"));
        r.put("abnormal_photos", b.get("abnormal_photos"));

        // 幂等（E3 上线质量修复）：同一任务、同一时段、同一天重复提交
        // （司机误触、断网重试）只保留一次结果，避免重复三检记录与重复隐患。
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT `id` FROM `check_record` WHERE `task_id`=? AND `phase`=? " +
                        "AND `check_day`=? AND `deleted`=0 LIMIT 1",
                taskId, phase, r.get("check_day"));
        if (!existing.isEmpty()) {
            long rid = ((Number) existing.get(0).get("id")).longValue();
            // 幂等返回也必须带回已联动生成的隐患 ID，否则「三检异常 → 隐患整改验收」
            // 链路在重跑/重复点击场景下会被切断。
            List<Long> ds = jdbc.queryForList(
                    "SELECT `id` FROM `danger_record` WHERE `ref_id`=? " +
                            "AND `check_type`='check_daily' AND `deleted`=0 ORDER BY `id` DESC LIMIT 1",
                    Long.class, rid);
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("id", rid);
            dup.put("phase", phase);
            dup.put("result", result);
            dup.put("dangerId", ds.isEmpty() ? null : ds.get(0));
            dup.put("idempotent", true);
            return Result.ok(dup);
        }
        long id = insert("check_record", r);

        // 异常自动转隐患（B4 → B5 联动）
        Long dangerId = null;
        if ("abnormal".equals(result)) {
            Map<String, Object> d = base();
            d.put("check_type", "check_daily");
            d.put("ref_id", id);
            d.put("title", "三检异常：" + Objects.toString(b.get("abnormal_note"), "未填写说明"));
            d.put("danger_level", "一般");
            d.put("find_time", LocalDateTime.now());
            d.put("status", "pending");
            dangerId = insert("danger_record", d);
            registrar.register("danger", "BOX8", dangerId, d.get("title").toString());
        }
        // 明细不装盒，仅月报装盒（设计 11 号文档明确要求）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("phase", phase);
        out.put("result", result);
        out.put("dangerId", dangerId);
        return Result.ok(out);
    }

    @GetMapping("/check/monthly-report")
    public Result<Map<String, Object>> monthlyCheckReport(@RequestParam int year, @RequestParam int month) {
        String period = String.format("%d-%02d", year, month);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `check_record` WHERE `deleted`=0 AND `tenant_id`=? AND DATE_FORMAT(`check_day`,'%Y-%m')=?",
                Long.class, tid(), period);
        Long pass = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `check_record` WHERE `deleted`=0 AND `tenant_id`=? AND DATE_FORMAT(`check_day`,'%Y-%m')=? AND `result`='pass'",
                Long.class, tid(), period);
        List<Map<String, Object>> byPhase = jdbc.queryForList(
                "SELECT `phase`, COUNT(*) AS cnt FROM `check_record` WHERE `deleted`=0 AND `tenant_id`=? " +
                        "AND DATE_FORMAT(`check_day`,'%Y-%m')=? GROUP BY `phase`", tid(), period);
        long abnormal = (total == null ? 0 : total) - (pass == null ? 0 : pass);

        // 月报汇总表归档（幂等：每月一条）
        String title = period + " 三检月报汇总表";
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `archive_item` WHERE `biz_module`='check' AND `title`=? AND `tenant_id`=? AND `deleted`=0",
                Integer.class, title, tid());
        if (exists == null || exists == 0) {
            Map<String, Object> a = base();
            a.put("catalog_id", firstCatalogId("BOX9"));
            a.put("data_type", 2);
            a.put("gen_mode", 1);
            a.put("biz_module", "check");
            a.put("biz_id", IdUtil.getSnowflakeNextId());
            a.put("title", title);
            a.put("occur_date", LocalDate.of(year, month, 1));
            a.put("archive_year", year);
            a.put("status", 1);
            insert("archive_item", a);
        }
        return Result.ok(Map.of("period", period, "total", total == null ? 0 : total,
                "pass", pass == null ? 0 : pass, "abnormal", abnormal, "byPhase", byPhase,
                "archived", true, "note", "明细不装盒，仅月报汇总表归档"));
    }

    // ==================================================================
    // B5 隐患排查治理：登记 → 整改 → 验收闭环
    // ==================================================================
    @PostMapping("/danger/{id}/rectify")
    public Result<Map<String, Object>> rectify(@PathVariable long id, @RequestBody Map<String, Object> b) {
        requireExists("danger_record", id);
        Map<String, Object> r = base();
        r.put("danger_id", id);
        r.put("rectify_no", "ZG" + System.currentTimeMillis());
        r.put("rectify_require", b.get("rectify_require"));
        r.put("responsible_id", b.get("responsible_id"));
        r.put("deadline", b.get("deadline"));
        r.put("submit_time", LocalDateTime.now());
        long rid = insert("danger_rectify", r);
        jdbc.update("UPDATE `danger_record` SET `status`='rectifying' WHERE `id`=?", id);
        registrar.register("danger", "BOX8", id, "隐患整改通知书");
        return Result.ok(Map.of("rectifyId", rid, "status", "rectifying"));
    }

    @PostMapping("/danger/{id}/accept")
    public Result<Map<String, Object>> acceptDanger(@PathVariable long id, @RequestBody Map<String, Object> b) {
        requireExists("danger_record", id);
        String result = str(b.get("accept_result"));
        if (!List.of("pass", "reject").contains(result)) throw ErrorCode.PARAM_INVALID.ex("accept_result 应为 pass/reject");
        Map<String, Object> r = base();
        r.put("danger_id", id);
        r.put("acceptor_id", b.get("acceptor_id"));
        r.put("accept_result", result);
        r.put("accept_note", b.get("accept_note"));
        r.put("accept_time", LocalDateTime.now());
        long aid = insert("danger_accept", r);
        String status = "pass".equals(result) ? "closed" : "rectifying";
        jdbc.update("UPDATE `danger_record` SET `status`=? WHERE `id`=?", status, id);
        registrar.register("danger", "BOX8", id, "隐患整改验收单");
        return Result.ok(Map.of("acceptId", aid, "status", status, "closed", "closed".equals(status)));
    }

    // ==================================================================
    // B6 安全会议四段式：通知 / 签到 / 记录 / 照片（逐段自动归档）
    // ==================================================================
    @PostMapping("/meeting/{id}/section")
    public Result<Map<String, Object>> meetingSection(@PathVariable long id, @RequestBody Map<String, Object> b) {
        requireExists("meeting", id);
        String section = str(b.get("section"));
        if (!List.of("notice", "sign", "record", "photos").contains(section)) {
            throw ErrorCode.PARAM_INVALID.ex("section 应为 notice/sign/record/photos");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetingId", id);
        out.put("section", section);
        switch (section) {
            case "notice" -> {
                jdbc.update("UPDATE `meeting` SET `notice_title`=?, `notice_content`=?, `status`='published' WHERE `id`=?",
                        b.get("notice_title"), b.get("notice_content"), id);
                out.put("status", "published");
            }
            case "sign" -> {
                Map<String, Object> r = base();
                r.put("meeting_id", id);
                r.put("user_id", b.get("user_id"));
                r.put("sign_image", b.get("sign_image"));
                r.put("sign_time", LocalDateTime.now());
                r.put("sign_status", 1);
                r.put("sign_source", "onsite");
                out.put("attendeeId", insert("meeting_attendee", r));
            }
            case "record" -> {
                Map<String, Object> r = base();
                r.put("meeting_id", id);
                r.put("content", b.get("content"));
                r.put("ppt_files", b.get("ppt_files"));
                out.put("recordId", insert("meeting_record", r));
            }
            case "photos" -> {
                Integer n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `meeting_record` WHERE `meeting_id`=? AND `deleted`=0", Integer.class, id);
                if (n != null && n > 0) {
                    jdbc.update("UPDATE `meeting_record` SET `venue_photos`=? WHERE `meeting_id`=?", b.get("venue_photos"), id);
                } else {
                    Map<String, Object> r = base();
                    r.put("meeting_id", id);
                    r.put("venue_photos", b.get("venue_photos"));
                    out.put("recordId", insert("meeting_record", r));
                }
            }
        }
        jdbc.update("UPDATE `meeting` SET `status`='archived' WHERE `id`=? AND `status`='finished'", id);
        registrar.register("meeting", "BOX3", id, "安全会议四段式资料");
        return Result.ok(out);
    }

    // ==================================================================
    // B7 电子档案柜：目录树全景 / 缺项提示 / 批量生成（验收标准 2）
    // ==================================================================
    @GetMapping("/archive/overview")
    public Result<Map<String, Object>> archiveOverview(@RequestParam(defaultValue = "GENERAL") String bizType) {
        List<Map<String, Object>> catalog = jdbc.queryForList(
                "SELECT c.`id`,c.`code`,c.`name`,c.`level`,c.`parent_id`,c.`sort_no`," +
                        " (SELECT COUNT(*) FROM `archive_item` i WHERE i.`catalog_id`=c.`id` AND i.`deleted`=0 AND i.`tenant_id`=?) AS item_count " +
                        "FROM `archive_catalog` c WHERE c.`biz_type`=? AND c.`deleted`=0 " +
                        "ORDER BY c.`level`,c.`sort_no`", tid(), bizType);
        List<Map<String, Object>> boxes = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> children = new HashMap<>();
        for (Map<String, Object> c : catalog) {
            long lv = ((Number) c.get("level")).longValue();
            if (lv == 1) boxes.add(c);
            else children.computeIfAbsent(((Number) c.get("parent_id")).longValue(), k -> new ArrayList<>()).add(c);
        }
        for (Map<String, Object> b : boxes) {
            b.put("children", children.getOrDefault(((Number) b.get("id")).longValue(), List.of()));
        }
        Long items = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `archive_item` WHERE `deleted`=0 AND `tenant_id`=?", Long.class, tid());
        return Result.ok(Map.of("bizType", bizType, "boxes", boxes,
                "boxCount", boxes.size(), "archiveItemCount", items == null ? 0 : items));
    }

    @GetMapping("/archive/missing")
    public Result<List<Map<String, Object>>> archiveMissing(@RequestParam(defaultValue = "GENERAL") String bizType) {
        return Result.ok(jdbc.queryForList(
                "SELECT c.`id`,c.`code`,c.`name`,c.`parent_id` FROM `archive_catalog` c " +
                        "WHERE c.`biz_type`=? AND c.`level`=2 AND c.`deleted`=0 " +
                        "AND NOT EXISTS (SELECT 1 FROM `archive_item` i WHERE i.`catalog_id`=c.`id` AND i.`deleted`=0 AND i.`tenant_id`=?) " +
                        "ORDER BY c.`parent_id`,c.`sort_no`", bizType, tid()));
    }

    @PostMapping("/archive/batch-generate")
    public Result<Map<String, Object>> batchGenerate(@RequestParam(defaultValue = "GENERAL") String bizType) {
        List<Map<String, Object>> missing = jdbc.queryForList(
                "SELECT c.`id`,c.`name` FROM `archive_catalog` c WHERE c.`biz_type`=? AND c.`level`=2 AND c.`deleted`=0 " +
                        "AND NOT EXISTS (SELECT 1 FROM `archive_item` i WHERE i.`catalog_id`=c.`id` AND i.`deleted`=0 AND i.`tenant_id`=?) " +
                        "ORDER BY c.`parent_id`,c.`sort_no`", bizType, tid());
        int n = 0;
        for (Map<String, Object> c : missing) {
            Map<String, Object> a = base();
            a.put("catalog_id", c.get("id"));
            a.put("data_type", 1);
            a.put("gen_mode", 3);
            a.put("biz_module", "archive");
            a.put("biz_id", IdUtil.getSnowflakeNextId());
            a.put("title", c.get("name"));
            a.put("occur_date", LocalDate.now());
            a.put("archive_year", LocalDate.now().getYear());
            a.put("status", 0);
            insert("archive_item", a);
            n++;
        }
        return Result.ok(Map.of("generated", n, "note", "已登记为待生成归档项，可批量下载/打印"));
    }

    // ==================================================================
    // B8 服务商代录工作台：跳过起草推送、复用历史签字（验收标准 4）
    // ==================================================================
    @GetMapping("/collect/signatures")
    @RequireRole({"provider", "admin"})
    public Result<List<Map<String, Object>>> historySignatures() {
        return Result.ok(jdbc.queryForList(
                "SELECT `id`,`user_id`,`sign_image`,`sign_time` FROM `meeting_attendee` " +
                        "WHERE `sign_image` IS NOT NULL AND `deleted`=0 ORDER BY `id` DESC LIMIT 50"));
    }

    @PostMapping("/collect/{taskId}/fill")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> collectFill(@PathVariable long taskId, @RequestBody Map<String, Object> b) {
        requireExists("collect_task", taskId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) b.getOrDefault("rows", List.of());
        String targetTable = str(b.get("targetTable"));
        String reuseSign = str(b.get("reuseSignature"));
        int n = 0;
        for (Map<String, Object> r : rows) {
            // 历史签字只留痕在解析结果里，不写入业务表（各表字段不同，避免非法列）
            Map<String, Object> dataRow = new LinkedHashMap<>(r);
            if (reuseSign != null) dataRow.put("_reuse_signature", reuseSign);
            Map<String, Object> cr = base();
            cr.put("task_id", taskId);
            cr.put("row_data", toJson(dataRow));
            cr.put("confidence", 100);
            cr.put("status", 3); // TINYINT：3=已入库
            insert("collect_result", cr);
            // 代录直接落业务表（跳过起草推送环节）
            if (targetTable != null && bizMeta.hasTable(targetTable)) {
                Map<String, Object> bizRow = base();
                for (Map.Entry<String, Object> e : r.entrySet()) {
                    if (!e.getKey().startsWith("_")) bizRow.put(e.getKey(), e.getValue());
                }
                long bizId = insert(targetTable, bizRow);
                registrar.register("collect", "BOX1", bizId, "服务商代录记录");
            }
            n++;
        }
        jdbc.update("UPDATE `collect_task` SET `status`=3 WHERE `id`=?", taskId);
        return Result.ok(Map.of("taskId", taskId, "filled", n, "reuseSignature", reuseSign != null,
                "note", "跳过起草推送，直接代录并归档"));
    }

    // ==================================================================
    // C1 培训教育：学时统计 / 考试留痕（含 force_learn 学时开关）
    // ==================================================================
    @PostMapping("/training/study")
    public Result<Map<String, Object>> trainingStudy(@RequestBody Map<String, Object> b) {
        Long planId = num(b.get("plan_id"));
        Long userId = num(b.get("user_id"));
        int minutes = b.get("minutes") == null ? 0 : ((Number) b.get("minutes")).intValue();
        if (planId == null || userId == null) throw ErrorCode.FIELD_REQUIRED.ex("plan_id/user_id");
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `training_assign` WHERE `plan_id`=? AND `user_id`=? AND `deleted`=0 LIMIT 1",
                Long.class, planId, userId);
        long assignId;
        if (ids.isEmpty()) {
            Map<String, Object> a = base();
            a.put("plan_id", planId);
            a.put("user_id", userId);
            a.put("status", "learning");
            a.put("watch_minutes", minutes);
            a.put("watch_progress", 0);
            assignId = insert("training_assign", a);
        } else {
            assignId = ids.get(0);
            jdbc.update("UPDATE `training_assign` SET `watch_minutes`=COALESCE(`watch_minutes`,0)+?, `status`='learning' WHERE `id`=?",
                    minutes, assignId);
        }
        Integer total = jdbc.queryForObject("SELECT `watch_minutes` FROM `training_assign` WHERE `id`=?", Integer.class, assignId);
        return Result.ok(Map.of("assignId", assignId, "watchMinutes", total == null ? 0 : total));
    }

    @PostMapping("/training/exam")
    public Result<Map<String, Object>> trainingExam(@RequestBody Map<String, Object> b) {
        Long planId = num(b.get("plan_id"));
        Long assignId = num(b.get("assign_id"));
        if (planId == null || assignId == null) throw ErrorCode.FIELD_REQUIRED.ex("plan_id/assign_id");
        Map<String, Object> plan = jdbc.queryForMap(
                "SELECT `force_learn`,`learn_hours`,`pass_score` FROM `training_plan` WHERE `id`=? AND `deleted`=0", planId);
        if ("1".equals(String.valueOf(plan.get("force_learn")))) {
            Integer mins = jdbc.queryForObject(
                    "SELECT COALESCE(`watch_minutes`,0) FROM `training_assign` WHERE `id`=?", Integer.class, assignId);
            int hours = plan.get("learn_hours") == null ? 0 : ((Number) plan.get("learn_hours")).intValue();
            if ((mins == null ? 0 : mins) < hours * 60) {
                throw ErrorCode.BIZ_STATE_INVALID.ex("学时未达标（需 " + hours + " 学时），不能参加考核");
            }
        }
        int score = b.get("score") == null ? 0 : ((Number) b.get("score")).intValue();
        int passLine = plan.get("pass_score") == null ? 60 : ((Number) plan.get("pass_score")).intValue();
        String result = score >= passLine ? "pass" : "fail";
        Map<String, Object> e = base();
        e.put("assign_id", assignId);
        e.put("user_id", b.get("user_id"));
        e.put("answers", b.get("answers"));
        e.put("score", score);
        e.put("result", result);
        e.put("submit_time", LocalDateTime.now());
        long eid = insert("training_exam", e);
        jdbc.update("UPDATE `training_assign` SET `status`=? WHERE `id`=?", result, assignId);
        registrar.register("training", "BOX4", eid, "培训考核记录");
        return Result.ok(Map.of("examId", eid, "score", score, "result", result, "passLine", passLine));
    }

    @GetMapping("/training/{planId}/progress")
    public Result<Map<String, Object>> trainingProgress(@PathVariable long planId) {
        Long learners = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `training_assign` WHERE `plan_id`=? AND `deleted`=0", Long.class, planId);
        Long passed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `training_exam` WHERE `result`='pass' AND `deleted`=0 " +
                        "AND `assign_id` IN (SELECT `id` FROM `training_assign` WHERE `plan_id`=?)", Long.class, planId);
        return Result.ok(Map.of("planId", planId, "learners", learners == null ? 0 : learners,
                "passed", passed == null ? 0 : passed));
    }

    // ==================================================================
    // C5 驾驶舱：企业安全分 / 缺项预警 / 当前最紧急风险（验收标准 3）
    // ==================================================================
    @GetMapping("/dashboard/risk")
    public Result<Map<String, Object>> dashboardRisk() {
        Long missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `archive_catalog` c WHERE c.`level`=2 AND c.`deleted`=0 " +
                        "AND NOT EXISTS (SELECT 1 FROM `archive_item` i WHERE i.`catalog_id`=c.`id` AND i.`deleted`=0 AND i.`tenant_id`=?)",
                Long.class, tid());
        Long openDanger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `danger_record` WHERE `deleted`=0 AND `tenant_id`=? AND `status` NOT IN ('closed')",
                Long.class, tid());
        Long abnormal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `check_record` WHERE `deleted`=0 AND `tenant_id`=? AND `result`='abnormal'",
                Long.class, tid());
        long m = missing == null ? 0 : missing, d = openDanger == null ? 0 : openDanger, a = abnormal == null ? 0 : abnormal;
        long score = Math.max(0, 100 - m * 2 - d * 5 - a);
        List<Map<String, Object>> top = jdbc.queryForList(
                "SELECT `id`,`title`,`danger_level`,`status`,`find_time` FROM `danger_record` " +
                        "WHERE `deleted`=0 AND `tenant_id`=? AND `status` NOT IN ('closed') " +
                        "ORDER BY FIELD(`danger_level`,'重大','较大','一般') , `find_time` DESC LIMIT 5", tid());
        return Result.ok(Map.of("safetyScore", score, "missingCount", m, "openDangerCount", d,
                "abnormalCheckCount", a, "topRisks", top,
                "mostUrgent", top.isEmpty() ? "暂无未闭环隐患" : top.get(0).get("title")));
    }

    // ==================================================================
    // C6 自定义文件签署：按锚点合成签署凭证 PDF
    // ==================================================================
    @PostMapping("/sign/{taskId}/render")
    public Result<Map<String, Object>> renderSign(@PathVariable long taskId) {
        Map<String, Object> task = jdbc.queryForMap("SELECT * FROM `sign_task` WHERE `id`=? AND `deleted`=0", taskId);
        String anchors = Objects.toString(task.get("anchors"), "[]");
        // openhtmltopdf 按 XHTML 解析，meta 必须显式闭合
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:40px;color:#222}" +
                "h1{text-align:center;font-size:22px}table{width:100%;border-collapse:collapse;margin-top:24px}" +
                "td,th{border:1px solid #999;padding:10px;font-size:13px}</style></head><body>" +
                "<h1>文件签署凭证</h1><p>任务：" + esc(Objects.toString(task.get("title"), "")) + "</p>" +
                "<p>签署人：" + esc(Objects.toString(task.get("signer"), "待指定")) + "　签署时间：" + LocalDateTime.now() + "</p>" +
                "<table><tr><th>序号</th><th>锚点类型</th><th>页码</th><th>坐标</th></tr>" +
                anchorRows(anchors) + "</table>" +
                "<p style='margin-top:30px;font-size:12px;color:#666'>本凭证由系统自动生成，记录盖章/签名锚点位置，与原件合并后归档。</p>" +
                "</body></html>";
        byte[] pdf = pdfService.render(html);
        String key = tid() + "/sign/" + taskId + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        jdbc.update("UPDATE `sign_task` SET `result_pdf`=?, `status`='signed', `signed_at`=? WHERE `id`=?",
                key, LocalDateTime.now(), taskId);
        registrar.register("sign", "BOX1", taskId, "文件签署凭证");
        return Result.ok(Map.of("taskId", taskId, "objectKey", key, "size", pdf.length, "status", "signed"));
    }

    // ==================================================================
    // B10 / C7 验收辅助：模块健康与归档归属完整性
    // ==================================================================
    @GetMapping("/health/modules")
    public Result<Map<String, Object>> moduleHealth() {
        List<Map<String, Object>> mods = new ArrayList<>();
        for (BizController.ModuleMeta m : bizMeta.getModules()) {
            Long cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `archive_item` WHERE `biz_module`=? AND `deleted`=0 AND `tenant_id`=?",
                    Long.class, m.getCode(), tid());
            mods.add(Map.of("code", m.getCode(), "title", m.getTitle(), "phase", m.getPhase(),
                    "tables", m.getTables().size(), "catalog", m.getCatalog() == null ? "" : m.getCatalog(),
                    "archiveItems", cnt == null ? 0 : cnt));
        }
        return Result.ok(Map.of("modules", mods, "moduleCount", mods.size(),
                "tables", mods.stream().mapToInt(x -> (int) x.get("tables")).sum()));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================
    private Map<String, Object> base() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant_id", tid());
        r.put("created_at", LocalDateTime.now());
        r.put("created_by", uid());
        r.put("deleted", 0);
        return r;
    }

    private Map<String, Object> row(Map<String, Object> b, String... keys) {
        Map<String, Object> r = base();
        for (String k : keys) if (b.containsKey(k)) r.put(k, b.get(k));
        return r;
    }

    private long insert(String table, Map<String, Object> r) {
        long id = IdUtil.getSnowflakeNextId();
        LinkedHashMap<String, Object> all = new LinkedHashMap<>();
        all.put("id", id);
        all.putAll(r);
        String cols = String.join(",", all.keySet().stream().map(c -> "`" + c + "`").toList());
        String marks = String.join(",", Collections.nCopies(all.size(), "?"));
        jdbc.update("INSERT INTO `" + table + "` (" + cols + ") VALUES (" + marks + ")", all.values().toArray());
        return id;
    }

    private void requireExists(String table, long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE `id`=? AND `deleted`=0", Integer.class, id);
        if (n == null || n == 0) throw ErrorCode.BIZ_NOT_FOUND.ex(table + "#" + id);
    }

    private long firstCatalogId(String code) {
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `archive_catalog` WHERE `code` LIKE ? AND `level`=1 LIMIT 1", Long.class, "%_" + code);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    private String anchorRows(String anchors) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String part : anchors.split("\\},\\{")) {
            String type = part.contains("seal") ? "盖章" : "签名";
            sb.append("<tr><td>").append(i++).append("</td><td>").append(type)
                    .append("</td><td>1</td><td>").append(esc(part.replaceAll("[^0-9,.:xyYXa-zA-Z]", ""))).append("</td></tr>");
        }
        return sb.toString();
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }

    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
}
