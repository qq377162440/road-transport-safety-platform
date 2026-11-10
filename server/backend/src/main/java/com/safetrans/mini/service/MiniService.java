package com.safetrans.mini.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 小程序端（运管管）正式版业务服务。
 *
 * <p><b>数据互通铁律</b>：本服务不建任何业务主表，全部读写与 PC 端相同的表——
 * 一日三检 check_task/check_record/check_item_result、隐患 danger_record、
 * 维修 vehicle_repair、会议 meeting/meeting_attendee、签署 sign_task、
 * 公告 warn_record、档案 person/vehicle/*_doc、风险 risk_card、
 * 投入 budget_expense、考核 credit_assess、培训 training_*。
 * 因此 PC 端下发的任务小程序立即可见，小程序上报的记录 PC 端台账立即可查。
 *
 * <p>按需求文档 2.2「弱网容错 / 少」：首页、我的等页面用聚合接口一次取全，
 * 避免小程序端串行发起十几个请求。
 */
@Service
public class MiniService {

    private final JdbcTemplate jdbc;

    public MiniService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long entId() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private long uid() {
        Long u = TenantContextHolder.userId();
        return u == null ? 0L : u;
    }

    private long l(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private int i(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rs = jdbc.queryForList(sql, args);
        return rs.isEmpty() ? null : rs.get(0);
    }

    private List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 一、身份与首页
    // ==================================================================

    /** 未显式指定学员时，默认取本企业名册第一人（便于直接打开 H5 走查） */
    public long defaultPersonId() {
        Map<String, Object> p = row("SELECT `id` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 " +
                "ORDER BY `id` LIMIT 1", entId());
        return p == null ? 0L : l(p.get("id"));
    }

    /** 启动数据：企业、可选人员名册、当前身份与角色（角色切换为测试期走查能力，对齐原型 role-switch） */
    public Map<String, Object> bootstrap(long personId, String role) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> tenant = row("SELECT `id`,`name` FROM `sys_tenant` WHERE `id`=? AND `deleted`=0", t);
        out.put("enterprise", tenant == null ? "演示企业" : s(tenant.get("name")));
        out.put("tenantId", t);
        List<Map<String, Object>> persons = list(
                "SELECT `id`,`name`,`telephone`,`person_type`,`post_ids` FROM `person` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t);
        out.put("persons", persons);
        long pid = personId > 0 ? personId : (persons.isEmpty() ? 0L : l(persons.get(0).get("id")));
        Map<String, Object> me = row("SELECT `id`,`name`,`telephone`,`person_type`,`post_ids` FROM `person` " +
                "WHERE `id`=? AND `deleted`=0", pid);
        out.put("personId", pid);
        out.put("person", me == null ? Map.of("id", pid, "name", "从业人员") : me);
        out.put("role", "manager".equalsIgnoreCase(role) ? "manager" : "worker");
        out.put("date", row("SELECT DATE_FORMAT(CURDATE(),'%Y年%m月%d日') AS d, " +
                "DAYNAME(CURDATE()) AS w") );
        return out;
    }

    /** 首页：今日必做、公告、功能宫格角标、管理视角今日数据（一次取全） */
    public Map<String, Object> home(long personId, String role) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean manager = "manager".equalsIgnoreCase(role);
        out.put("role", manager ? "manager" : "worker");

        // --- 今日必做：三检 / 会议签到 / 学习，全部来自 PC 端下发数据 ---
        List<Map<String, Object>> todos = new ArrayList<>();

        Map<String, Object> task = currentTask(personId);
        int donePhases = 0;
        if (task != null) {
            donePhases = count("SELECT COUNT(DISTINCT `phase`) FROM `check_record` " +
                    "WHERE `task_id`=? AND `check_day`=CURDATE() AND `deleted`=0", l(task.get("id")));
        }
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("key", "check");
        t1.put("title", "出车前检查");
        t1.put("desc", task == null ? "暂无今日三检任务，请联系安全员"
                : (donePhases >= 3 ? "今日三个时段已全部完成" : "今日已完成 " + donePhases + "/3 个时段"));
        t1.put("done", task != null && donePhases >= 3);
        t1.put("route", "/m/check");
        todos.add(t1);

        int unsigned = count("SELECT COUNT(*) FROM `meeting_attendee` a JOIN `meeting` m ON m.`id`=a.`meeting_id` " +
                "WHERE a.`tenant_id`=? AND a.`user_id`=? AND a.`deleted`=0 AND a.`sign_status`='unsigned' " +
                "AND m.`deleted`=0 AND DATE(m.`start_time`)>=CURDATE()", t, personId);
        Map<String, Object> t2 = new LinkedHashMap<>();
        t2.put("key", "meeting");
        t2.put("title", unsigned > 0 ? "安全会议签到" : "今日暂无待签会议");
        t2.put("desc", unsigned > 0 ? "还有 " + unsigned + " 场会议等你签到" : "会议都已签到了");
        t2.put("done", unsigned == 0);
        t2.put("route", "/m/meeting");
        todos.add(t2);

        Map<String, Object> study = studyBrief(personId);
        Map<String, Object> t3 = new LinkedHashMap<>();
        t3.put("key", "study");
        t3.put("title", "学习安全视频");
        t3.put("desc", Boolean.TRUE.equals(study.get("hasPlan"))
                ? ("本月进度 " + study.get("progress") + "%" + (i(study.get("left")) > 0 ? " · 还有 " + study.get("left") + " 节课" : " · 已学完"))
                : "暂无本月培训计划");
        t3.put("done", Boolean.TRUE.equals(study.get("passed")) || i(study.get("progress")) == 100);
        t3.put("route", "/m/study");
        todos.add(t3);
        out.put("todos", todos);
        out.put("todoLeft", (int) todos.stream().filter(x -> !Boolean.TRUE.equals(x.get("done"))).count());

        // --- 公告 / 通知 ---
        List<Map<String, Object>> notices = notices(personId);
        long unread = notices.stream().filter(x -> i(x.get("read")) == 0).count();
        out.put("unreadNotices", unread);
        out.put("latestNotice", notices.isEmpty() ? null : notices.get(0));
        out.put("notices", notices.size() > 3 ? notices.subList(0, 3) : notices);

        // --- 功能宫格角标 ---
        Map<String, Object> badges = new LinkedHashMap<>();
        badges.put("danger", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `status` NOT IN ('closed','rectified')", t));
        badges.put("repair", count("SELECT COUNT(*) FROM `vehicle_repair` WHERE `tenant_id`=? AND `deleted`=0", t));
        badges.put("sign", count("SELECT COUNT(*) FROM `sign_task` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND (`status` IS NULL OR `status`<>'signed')", t));
        badges.put("meeting", unsigned);
        badges.put("checkin", checkinTasks(personId).size());
        badges.put("risk", count("SELECT COUNT(*) FROM `risk_card` WHERE `tenant_id`=? AND `deleted`=0", t));
        badges.put("budget", count("SELECT COUNT(*) FROM `budget_expense` WHERE `tenant_id`=? AND `deleted`=0", t));
        badges.put("credit", count("SELECT COUNT(*) FROM `credit_assess` WHERE `tenant_id`=? AND `deleted`=0", t));
        badges.put("checks", count("SELECT COUNT(*) FROM `check_record` r JOIN `check_task` k ON k.`id`=r.`task_id` " +
                "WHERE r.`tenant_id`=? AND r.`deleted`=0 AND k.`driver_id`=?", t, personId));
        out.put("badges", badges);

        // --- 管理视角：今日数据概览 ---
        if (manager) out.put("manager", managerOverview());
        return out;
    }

    /** 管理视角今日数据（与 PC 端驾驶舱同源统计） */
    public Map<String, Object> managerOverview() {
        long t = entId();
        Map<String, Object> m = new LinkedHashMap<>();
        int totalTasks = count("SELECT COUNT(*) FROM `check_task` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `status`='ongoing' AND CURDATE()>=`start_date` " +
                "AND (`end_date` IS NULL OR CURDATE()<=`end_date`)", t);
        int doneChecks = count("SELECT COUNT(*) FROM `check_record` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `check_day`=CURDATE()", t);
        m.put("checkDone", doneChecks);
        m.put("checkTotal", totalTasks * 3);
        m.put("overdueDanger", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `status` NOT IN ('closed','rectified') AND `find_time` < DATE_SUB(NOW(), INTERVAL 7 DAY)", t));
        m.put("pendingStudy", count("SELECT COUNT(*) FROM `training_assign` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND (`status` IS NULL OR `status` NOT IN ('completed'))", t));
        m.put("certExpiring", count("SELECT COUNT(*) FROM (" +
                "SELECT `expire_date` FROM `person_doc` WHERE `tenant_id`=? AND `deleted`=0 " +
                "UNION ALL SELECT `expire_date` FROM `vehicle_doc` WHERE `tenant_id`=? AND `deleted`=0" +
                ") x WHERE x.`expire_date` IS NOT NULL AND x.`expire_date` <= DATE_ADD(CURDATE(), INTERVAL 60 DAY)", t, t));
        m.put("personCount", count("SELECT COUNT(*) FROM `person` WHERE `tenant_id`=? AND `deleted`=0", t));
        m.put("vehicleCount", count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0", t));
        return m;
    }

    /** 本月学习概况（与 C1 培训闭环共用 training_assign / training_plan） */
    private Map<String, Object> studyBrief(long personId) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> a = row("SELECT a.`id`,a.`status`,a.`watch_progress`,p.`id` AS plan_id,p.`plan_name` " +
                        "FROM `training_assign` a JOIN `training_plan` p ON p.`id`=a.`plan_id` " +
                        "WHERE a.`tenant_id`=? AND a.`user_id`=? AND a.`deleted`=0 AND p.`deleted`=0 " +
                        "AND p.`status`='published' AND p.`plan_type`='on_job' " +
                        "AND p.`year`=YEAR(CURDATE()) AND p.`month`=MONTH(CURDATE()) " +
                        "ORDER BY p.`id` DESC LIMIT 1", t, personId);
        out.put("hasPlan", a != null);
        out.put("passed", false);
        out.put("progress", 0);
        out.put("left", 0);
        if (a == null) return out;
        long assignId = l(a.get("id"));
        Map<String, Object> last = row("SELECT `result` FROM `training_exam` WHERE `assign_id`=? AND `deleted`=0 " +
                "ORDER BY `id` DESC LIMIT 1", assignId);
        out.put("passed", last != null && "pass".equals(s(last.get("result"))));

        int total = count("SELECT COUNT(*) FROM `training_plan_video` WHERE `plan_id`=? AND `deleted`=0", l(a.get("plan_id")));
        int learned = 0;
        String raw = s(a.get("watch_progress"));
        if (!raw.isBlank()) {
            try {
                cn.hutool.json.JSONObject obj = JSONUtil.parseObj(raw);
                for (String k : obj.keySet()) {
                    Object v = obj.get(k);
                    if (v instanceof cn.hutool.json.JSONObject one) {
                        int pos = one.getInt("position", 0);
                        int dur = one.getInt("duration", 0);
                        if (dur > 0 && pos >= dur - 1) learned++;
                    }
                }
            } catch (Exception ignored) { /* 进度串异常按未学处理 */ }
        }
        out.put("progress", total == 0 ? 0 : learned * 100 / total);
        out.put("left", Math.max(0, total - learned));
        out.put("planName", s(a.get("plan_name")));
        return out;
    }

    // ==================================================================
    // 二、一日三检（读 PC 端下发任务，写入 PC 端台账）
    // ==================================================================

    private Map<String, Object> currentTask(long personId) {
        return row("SELECT k.*, v.`plate_no`,v.`vehicle_type`,v.`brand_model` FROM `check_task` k " +
                        "LEFT JOIN `vehicle` v ON v.`id`=k.`vehicle_id` " +
                        "WHERE k.`tenant_id`=? AND k.`driver_id`=? AND k.`deleted`=0 AND k.`status`='ongoing' " +
                        "AND CURDATE()>=k.`start_date` AND (k.`end_date` IS NULL OR CURDATE()<=k.`end_date`) " +
                        "ORDER BY k.`id` DESC LIMIT 1", entId(), personId);
    }

    /** 三检 Tab：绑定车辆、今日任务、三个时段完成状态、各时段检查项 */
    public Map<String, Object> checkToday(long personId) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> v = row("SELECT v.`id`,v.`plate_no`,v.`vehicle_type`,v.`brand_model`,v.`biz_type` " +
                        "FROM `vehicle` v JOIN `vehicle_driver` d ON d.`vehicle_id`=v.`id` " +
                        "WHERE d.`tenant_id`=? AND d.`driver_id`=? AND d.`deleted`=0 AND v.`deleted`=0 " +
                        "ORDER BY d.`id` LIMIT 1", t, personId);
        out.put("vehicle", v);
        Map<String, Object> task = currentTask(personId);
        out.put("task", task);
        out.put("taskId", task == null ? null : task.get("id"));

        Map<String, Object> phases = new LinkedHashMap<>();
        for (String p : List.of("out", "mid", "in")) {
            Map<String, Object> rec = task == null ? null : row(
                    "SELECT `id`,`result`,`check_time`,`abnormal_note` FROM `check_record` " +
                            "WHERE `task_id`=? AND `phase`=? AND `check_day`=CURDATE() AND `deleted`=0 LIMIT 1",
                    l(task.get("id")), p);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("done", rec != null);
            one.put("result", rec == null ? null : s(rec.get("result")));
            one.put("recordId", rec == null ? null : rec.get("id"));
            phases.put(p, one);
        }
        out.put("phases", phases);
        out.put("doneCount", (int) phases.values().stream()
                .filter(x -> x instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("done"))).count());
        return out;
    }

    /** 检查项字典（PC 端一日三检配置同源） */
    public List<Map<String, Object>> checkItems(String phase) {
        String p = (phase == null || phase.isBlank()) ? "out" : phase;
        return list("SELECT `id`,`item_code`,`item_name`,`phase`,`sort_no` FROM `check_item_dict` " +
                "WHERE `phase`=? AND `deleted`=0 ORDER BY `sort_no`", p);
    }

    /** 我的三检记录（含逐项明细条数） */
    public List<Map<String, Object>> checkRecords(long personId) {
        return list("SELECT r.`id`,r.`phase`,r.`check_day`,r.`result`,r.`check_time`,r.`abnormal_note`, " +
                        "k.`cargo_name`,v.`plate_no`, " +
                        "(SELECT COUNT(*) FROM `check_item_result` i WHERE i.`record_id`=r.`id` AND i.`deleted`=0) AS item_count, " +
                        "(SELECT COUNT(*) FROM `check_item_result` i WHERE i.`record_id`=r.`id` AND i.`deleted`=0 AND i.`abnormal`=1) AS abnormal_count " +
                        "FROM `check_record` r JOIN `check_task` k ON k.`id`=r.`task_id` " +
                        "LEFT JOIN `vehicle` v ON v.`id`=k.`vehicle_id` " +
                        "WHERE r.`tenant_id`=? AND k.`driver_id`=? AND r.`deleted`=0 " +
                        "ORDER BY r.`check_day` DESC, r.`id` DESC LIMIT 50", entId(), personId);
    }

    // ==================================================================
    // 三、会议签到
    // ==================================================================

    public List<Map<String, Object>> meetings(long personId) {
        return list("SELECT m.`id`,m.`title`,m.`topic`,m.`meeting_type`,m.`location`,m.`start_time`,m.`end_time`, " +
                        "m.`notice_title`,m.`notice_content`, " +
                        "a.`sign_status`,a.`sign_time`, " +
                        "(SELECT COUNT(*) FROM `meeting_attendee` x WHERE x.`meeting_id`=m.`id` AND x.`deleted`=0) AS attendee_count, " +
                        "(SELECT COUNT(*) FROM `meeting_attendee` x WHERE x.`meeting_id`=m.`id` AND x.`deleted`=0 " +
                        " AND x.`sign_status`='signed') AS signed_count " +
                        "FROM `meeting` m LEFT JOIN `meeting_attendee` a " +
                        "ON a.`meeting_id`=m.`id` AND a.`user_id`=? AND a.`deleted`=0 " +
                        "WHERE m.`tenant_id`=? AND m.`deleted`=0 AND m.`status`='published' " +
                        "ORDER BY m.`start_time`", personId, entId());
    }

    @Transactional
    public Map<String, Object> signMeeting(long meetingId, long personId, String signImage) {
        long t = entId();
        Map<String, Object> m = row("SELECT `id`,`title` FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                meetingId, t);
        if (m == null) throw new BizException("会议不存在");
        int n = jdbc.update("UPDATE `meeting_attendee` SET `sign_status`='signed',`sign_time`=NOW()," +
                        "`sign_source`='mini',`sign_image`=?,`updated_at`=NOW() " +
                        "WHERE `meeting_id`=? AND `user_id`=? AND `tenant_id`=? AND `deleted`=0",
                signImage, meetingId, personId, t);
        if (n == 0) {
            jdbc.update("INSERT INTO `meeting_attendee` " +
                            "(`id`,`tenant_id`,`meeting_id`,`user_id`,`sign_status`,`sign_time`,`sign_source`," +
                            " `sign_image`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,'signed',NOW(),'mini',?,NOW(),?,0)",
                    IdWorker.getId(), t, meetingId, personId, signImage, uid());
        }
        return Map.of("meetingId", meetingId, "signed", true, "title", s(m.get("title")));
    }

    // ==================================================================
    // 三·五、统一签到入口（会议签到 + 演练签到，司机只看到一个"签到"）
    // ==================================================================

    /** 演练签到写库：写 emergency_drill_sign（统一签到来源） */
    @Transactional
    public Map<String, Object> signDrill(long drillId, long personId, String signImage) {
        long t = entId();
        Map<String, Object> d = row("SELECT `id`,`drill_name` FROM `emergency_drill` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", drillId, t);
        if (d == null) throw new BizException("演练不存在");
        long n = jdbc.update("UPDATE `emergency_drill_sign` SET `sign_status`='signed',`sign_time`=NOW(),`sign_source`='mini'," +
                        "`sign_image`=?,`updated_at`=NOW() WHERE `drill_id`=? AND `user_id`=? AND `tenant_id`=? AND `deleted`=0",
                signImage, drillId, personId, t);
        if (n == 0) {
            jdbc.update("INSERT INTO `emergency_drill_sign` " +
                            "(`id`,`tenant_id`,`drill_id`,`user_id`,`sign_status`,`sign_time`,`sign_source`,`sign_image`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,'signed',NOW(),'mini',?,NOW(),0)",
                    IdWorker.getId(), t, drillId, personId, signImage);
        }
        return Map.of("drillId", drillId, "signed", true, "title", s(d.get("drill_name")));
    }

    /** 统一签到：按 bizModule 路由到会议/演练签到 */
    @Transactional
    public Map<String, Object> unifiedCheckin(String bizModule, long bizId, long personId, String signImage) {
        if ("meeting".equals(bizModule)) return signMeeting(bizId, personId, signImage);
        if ("drill".equals(bizModule)) return signDrill(bizId, personId, signImage);
        throw new BizException("未知签到类型：" + bizModule);
    }

    /** 聚合当前用户待签任务（会议 + 演练），供小程序统一签到页展示 */
    public List<Map<String, Object>> checkinTasks(long personId) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 会议签到（已发布、未签）
        for (Map<String, Object> m : meetings(personId)) {
            if ("signed".equals(s(m.get("sign_status")))) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("bizModule", "meeting");
            t.put("bizId", String.valueOf(m.get("id")));
            t.put("title", s(m.get("title")));
            t.put("subTitle", "会议签到 · " + s(m.get("location")));
            t.put("deadline", s(m.get("start_time")));
            t.put("status", "pending");
            out.add(t);
        }
        // 演练签到（进行中、未签）
        for (Map<String, Object> d : list("SELECT d.`id`,d.`drill_name`,d.`drill_date` FROM `emergency_drill` d " +
                "WHERE d.`tenant_id`=? AND d.`status`='executing' AND d.`deleted`=0 " +
                "AND NOT EXISTS(SELECT 1 FROM `emergency_drill_sign` s WHERE s.`drill_id`=d.`id` AND s.`user_id`=? AND s.`tenant_id`=? AND s.`deleted`=0 AND s.`sign_status`='signed') " +
                "ORDER BY d.`id` DESC LIMIT 20", entId(), personId, entId())) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("bizModule", "drill");
            t.put("bizId", String.valueOf(d.get("id")));
            t.put("title", s(d.get("drill_name")));
            t.put("subTitle", "演练签到");
            t.put("deadline", s(d.get("drill_date")));
            t.put("status", "pending");
            out.add(t);
        }
        return out;
    }

    // ==================================================================
    // 四、隐患上报 / 维修上报（写回 PC 端台账）
    // ==================================================================

    public List<Map<String, Object>> dangers(long personId) {
        return list("SELECT d.`id`,d.`title`,d.`danger_level`,d.`location`,d.`check_type`,d.`status`,d.`find_time`, " +
                        "v.`plate_no`,p.`name` AS finder_name, " +
                        "r.`rectify_require`,r.`deadline`,r.`rectify_note`,r.`submit_time` " +
                        "FROM `danger_record` d " +
                        "LEFT JOIN `vehicle` v ON v.`id`=d.`vehicle_id` " +
                        "LEFT JOIN `person` p ON p.`id`=d.`finder_id` " +
                        "LEFT JOIN `danger_rectify` r ON r.`danger_id`=d.`id` AND r.`deleted`=0 " +
                        "WHERE d.`tenant_id`=? AND d.`deleted`=0 ORDER BY d.`find_time` DESC LIMIT 50", entId());
    }

    @Transactional
    public Map<String, Object> reportDanger(Map<String, Object> b) {
        long t = entId();
        String title = s(b.get("title")).trim();
        if (title.isEmpty()) throw new BizException("请填写隐患情况");
        long id = IdWorker.getId();
        long personId = l(b.get("personId"));
        long vehicleId = l(b.get("vehicleId"));
        jdbc.update("INSERT INTO `danger_record` " +
                        "(`id`,`tenant_id`,`check_type`,`title`,`danger_level`,`location`,`vehicle_id`," +
                        " `finder_id`,`find_time`,`photos`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,'report',?,?,?,?,?,NOW(),?,'pending',NOW(),?,0)",
                id, t, title, s(b.get("level")).isEmpty() ? "一般" : s(b.get("level")),
                s(b.get("location")), vehicleId == 0 ? null : vehicleId,
                personId == 0 ? null : personId, s(b.get("photos")), uid());
        return Map.of("id", id, "title", title, "status", "pending");
    }

    public List<Map<String, Object>> repairs(long personId) {
        return list("SELECT `id`,`plate_no`,`repair_type`,`repair_date`,`mileage`,`fault_desc`," +
                        "`repair_content`,`repair_org`,`cost_amount`,`handler`,`status` " +
                        "FROM `vehicle_repair` WHERE `tenant_id`=? AND `deleted`=0 " +
                        "ORDER BY `repair_date` DESC, `id` DESC LIMIT 50", entId());
    }

    @Transactional
    public Map<String, Object> reportRepair(Map<String, Object> b) {
        long t = entId();
        String fault = s(b.get("faultDesc")).trim();
        if (fault.isEmpty()) throw new BizException("请填写故障情况");
        long vehicleId = l(b.get("vehicleId"));
        String plate = s(b.get("plateNo"));
        if (vehicleId == 0) {
            // 未显式选车时，自动取当前人员绑定车辆（vehicle_id 为必填列）
            Map<String, Object> v = row("SELECT v.`id`,v.`plate_no` FROM `vehicle` v " +
                            "JOIN `vehicle_driver` d ON d.`vehicle_id`=v.`id` " +
                            "WHERE d.`tenant_id`=? AND d.`driver_id`=? AND d.`deleted`=0 AND v.`deleted`=0 LIMIT 1",
                    t, l(b.get("personId")));
            if (v != null) { vehicleId = l(v.get("id")); if (plate.isEmpty()) plate = s(v.get("plate_no")); }
        }
        if (vehicleId == 0) throw new BizException("请先绑定车辆，再上报维修");
        if (plate.isEmpty()) {
            Map<String, Object> v = row("SELECT `plate_no` FROM `vehicle` WHERE `id`=? AND `deleted`=0", vehicleId);
            plate = v == null ? "" : s(v.get("plate_no"));
        }
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO `vehicle_repair` " +
                        "(`id`,`tenant_id`,`vehicle_id`,`plate_no`,`repair_type`,`repair_date`,`mileage`," +
                        " `fault_desc`,`repair_content`,`repair_org`,`cost_amount`,`handler`,`status`," +
                        " `created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,CURDATE(),?,?,?,?,?,?,'pending',NOW(),?,0)",
                id, t, vehicleId == 0 ? null : vehicleId, plate,
                s(b.get("repairType")).isEmpty() ? "报修" : s(b.get("repairType")),
                i(b.get("mileage")), fault, s(b.get("remark")), s(b.get("repairOrg")),
                b.get("costAmount") == null ? null : b.get("costAmount"),
                s(b.get("personName")), uid());
        return Map.of("id", id, "faultDesc", fault, "status", "pending");
    }

    // ==================================================================
    // 五、文件签署 / 公告
    // ==================================================================

    public List<Map<String, Object>> signTasks(long personId) {
        return list("SELECT `id`,`title`,`status`,`signer`,`signed_at`,`created_at` " +
                "FROM `sign_task` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC LIMIT 50", entId());
    }

    @Transactional
    public Map<String, Object> signTask(long id, long personId, String signImage, String personName) {
        long t = entId();
        Map<String, Object> task = row("SELECT `id`,`title`,`status` FROM `sign_task` " +
                "WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, t);
        if (task == null) throw new BizException("签署任务不存在");
        jdbc.update("UPDATE `sign_task` SET `status`='signed',`signer`=?,`signed_at`=NOW()," +
                "`updated_at`=NOW() WHERE `id`=?", personName, id);
        jdbc.update("INSERT INTO `tpl_signature` " +
                        "(`id`,`tenant_id`,`person_id`,`post`,`sign_image`,`sign_type`,`is_default`," +
                        " `created_at`,`created_by`,`deleted`) VALUES (?,?,?,'从业人员',?,?,0,NOW(),?,0)",
                IdWorker.getId(), t, personId, signImage, 1, uid());
        return Map.of("id", id, "signed", true, "title", s(task.get("title")));
    }

    public List<Map<String, Object>> notices(long personId) {
        return list("SELECT w.`id`,w.`warn_type`,w.`level`,w.`title`,w.`expire_date`,w.`created_at`, " +
                        "CASE WHEN r.`id` IS NULL THEN 0 ELSE 1 END AS `read` " +
                        "FROM `warn_record` w LEFT JOIN `mini_notice_read` r " +
                        "ON r.`notice_id`=w.`id` AND r.`person_id`=? AND r.`tenant_id`=w.`tenant_id` AND r.`deleted`=0 " +
                        "WHERE w.`tenant_id`=? AND w.`deleted`=0 AND w.`status`=1 " +
                        "ORDER BY w.`id` DESC LIMIT 30", personId, entId());
    }

    @Transactional
    public Map<String, Object> readNotice(long id, long personId) {
        long t = entId();
        jdbc.update("INSERT INTO `mini_notice_read` " +
                        "(`id`,`tenant_id`,`notice_id`,`person_id`,`read_time`,`created_at`,`deleted`) " +
                        "VALUES (?,?,?,?,NOW(),NOW(),0) " +
                        "ON DUPLICATE KEY UPDATE `read_time`=NOW(),`deleted`=0",
                IdWorker.getId(), t, id, personId);
        return Map.of("id", id, "read", true);
    }

    // ==================================================================
    // 六、只读查看类（风险 / 投入 / 考核 / 个人档案）
    // ==================================================================

    /** 统一列模型：id / title / subtitle / date / amount / status / tag，供小程序配置化列表页渲染 */
    public List<Map<String, Object>> view(String type, long personId) {
        long t = entId();
        List<Map<String, Object>> rs = new ArrayList<>();
        switch (type == null ? "" : type) {
            case "risk" -> {
                for (Map<String, Object> x : list(
                        "SELECT `id`,`year`,`post_name`,`risk_points` FROM `risk_card` " +
                                "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `year` DESC,`id` LIMIT 50", t)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", x.get("id"));
                    r.put("title", s(x.get("post_name")) + " 岗位风险告知卡");
                    r.put("subtitle", "风险点 " + s(x.get("risk_points")));
                    r.put("date", s(x.get("year")) + " 年度");
                    r.put("status", "已辨识");
                    r.put("tag", "蓝");
                    rs.add(r);
                }
            }
            case "budget" -> {
                for (Map<String, Object> x : list(
                        "SELECT `id`,`expense_date`,`expense_desc`,`amount` FROM `budget_expense` " +
                                "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `expense_date` DESC,`id` DESC LIMIT 50", t)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", x.get("id"));
                    r.put("title", s(x.get("expense_desc")));
                    r.put("subtitle", "安全生产费用支出");
                    r.put("date", s(x.get("expense_date")));
                    r.put("amount", s(x.get("amount")));
                    r.put("status", "已入账");
                    r.put("tag", "绿");
                    rs.add(r);
                }
            }
            case "credit" -> {
                for (Map<String, Object> x : list(
                        "SELECT `id`,`year`,`assess_name`,`status`,`created_at` FROM `credit_assess` " +
                                "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `year` DESC,`id` DESC LIMIT 50", t)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", x.get("id"));
                    r.put("title", s(x.get("assess_name")));
                    r.put("subtitle", "诚信考核 / 考核奖惩记录");
                    r.put("date", s(x.get("year")) + " 年度");
                    r.put("status", s(x.get("status")));
                    r.put("tag", "蓝");
                    rs.add(r);
                }
            }
            case "checks" -> {
                for (Map<String, Object> x : checkRecords(personId)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    String phase = "out".equals(s(x.get("phase"))) ? "出车前"
                            : ("mid".equals(s(x.get("phase"))) ? "行车中" : "收车后");
                    r.put("id", x.get("id"));
                    r.put("title", phase + "检查 · " + s(x.get("plate_no")));
                    r.put("subtitle", "逐项检查 " + x.get("item_count") + " 项，异常 " + x.get("abnormal_count") + " 项");
                    r.put("date", s(x.get("check_day")));
                    r.put("status", "pass".equals(s(x.get("result"))) ? "合格" : "有异常");
                    r.put("tag", "pass".equals(s(x.get("result"))) ? "绿" : "红");
                    rs.add(r);
                }
            }
            case "dangers" -> {
                for (Map<String, Object> x : dangers(personId)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", x.get("id"));
                    r.put("title", s(x.get("title")));
                    r.put("subtitle", s(x.get("location")) + " · " + s(x.get("rectify_require")));
                    r.put("date", s(x.get("find_time")));
                    r.put("status", s(x.get("status")));
                    r.put("tag", "黄");
                    rs.add(r);
                }
            }
            case "repairs" -> {
                for (Map<String, Object> x : repairs(personId)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", x.get("id"));
                    r.put("title", s(x.get("fault_desc")));
                    r.put("subtitle", s(x.get("plate_no")) + " · " + s(x.get("repair_org")));
                    r.put("date", s(x.get("repair_date")));
                    r.put("amount", s(x.get("cost_amount")));
                    r.put("status", s(x.get("status")));
                    r.put("tag", "蓝");
                    rs.add(r);
                }
            }
            default -> { /* 未知类型返回空列表 */ }
        }
        return rs;
    }

    // ==================================================================
    // 七、我的：个人档案、证件、绑定车辆、统计
    // ==================================================================

    public Map<String, Object> mine(long personId) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> p = row("SELECT `id`,`name`,`telephone`,`person_type`,`gender`,`post_ids` " +
                "FROM `person` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", personId, t);
        out.put("person", p == null ? Map.of("id", personId, "name", "从业人员") : p);
        Map<String, Object> v = row("SELECT v.`id`,v.`plate_no`,v.`vehicle_type`,v.`brand_model` " +
                        "FROM `vehicle` v JOIN `vehicle_driver` d ON d.`vehicle_id`=v.`id` " +
                        "WHERE d.`tenant_id`=? AND d.`driver_id`=? AND d.`deleted`=0 AND v.`deleted`=0 LIMIT 1",
                t, personId);
        out.put("vehicle", v);

        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> x : list(
                "SELECT `id`,'person' AS owner_type,`doc_name`,`expire_date`,`status` FROM `person_doc` " +
                        "WHERE `tenant_id`=? AND `person_id`=? AND `deleted`=0 " +
                        "UNION ALL " +
                        "SELECT `id`,'vehicle' AS owner_type,`doc_name`,`expire_date`,`status` FROM `vehicle_doc` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `expire_date`", t, personId, t)) {
            Map<String, Object> d = new LinkedHashMap<>(x);
            String exp = s(x.get("expire_date"));
            int days = 0;
            if (exp.length() >= 10) {
                try { days = (int) java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.now(), java.time.LocalDate.parse(exp.substring(0, 10))); }
                catch (Exception ignored) { }
            }
            d.put("daysLeft", days);
            d.put("warning", days <= 60);
            docs.add(d);
        }
        out.put("docs", docs);
        out.put("docWarning", (int) docs.stream().filter(x -> Boolean.TRUE.equals(x.get("warning"))).count());

        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("checks", count("SELECT COUNT(*) FROM `check_record` r JOIN `check_task` k ON k.`id`=r.`task_id` " +
                "WHERE r.`tenant_id`=? AND r.`deleted`=0 AND k.`driver_id`=?", t, personId));
        stat.put("dangers", count("SELECT COUNT(*) FROM `danger_record` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `finder_id`=?", t, personId));
        stat.put("repairs", count("SELECT COUNT(*) FROM `vehicle_repair` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `handler`=(SELECT `name` FROM `person` WHERE `id`=? LIMIT 1)", t, personId));
        stat.put("meetings", count("SELECT COUNT(*) FROM `meeting_attendee` WHERE `tenant_id`=? AND `deleted`=0 " +
                "AND `user_id`=? AND `sign_status`='signed'", t, personId));
        stat.put("exams", count("SELECT COUNT(*) FROM `training_exam` e JOIN `training_assign` a ON a.`id`=e.`assign_id` " +
                "WHERE e.`tenant_id`=? AND e.`deleted`=0 AND a.`user_id`=?", t, personId));
        out.put("stats", stat);
        out.put("study", studyBrief(personId));
        return out;
    }
}
