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
 * B6 安全会议管理 · 完整流程闭环（详细设计 12）。
 *
 * <p>与既有接口分工：{@link BizFlowController} 的 "/api/flow/meeting/{id}/section" 是早期通用发布入口，
 * 本控制器统一前缀 "/api/flow/meeting"，负责 PC 端专属闭环：会议创建（多选参会人）、通知生成与发布、
 * 状态机（draft→published→ongoing→finished→archived，可 cancel）、签到表（司机自带签名留痕
 * + 安全员/超管补签/标请假）、会议纪要（富文本+照片+PPT）、四段式会议 PDF、盒三按类型归档、
 * 跨模块联动、小程序 /expiring 与 /mine 预留聚合接口。
 *
 * <p>状态机（设计 §3.2）：
 * draft 草稿 → published 已发布(未开始) → ongoing 进行中 → finished 已结束 → archived 已归档；
 * published 之前可 cancel 取消；time 驱动 published/ongoing/finished 自动推进，archived 为显式终端。
 *
 * <p>签到留痕（M3/M9）：sign_image（签名图片）+ sign_time（自动时间戳）+ sign_status，生成后不可随意改；
 * 补签必带 sign_source=replenish + replenished_by，可追溯。
 *
 * <p>小程序边界（用户 2026-11-10 确认）：仅 /expiring 与 /mine 两个聚合 GET 预留数据接口，
 * 不新增、不改动小程序端页面（既有 /mini/meeting/list、/mini/meeting/{id}/sign、MobileMeeting.vue 保留）。
 */
@RestController
@RequestMapping("/api/flow/meeting")
public class MeetingFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public MeetingFlowController(JdbcTemplate jdbc, PdfService pdfService,
                                StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    // 两类会议（M1，对齐档案盒盒三两个子项）
    private static final List<String[]> MEETING_TYPES = List.of(
            new String[]{"leader", "安全生产领导小组会议", "定期（季度/半年，可配）"},
            new String[]{"monthly", "安全生产月度会议", "每月"});

    private static final Map<String, String> STATUS = new LinkedHashMap<>();
    static {
        STATUS.put("draft", "草稿");
        STATUS.put("published", "未开始");
        STATUS.put("ongoing", "进行中");
        STATUS.put("finished", "已结束");
        STATUS.put("archived", "已归档");
        STATUS.put("cancelled", "已取消");
    }
    private static final List<String[]> FLOW = List.of(
            new String[]{"draft", "草稿"}, new String[]{"published", "未开始"},
            new String[]{"ongoing", "进行中"}, new String[]{"finished", "已结束"},
            new String[]{"archived", "已归档"});

    private static final Map<String, String> SIGN_STATUS = new LinkedHashMap<>();
    static {
        SIGN_STATUS.put("signed", "已签到");
        SIGN_STATUS.put("absent", "缺席");
        SIGN_STATUS.put("leave", "请假");
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
    private String date16(Object o) { return s(o).length() >= 16 ? s(o).substring(0, 16) : s(o); }
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
        for (String[] t : MEETING_TYPES) if (t[0].equals(code)) return t[1];
        return code == null ? "" : code;
    }
    private String statusName(String code) { return STATUS.getOrDefault(code, code == null ? "" : code); }
    private String signName(String code) { return SIGN_STATUS.getOrDefault(code, code == null ? "缺席" : code); }

    /** 状态流转日志（全流程留痕） */
    private void log(long meetingId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `meeting_flow_log` (`id`,`tenant_id`,`meeting_id`,`action`," +
                            "`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), meetingId, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
    }

    /** 时间驱动状态推进：published→ongoing→finished（draft/cancelled/archived 不动） */
    private void advanceStatus(long meetingId) {
        Map<String, Object> m = one("SELECT `status`,`start_time`,`end_time` FROM `meeting` " +
                "WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", meetingId, tid());
        if (m.isEmpty()) return;
        String st = s(m.get("status"));
        if (!List.of("published", "ongoing").contains(st)) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = parseDt(m.get("start_time"));
        LocalDateTime end = parseDt(m.get("end_time"));
        String to = st;
        if (start != null && now.isAfter(start) && !"ongoing".equals(st) && !"finished".equals(st) && !"archived".equals(st)) to = "ongoing";
        if (end != null && now.isAfter(end) && !"finished".equals(st) && !"archived".equals(st)) to = "finished";
        if (!to.equals(st)) {
            exec("UPDATE `meeting` SET `status`=? WHERE `id`=?", to, meetingId);
            log(meetingId, "advance", st, to, "按会议时间自动推进状态");
        }
    }
    private LocalDateTime parseDt(Object o) {
        if (o == null) return null;
        String v = s(o).replace(' ', 'T');
        if (v.length() >= 16) { try { return LocalDateTime.parse(v.substring(0, 16)); } catch (Exception e) { return null; } }
        if (v.length() >= 10) { try { return LocalDateTime.parse(v.substring(0, 10) + "T00:00:00"); } catch (Exception e) { return null; } }
        return null;
    }

    /** 签到表岗位顺序（线下固定口径，设计 §4.5） */
    private static final List<String> POST_ORDER = List.of("安全管理员", "监控员", "总经理", "车队长", "驾驶员");
    private int postRank(String post) {
        int idx = POST_ORDER.indexOf(post);
        return idx < 0 ? POST_ORDER.size() : idx;
    }

    // ==================================================================
    // 1. 概览 / 字典 / 签到看板
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String meetingType,
                                               @RequestParam(required = false) String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("statusMap", STATUS);
        out.put("flow", FLOW.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("code", f[0]); m.put("name", f[1]); return m;
        }).toList());
        out.put("meetingTypes", MEETING_TYPES.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t[0]); m.put("name", t[1]); m.put("freq", t[2]); return m;
        }).toList());

        long total = count("SELECT COUNT(*) FROM `meeting` WHERE `tenant_id`=? AND `deleted`=0", tid());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        for (String st : STATUS.keySet())
            stats.put(st, count("SELECT COUNT(*) FROM `meeting` WHERE `tenant_id`=? AND `deleted`=0 AND `status`=?", tid(), st));
        long archived = l(stats.get("archived"));
        stats.put("open", total - archived);
        stats.put("completionRate", total == 0 ? 100 : Math.round(archived * 1000.0 / total) / 10.0);
        stats.put("signed", count("SELECT COUNT(*) FROM `meeting_attendee` WHERE `tenant_id`=? AND `sign_status`='signed' AND `deleted`=0", tid()));
        stats.put("attendees", count("SELECT COUNT(*) FROM `meeting_attendee` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        out.put("stats", stats);

        out.put("records", records(status, meetingType, keyword));
        out.put("board", board());
        out.put("archives", archives());
        out.put("refs", referencesData());
        return Result.ok(out);
    }

    @GetMapping("/types")
    public Result<List<Map<String, Object>>> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] t : MEETING_TYPES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t[0]); m.put("name", t[1]); m.put("freq", t[2]); out.add(m);
        }
        return Result.ok(out);
    }

    /** 签到看板：每场会议的签到进度 + 未签名单 */
    private List<Map<String, Object>> board() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT m.`id`,m.`title`,m.`meeting_type`,m.`start_time`,m.`status`," +
                "(SELECT COUNT(*) FROM `meeting_attendee` a WHERE a.`meeting_id`=m.`id` AND a.`tenant_id`=? AND a.`deleted`=0) AS total," +
                "(SELECT COUNT(*) FROM `meeting_attendee` a WHERE a.`meeting_id`=m.`id` AND a.`tenant_id`=? AND a.`deleted`=0 AND a.`sign_status`='signed') AS signed " +
                "FROM `meeting` m WHERE m.`tenant_id`=? AND m.`deleted`=0 ORDER BY m.`start_time` DESC LIMIT 30", tid(), tid(), tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("typeName", typeName(s(r.get("meeting_type"))));
            m.put("statusName", statusName(s(r.get("status"))));
            long total = l(r.get("total")), sg = l(r.get("signed"));
            m.put("progress", total == 0 ? 0 : Math.round(sg * 1000.0 / total) / 10.0);
            m.put("unsignedNames", unsignedNames(l(r.get("id"))));
            out.add(m);
        }
        return out;
    }
    private String unsignedNames(long meetingId) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : q("SELECT p.`name` FROM `meeting_attendee` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`user_id` WHERE a.`meeting_id`=? AND a.`tenant_id`=? " +
                "AND a.`sign_status`<>'signed' AND a.`deleted`=0 ORDER BY a.`id`", meetingId, tid())) {
            if (sb.length() > 0) sb.append("、");
            sb.append(s(r.get("name")));
        }
        return sb.toString();
    }

    // ==================================================================
    // 2. 会议主表：创建 / 编辑 / 发布 / 取消 / 超管纠错
    // ==================================================================
    /** 自动生成通知（M6 模板占位） */
    private Map<String, String> buildNotice(Map<String, Object> m) {
        String title = s(m.get("title"));
        String month = date10(m.get("start_time"));
        if (month.length() >= 7) month = month.substring(0, 7).replace("-", "年") + "月";
        String place;
        if ("online".equals(s(m.get("mode")))) {
            place = "会议方式：线上（" + (s(m.get("meeting_no")).isBlank() ? "腾讯会议" : "腾讯会议号 " + s(m.get("meeting_no"))) +
                    (s(m.get("link")).isBlank() ? "" : "，" + s(m.get("link"))) + "）";
        } else {
            place = "会议地点：" + (s(m.get("location")).isBlank() ? "公司会议室" : s(m.get("location")));
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT p.`name` FROM `meeting_attendee` a LEFT JOIN `person` p ON p.`id`=a.`user_id` " +
                "WHERE a.`meeting_id`=? AND a.`tenant_id`=? AND a.`deleted`=0 ORDER BY a.`id`", l(m.get("id")), tid()))
            names.add(s(r.get("name")));
        String noticeTitle = "关于召开" + month + "安全生产工作会议的通知";
        String content = "为确保公司安全生产各项工作顺利开展，兹定于 " + date10(m.get("start_time")) + " 召开" +
                (title.isBlank() ? month + "安全生产工作会议" : title) + "，现将有关事宜通知如下：\n" +
                "一、会议时间：" + date16(m.get("start_time")) + "\n" +
                "二、" + place + "\n" +
                "三、会议议题：" + (s(m.get("topic")).isBlank() ? "安全宣贯、工作总结与部署" : s(m.get("topic"))) + "\n" +
                "四、参会人员：" + (names.isEmpty() ? "相关人员" : String.join("、", names)) + "\n" +
                "请准时参加。\n" + company();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("noticeTitle", noticeTitle); out.put("noticeContent", content);
        return out;
    }

    @PostMapping("")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> b) {
        String title = s(b.get("title"));
        if (title.isBlank()) return Result.ok(fail("会议名称不能为空"));
        String type = s(b.get("meetingType"));
        if (type.isBlank()) return Result.ok(fail("请选择会议类型"));
        String mode = s(b.getOrDefault("mode", "offline"));
        String start = s(b.get("startTime"));
        String end = s(b.get("endTime"));
        if (start.isBlank() || end.isBlank()) return Result.ok(fail("请填写会议开始与结束时间"));
        List<?> attendeeIds = b.get("attendeeIds") instanceof List<?> li ? li : List.of();
        long host = l(b.get("hostId"));
        long recorder = l(b.get("recorderId"));
        if (host == 0L) return Result.ok(fail("请选择主持人"));
        if (recorder == 0L) return Result.ok(fail("请选择记录人"));

        long mid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `meeting` (`id`,`tenant_id`,`meeting_type`,`title`,`topic`,`mode`,`meeting_no`,`link`," +
                        "`location`,`start_time`,`end_time`,`host_id`,`recorder_id`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?,0)",
                mid, tid(), type, title, s(b.get("topic")), mode, s(b.get("meetingNo")), s(b.get("link")),
                s(b.get("location")), start.replace('T', ' ').substring(0, 19), end.replace('T', ' ').substring(0, 19),
                host, recorder, LocalDateTime.now(), TenantContextHolder.userId());
        // 参会人（默认缺席，签到后更新）
        for (Object aid : attendeeIds) {
            long pid = l(aid);
            if (pid == 0L) continue;
            exec("INSERT INTO `meeting_attendee` (`id`,`tenant_id`,`meeting_id`,`user_id`,`sign_status`," +
                            "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,'absent',?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), mid, pid, LocalDateTime.now(), TenantContextHolder.userId());
        }
        // 空会议记录占位（便于后续录入）
        exec("INSERT INTO `meeting_record` (`id`,`tenant_id`,`meeting_id`,`created_at`,`created_by`,`deleted`) " +
                "VALUES (?,?,?,?,?,0)", IdUtil.getSnowflakeNextId(), tid(), mid, LocalDateTime.now(), TenantContextHolder.userId());
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=?", mid);
        Map<String, String> nt = buildNotice(m);
        exec("UPDATE `meeting` SET `notice_title`=?,`notice_content`=? WHERE `id`=?", nt.get("noticeTitle"), nt.get("noticeContent"), mid);
        log(mid, "create", "", "draft", "创建会议（" + typeName(type) + "）：" + title + "，参会 " + attendeeIds.size() + " 人");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("meetingId", String.valueOf(mid));
        out.put("attendeeCount", attendeeIds.size());
        out.put("noticeTitle", nt.get("noticeTitle"));
        return Result.ok(out);
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> edit(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        String st = s(m.get("status"));
        if (!List.of("draft", "published").contains(st))
            return Result.ok(fail("仅草稿/未开始状态可编辑，当前 " + statusName(st)));
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (b.containsKey("title")) { sets.add("`title`=?"); args.add(s(b.get("title"))); }
        if (b.containsKey("topic")) { sets.add("`topic`=?"); args.add(s(b.get("topic"))); }
        if (b.containsKey("mode")) { sets.add("`mode`=?"); args.add(s(b.get("mode"))); }
        if (b.containsKey("meetingNo")) { sets.add("`meeting_no`=?"); args.add(s(b.get("meetingNo"))); }
        if (b.containsKey("link")) { sets.add("`link`=?"); args.add(s(b.get("link"))); }
        if (b.containsKey("location")) { sets.add("`location`=?"); args.add(s(b.get("location"))); }
        if (b.containsKey("startTime")) { sets.add("`start_time`=?"); args.add(s(b.get("startTime")).replace('T', ' ').substring(0, 19)); }
        if (b.containsKey("endTime")) { sets.add("`end_time`=?"); args.add(s(b.get("endTime")).replace('T', ' ').substring(0, 19)); }
        if (b.containsKey("hostId")) { sets.add("`host_id`=?"); args.add(l(b.get("hostId"))); }
        if (b.containsKey("recorderId")) { sets.add("`recorder_id`=?"); args.add(l(b.get("recorderId"))); }
        if (sets.isEmpty()) return Result.ok(Map.of("ok", true));
        sets.add("`updated_at`=?"); args.add(LocalDateTime.now());
        StringBuilder sql = new StringBuilder("UPDATE `meeting` SET ").append(String.join(",", sets)).append(" WHERE `id`=?");
        args.add(id);
        exec(sql.toString(), args.toArray());
        // 参会人变更
        if (b.containsKey("attendeeIds")) {
            List<?> attendeeIds = b.get("attendeeIds") instanceof List<?> li ? li : List.of();
            exec("UPDATE `meeting_attendee` SET `deleted`=1 WHERE `meeting_id`=? AND `tenant_id`=?", id, tid());
            for (Object aid : attendeeIds) {
                long pid = l(aid);
                if (pid == 0L) continue;
                exec("INSERT INTO `meeting_attendee` (`id`,`tenant_id`,`meeting_id`,`user_id`,`sign_status`," +
                                "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,?,'absent',?,?,0)",
                        IdUtil.getSnowflakeNextId(), tid(), id, pid, LocalDateTime.now(), TenantContextHolder.userId());
            }
        }
        Map<String, String> nt = buildNotice(one("SELECT * FROM `meeting` WHERE `id`=?", id));
        exec("UPDATE `meeting` SET `notice_title`=?,`notice_content`=? WHERE `id`=?", nt.get("noticeTitle"), nt.get("noticeContent"), id);
        log(id, "edit", st, st, "编辑会议信息，通知已同步刷新");
        return Result.ok(Map.of("ok", true, "noticeTitle", nt.get("noticeTitle")));
    }

    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publish(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        if (!"draft".equals(s(m.get("status")))) return Result.ok(fail("仅草稿可发布，当前 " + statusName(s(m.get("status")))));
        Map<String, String> nt = buildNotice(m);
        exec("UPDATE `meeting` SET `status`='published',`publish_time`=?,`notice_title`=?,`notice_content`=?," +
                "`updated_at`=? WHERE `id`=?", LocalDateTime.now(), nt.get("noticeTitle"), nt.get("noticeContent"), LocalDateTime.now(), id);
        log(id, "publish", "draft", "published", "发布会议通知：" + nt.get("noticeTitle"));
        return Result.ok(Map.of("ok", true, "status", "published", "noticeTitle", nt.get("noticeTitle")));
    }

    @PostMapping("/{id}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        String st = s(m.get("status"));
        if (!List.of("draft", "published").contains(st))
            return Result.ok(fail("仅草稿/未开始状态可取消，当前 " + statusName(st)));
        exec("UPDATE `meeting` SET `status`='cancelled',`cancelled_at`=?,`updated_at`=? WHERE `id`=?", LocalDateTime.now(), LocalDateTime.now(), id);
        log(id, "cancel", st, "cancelled", "取消会议");
        return Result.ok(Map.of("ok", true, "status", "cancelled"));
    }

    /** 超管纠错（@RequireRole），企业账号 403 */
    @PutMapping("/{id}/correct")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> correct(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        String title = s(b.getOrDefault("title", m.get("title")));
        String type = s(b.getOrDefault("meetingType", m.get("meeting_type")));
        String status = s(b.getOrDefault("status", m.get("status")));
        if (!STATUS.containsKey(status)) return Result.ok(fail("状态值不合法"));
        exec("UPDATE `meeting` SET `title`=?,`meeting_type`=?,`status`=?,`updated_at`=? WHERE `id`=?",
                title, type, status, LocalDateTime.now(), id);
        log(id, "correct", s(m.get("status")), status, "超管纠错：调整会议信息/状态");
        return Result.ok(Map.of("ok", true, "status", status));
    }

    // ==================================================================
    // 3. 列表 / 详情
    // ==================================================================
    private List<Map<String, Object>> records(String status, String meetingType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT m.*, h.`name` AS host_name, r.`name` AS recorder_name," +
                "(SELECT COUNT(*) FROM `meeting_attendee` a WHERE a.`meeting_id`=m.`id` AND a.`tenant_id`=? AND a.`deleted`=0) AS attendee_total," +
                "(SELECT COUNT(*) FROM `meeting_attendee` a WHERE a.`meeting_id`=m.`id` AND a.`tenant_id`=? AND a.`deleted`=0 AND a.`sign_status`='signed') AS sign_total " +
                "FROM `meeting` m LEFT JOIN `person` h ON h.`id`=m.`host_id` LEFT JOIN `person` r ON r.`id`=m.`recorder_id` " +
                "WHERE m.`tenant_id`=? AND m.`deleted`=0");
        List<Object> args = new ArrayList<>();
        args.add(tid()); args.add(tid()); args.add(tid());
        if (status != null && !status.isBlank()) { sql.append(" AND m.`status`=?"); args.add(status); }
        if (meetingType != null && !meetingType.isBlank()) { sql.append(" AND m.`meeting_type`=?"); args.add(meetingType); }
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND m.`title` LIKE ?"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY m.`start_time` DESC LIMIT 100");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            advanceStatus(l(r.get("id")));
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("typeName", typeName(s(r.get("meeting_type"))));
            m.put("statusName", statusName(s(r.get("status"))));
            m.put("signProgress", l(r.get("attendee_total")) == 0 ? 0 :
                    Math.round(l(r.get("sign_total")) * 1000.0 / l(r.get("attendee_total"))) / 10.0);
            out.add(m);
        }
        return out;
    }

    @GetMapping("")
    public Result<List<Map<String, Object>>> listApi(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String meetingType,
                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(records(status, meetingType, keyword));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> m = one("SELECT m.*, h.`name` AS host_name, r.`name` AS recorder_name " +
                "FROM `meeting` m LEFT JOIN `person` h ON h.`id`=m.`host_id` LEFT JOIN `person` r ON r.`id`=m.`recorder_id` " +
                "WHERE m.`id`=? AND m.`tenant_id`=? AND m.`deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        advanceStatus(id);
        m = one("SELECT m.*, h.`name` AS host_name, r.`name` AS recorder_name " +
                "FROM `meeting` m LEFT JOIN `person` h ON h.`id`=m.`host_id` LEFT JOIN `person` r ON r.`id`=m.`recorder_id` " +
                "WHERE m.`id`=? AND m.`tenant_id`=? AND m.`deleted`=0", id, tid());
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("id", String.valueOf(m.get("id")));
        out.put("typeName", typeName(s(m.get("meeting_type"))));
        out.put("statusName", statusName(s(m.get("status"))));
        out.put("hostName", s(m.get("host_name")));
        out.put("recorderName", s(m.get("recorder_name")));

        // 参会签到表（按岗位固定顺序，设计 §4.5）
        List<Map<String, Object>> atts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT a.*, p.`name`, p.`person_type` FROM `meeting_attendee` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`user_id` WHERE a.`meeting_id`=? AND a.`tenant_id`=? " +
                "AND a.`deleted`=0 ORDER BY a.`id`", id, tid())) {
            String post = s(r.get("person_type"));
            Map<String, Object> mm = new LinkedHashMap<>(r);
            mm.put("id", String.valueOf(r.get("id")));
            mm.put("name", s(r.get("name")));
            mm.put("post", post);
            mm.put("signStatusName", signName(s(r.get("sign_status"))));
            mm.put("replenishedByName", personName(r.get("replenished_by")));
            atts.add(mm);
        }
        atts.sort((a, b) -> Integer.compare(postRank(s(a.get("post"))), postRank(s(b.get("post")))));
        out.put("attendees", atts);

        // 会议纪要
        Map<String, Object> rec = one("SELECT * FROM `meeting_record` WHERE `meeting_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", id, tid());
        Map<String, Object> recOut = new LinkedHashMap<>();
        if (!rec.isEmpty()) {
            recOut.put("id", String.valueOf(rec.get("id")));
            recOut.put("content", s(rec.get("content")));
            recOut.put("venuePhotos", parseJsonArr(s(rec.get("venue_photos"))));
            recOut.put("pptFiles", parseJsonArr(s(rec.get("ppt_files"))));
            recOut.put("hasRecord", !s(rec.get("content")).isBlank());
        }
        out.put("record", recOut);

        // 归档完整度（四要素）
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("record", !s(rec.get("content")).isBlank());
        comp.put("sign", count("SELECT COUNT(*) FROM `meeting_attendee` WHERE `meeting_id`=? AND `tenant_id`=? AND `sign_status`='signed' AND `deleted`=0", id, tid()) > 0);
        comp.put("photo", !s(rec.get("venue_photos")).isBlank() && !"[]".equals(s(rec.get("venue_photos"))));
        comp.put("ppt", !s(rec.get("ppt_files")).isBlank() && !"[]".equals(s(rec.get("ppt_files"))));
        comp.put("rate", comp.values().stream().filter(v -> Boolean.TRUE.equals(v)).count() * 25);
        out.put("completeness", comp);

        // 流转日志
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `action`,`from_status`,`to_status`,`detail`,`operator`,`created_at` " +
                "FROM `meeting_flow_log` WHERE `meeting_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `id`", id, tid()))
            logs.add(new LinkedHashMap<>(r));
        out.put("logs", logs);
        return Result.ok(out);
    }

    private List<String> parseJsonArr(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || "[]".equals(raw)) return out;
        try {
            for (Object o : cn.hutool.json.JSONUtil.parseArray(raw)) out.add(String.valueOf(o));
        } catch (Exception ignore) { }
        return out;
    }

    // ==================================================================
    // 4. 签到：司机签名 / 补签 / 标请假
    // ==================================================================
    @GetMapping("/{id}/attendees")
    public Result<List<Map<String, Object>>> attendees(@PathVariable long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT a.*, p.`name`, p.`person_type` FROM `meeting_attendee` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`user_id` WHERE a.`meeting_id`=? AND a.`tenant_id`=? " +
                "AND a.`deleted`=0 ORDER BY a.`id`", id, tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", s(r.get("name")));
            m.put("post", s(r.get("person_type")));
            m.put("signStatusName", signName(s(r.get("sign_status"))));
            out.add(m);
        }
        return Result.ok(out);
    }

    /** 司机/PC 签到（self）。sign_image 签名图片 URL，自动时间戳；可重复签（覆盖） */
    @PostMapping("/{id}/sign")
    public Result<Map<String, Object>> sign(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        long userId = l(b.get("userId"));
        if (userId == 0L) return Result.ok(fail("缺少参会人 userId"));
        Map<String, Object> a = one("SELECT * FROM `meeting_attendee` WHERE `meeting_id`=? AND `tenant_id`=? AND `user_id`=? AND `deleted`=0", id, tid(), userId);
        if (a.isEmpty()) return Result.ok(fail("该人员不在参会名单中"));
        String img = s(b.get("signImage"));
        exec("UPDATE `meeting_attendee` SET `sign_status`='signed',`sign_image`=?,`sign_time`=?,`sign_source`='self'," +
                "`absent_reason`=NULL,`updated_at`=? WHERE `id`=?", img, LocalDateTime.now(), LocalDateTime.now(), l(a.get("id")));
        log(id, "sign", "absent", "signed", "参会人 " + personName(userId) + " 签到" + (img.isBlank() ? "（无签名图）" : "（含签名留痕）"));
        return Result.ok(Map.of("ok", true, "signStatus", "signed", "signTime", LocalDateTime.now().toString()));
    }

    /** 安全员/超管补签（M9，必留来源） */
    @PostMapping("/{id}/replenish")
    public Result<Map<String, Object>> replenish(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        long userId = l(b.get("userId"));
        if (userId == 0L) return Result.ok(fail("缺少参会人 userId"));
        Map<String, Object> a = one("SELECT * FROM `meeting_attendee` WHERE `meeting_id`=? AND `tenant_id`=? AND `user_id`=? AND `deleted`=0", id, tid(), userId);
        if (a.isEmpty()) return Result.ok(fail("该人员不在参会名单中"));
        String img = s(b.get("signImage"));
        exec("UPDATE `meeting_attendee` SET `sign_status`='signed',`sign_image`=?,`sign_time`=?,`sign_source`='replenish'," +
                        "`replenished_by`=?,`absent_reason`=NULL,`updated_at`=? WHERE `id`=?",
                img, LocalDateTime.now(), TenantContextHolder.userId(), LocalDateTime.now(), l(a.get("id")));
        log(id, "replenish", s(a.get("sign_status")), "signed", "补签：" + personName(userId));
        return Result.ok(Map.of("ok", true, "signStatus", "signed", "source", "replenish", "replenishedBy", operator()));
    }

    /** 标请假（缺席原因） */
    @PostMapping("/{id}/mark-leave")
    public Result<Map<String, Object>> markLeave(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        long userId = l(b.get("userId"));
        if (userId == 0L) return Result.ok(fail("缺少参会人 userId"));
        Map<String, Object> a = one("SELECT * FROM `meeting_attendee` WHERE `meeting_id`=? AND `tenant_id`=? AND `user_id`=? AND `deleted`=0", id, tid(), userId);
        if (a.isEmpty()) return Result.ok(fail("该人员不在参会名单中"));
        exec("UPDATE `meeting_attendee` SET `sign_status`='leave',`absent_reason`=?,`updated_at`=? WHERE `id`=?",
                s(b.get("reason")), LocalDateTime.now(), l(a.get("id")));
        log(id, "leave", s(a.get("sign_status")), "leave", "标记请假：" + personName(userId) + " " + s(b.get("reason")));
        return Result.ok(Map.of("ok", true, "signStatus", "leave"));
    }

    // ==================================================================
    // 5. 会议纪要（富文本 + 照片 + PPT）
    // ==================================================================
    @PutMapping("/{id}/record")
    public Result<Map<String, Object>> saveRecord(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        Map<String, Object> rec = one("SELECT * FROM `meeting_record` WHERE `meeting_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", id, tid());
        String content = s(b.get("content"));
        String photos = toJsonArr(b.get("venuePhotos"));
        String ppt = toJsonArr(b.get("pptFiles"));
        if (rec.isEmpty()) {
            exec("INSERT INTO `meeting_record` (`id`,`tenant_id`,`meeting_id`,`content`,`venue_photos`,`ppt_files`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), id, content, photos, ppt, LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            exec("UPDATE `meeting_record` SET `content`=?,`venue_photos`=?,`ppt_files`=?,`updated_at`=? WHERE `meeting_id`=?",
                    content, photos, ppt, LocalDateTime.now(), id);
        }
        log(id, "record", s(m.get("status")), s(m.get("status")), "录入/更新会议纪要（记录+照片+PPT）");
        return Result.ok(Map.of("ok", true, "hasRecord", !content.isBlank()));
    }
    private String toJsonArr(Object o) {
        if (o == null) return "[]";
        if (o instanceof List<?> li) {
            cn.hutool.json.JSONArray arr = new cn.hutool.json.JSONArray();
            for (Object x : li) arr.add(String.valueOf(x));
            return arr.toString();
        }
        String s = String.valueOf(o).trim();
        if (s.startsWith("[")) return s;
        cn.hutool.json.JSONArray arr = new cn.hutool.json.JSONArray();
        for (String part : s.split("[,\\n]")) { part = part.trim(); if (!part.isBlank()) arr.add(part); }
        return arr.toString();
    }

    // ==================================================================
    // 6. PDF 四段式成品 + 盒三归档
    // ==================================================================
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> meetingPdf(@PathVariable long id) {
        Map<String, Object> m = one("SELECT m.*, h.`name` AS host_name, r.`name` AS recorder_name " +
                "FROM `meeting` m LEFT JOIN `person` h ON h.`id`=m.`host_id` LEFT JOIN `person` r ON r.`id`=m.`recorder_id` " +
                "WHERE m.`id`=? AND m.`tenant_id`=? AND m.`deleted`=0", id, tid());
        Map<String, Object> rec = one("SELECT * FROM `meeting_record` WHERE `meeting_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", id, tid());
        List<Map<String, Object>> atts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT a.*, p.`name`, p.`person_type` FROM `meeting_attendee` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`user_id` WHERE a.`meeting_id`=? AND a.`tenant_id`=? " +
                "AND a.`deleted`=0 ORDER BY a.`id`", id, tid())) {
            Map<String, Object> mm = new LinkedHashMap<>(r);
            mm.put("name", s(r.get("name"))); mm.put("post", s(r.get("person_type")));
            atts.add(mm);
        }
        atts.sort((a, b) -> Integer.compare(postRank(s(a.get("post"))), postRank(s(b.get("post")))));

        StringBuilder body = new StringBuilder();
        // ① 会议通知
        body.append("<h2>一、会议通知</h2><table>")
                .append("<tr><td class='n'>会议名称</td><td colspan='3'>").append(esc(s(m.get("title")))).append("</td></tr>")
                .append("<tr><td class='n'>会议类型</td><td>").append(esc(typeName(s(m.get("meeting_type")))))
                .append("</td><td class='n'>召开时间</td><td>").append(esc(date16(m.get("start_time")))).append("</td></tr>")
                .append("<tr><td class='n'>会议地点</td><td colspan='3'>")
                .append("online".equals(s(m.get("mode"))) ? esc("线上（" + (s(m.get("meeting_no")).isBlank()?"腾讯会议":s(m.get("meeting_no"))) + (s(m.get("link")).isBlank()?"":"/"+s(m.get("link"))) + "）")
                        : esc(s(m.get("location")).isBlank() ? "公司会议室" : s(m.get("location")))).append("</td></tr>")
                .append("<tr><td class='n'>会议内容</td><td colspan='3'>").append(esc(s(m.get("topic")).isBlank() ? "安全宣贯、工作总结与部署" : s(m.get("topic")))).append("</td></tr>")
                .append("<tr><td class='n'>参会人员</td><td colspan='3'>").append(esc(attendeeNames(atts))).append("</td></tr>")
                .append("</table>");
        // ② 签到表
        body.append("<h2>二、会议签到表</h2><table><tr><th>序号</th><th>姓名</th><th>岗位</th><th>签名</th><th>签到时间</th><th>状态</th></tr>");
        int n = 0;
        for (Map<String, Object> a : atts) {
            n++;
            body.append("<tr><td>").append(n).append("</td><td>").append(esc(s(a.get("name"))))
                    .append("</td><td>").append(esc(s(a.get("post")))).append("</td><td>")
                    .append(signImg(s(a.get("sign_image")))).append("</td><td>").append(esc(date16(a.get("sign_time"))))
                    .append("</td><td>").append(esc(signName(s(a.get("sign_status"))))).append("</td></tr>");
        }
        if (atts.isEmpty()) body.append("<tr><td colspan='6' style='text-align:center;color:#999'>暂无参会人</td></tr>");
        body.append("</table>");
        // ③ 会议记录
        body.append("<h2>三、会议记录</h2><table>")
                .append("<tr><td class='n'>会议名称</td><td colspan='3'>").append(esc(s(m.get("title")))).append("</td></tr>")
                .append("<tr><td class='n'>会议地点</td><td>").append(esc("online".equals(s(m.get("mode")))?"线上":(s(m.get("location")).isBlank()?"公司会议室":s(m.get("location")))))
                .append("</td><td class='n'>会议时间</td><td>").append(esc(date16(m.get("start_time")))).append("</td></tr>")
                .append("<tr><td class='n'>主持人</td><td>").append(esc(s(m.get("host_name"))))
                .append("</td><td class='n'>记录人</td><td>").append(esc(s(m.get("recorder_name")))).append("</td></tr>")
                .append("<tr><td class='n'>会议议题</td><td colspan='3'>").append(esc(s(m.get("topic")).isBlank()?"—":s(m.get("topic")))).append("</td></tr>")
                .append("</table>");
        body.append("<div class='record'>").append(esc(s(rec.get("content"))).replace("\n", "<br/>")).append("</div>");
        // ④ 会场照片
        body.append("<h2>四、会场照片</h2>");
        List<String> photos = parseJsonArr(s(rec.get("venue_photos")));
        if (photos.isEmpty()) body.append("<p class='ph'>（未上传会场照片）</p>");
        else for (String p : photos) body.append("<p class='ph'>").append(esc(p)).append("</p>");

        body.insert(0, "<h1>" + esc(company()) + " · 安全会议记录（四段式）</h1>" +
                "<p class='tip'>" + esc(typeName(s(m.get("meeting_type")))) + " · 归档盒：三、安全生产会议</p>");
        body.append("<p class='foot'>本表由系统按会议留痕实时生成 · 通知/签到/记录/照片四段齐全 · ").append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("安全会议记录", body.toString())),
                "meeting_" + id + ".pdf");
    }

    private String attendeeNames(List<Map<String, Object>> atts) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : atts) { if (sb.length() > 0) sb.append("、"); sb.append(s(a.get("name"))); }
        return sb.toString();
    }
    private String signImg(String img) {
        if (img == null || img.isBlank()) return "<span style='color:#999'>—</span>";
        if (img.startsWith("http") || img.startsWith("data:")) return "<img src='" + esc(img) + "' style='height:34px'/>";
        return esc(img);
    }

    @GetMapping(value = "/ledger/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> ledgerPdf() {
        List<Map<String, Object>> rows = records(null, null, null);
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(company())).append(" · 安全会议管理台账</h1>");
        body.append("<p class='tip'>共 ").append(rows.size()).append(" 场会议 · ").append(LocalDate.now()).append("</p>");
        body.append("<table><tr><th>序号</th><th>会议名称</th><th>类型</th><th>召开时间</th><th>主持人</th><th>参会/签到</th><th>状态</th></tr>");
        int n = 0;
        for (Map<String, Object> r : rows) {
            n++;
            body.append("<tr><td>").append(n).append("</td><td>").append(esc(s(r.get("title"))))
                    .append("</td><td>").append(esc(s(r.get("typeName"))))
                    .append("</td><td>").append(esc(date10(r.get("start_time"))))
                    .append("</td><td>").append(esc(s(r.get("host_name"))))
                    .append("</td><td>").append(l(r.get("sign_total"))).append("/").append(l(r.get("attendee_total")))
                    .append("</td><td>").append(esc(s(r.get("statusName")))).append("</td></tr>");
        }
        if (rows.isEmpty()) body.append("<tr><td colspan='7' style='text-align:center;color:#999'>暂无会议记录</td></tr>");
        body.append("</table><p class='foot'>").append(esc(company())).append(" · 系统自动生成 · ").append(LocalDate.now()).append("</p>");
        return pdfResponse(pdfService.render(page("安全会议管理台账", body.toString())), "meeting_ledger.pdf");
    }

    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:30px;color:#222;font-size:12px}" +
                "h1{text-align:center;font-size:19px;letter-spacing:2px;margin:0 0 8px}" +
                "h2{font-size:15px;margin:18px 0 6px;border-left:3px solid #444;padding-left:8px}" +
                "table{width:100%;border-collapse:collapse;margin-top:6px}" +
                "td,th{border:1px solid #666;padding:6px;font-size:11px;text-align:left;vertical-align:top}" +
                "th{background:#f2f2f2;text-align:center}" +
                ".n{background:#f7f7f7;text-align:center;width:96px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".record{margin-top:8px;line-height:1.9;white-space:pre-wrap}" +
                ".ph{font-size:11px;color:#444}" +
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

    /** 一键归档（M7）：把单场会议四段式 PDF 按 meeting_type 归入盒三对应子项，幂等覆盖 */
    @PostMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestBody Map<String, Object> b) {
        long meetingId = l(b.get("meetingId"));
        if (meetingId == 0L) return Result.ok(fail("请指定归档会议 meetingId"));
        Map<String, Object> m = one("SELECT * FROM `meeting` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", meetingId, tid());
        if (m.isEmpty()) return Result.ok(fail("会议不存在"));
        boolean danger = count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? " +
                "AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
        String box = danger ? "DANGER_BOX3" : "GENERAL_BOX3";
        String sub = "leader".equals(s(m.get("meeting_type"))) ? "S01" : "S02";
        Map<String, Object> parent = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long parentId = l(parent.get("id"));
        Map<String, Object> subC = one("SELECT `id` FROM `archive_catalog` WHERE `parent_id`=? AND `code` LIKE ? " +
                "AND `level`=2 LIMIT 1", parentId, box + "_" + sub + "%");
        long catalogId = l(subC.get("id")) == 0L ? parentId : l(subC.get("id"));

        String title = s(m.get("title"));
        byte[] pdf = pdfService.render(page("安全会议记录", fourSegment(m, meetingId)));

        String key = tid() + "/meeting/" + meetingId + "_" + LocalDate.now() + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? " +
                "AND `biz_module`='meeting' AND `biz_id`=? AND `deleted`=0 LIMIT 1", tid(), meetingId);
        long itemId;
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`," +
                            "`biz_module`,`biz_id`,`title`,`occur_date`,`archive_year`,`status`," +
                            "`created_at`,`created_by`,`deleted`) VALUES (?,?,?,2,1,'meeting',?,?,?,?,1,?,?,0)",
                    itemId, tid(), catalogId, itemId, title, LocalDate.now(),
                    LocalDate.now().getYear(), LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
            exec("UPDATE `archive_item` SET `catalog_id`=?,`title`=?,`occur_date`=? WHERE `id`=?", catalogId, title, LocalDate.now(), itemId);
        }
        exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=?", itemId, tid());
        long pdfId = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`," +
                        "`file_path`,`file_size`,`page_count`,`template_code`,`gen_time`,`created_at`," +
                        "`created_by`,`deleted`) VALUES (?,?,?,?,?,?,?,1,?,?,?,?,0)",
                pdfId, tid(), itemId, (short) LocalDate.now().getYear(), title + ".pdf",
                key, pdf.length, "MEETING_RECORD", LocalDateTime.now(), LocalDateTime.now(),
                TenantContextHolder.userId());
        exec("UPDATE `meeting` SET `status`='archived',`archive_time`=? WHERE `id`=?", LocalDateTime.now(), meetingId);
        log(meetingId, "archive", s(m.get("status")), "archived", "归档到" + box + (sub.equals("S01") ? "（领导小组会议）" : "（月度会议）"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true); res.put("box", box); res.put("sub", sub);
        res.put("itemId", String.valueOf(itemId)); res.put("pdfId", String.valueOf(pdfId));
        res.put("size", pdf.length); res.put("title", title);
        return Result.ok(res);
    }

    /** 与 PDF 共用四段式 HTML（归档复用） */
    private String fourSegment(Map<String, Object> m, long meetingId) {
        Map<String, Object> rec = one("SELECT * FROM `meeting_record` WHERE `meeting_id`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1", meetingId, tid());
        List<Map<String, Object>> atts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT a.*, p.`name`, p.`person_type` FROM `meeting_attendee` a " +
                "LEFT JOIN `person` p ON p.`id`=a.`user_id` WHERE a.`meeting_id`=? AND a.`tenant_id`=? " +
                "AND a.`deleted`=0 ORDER BY a.`id`", meetingId, tid())) {
            Map<String, Object> mm = new LinkedHashMap<>(r); mm.put("name", s(r.get("name"))); mm.put("post", s(r.get("person_type")));
            atts.add(mm);
        }
        atts.sort((a, b) -> Integer.compare(postRank(s(a.get("post"))), postRank(s(b.get("post")))));
        StringBuilder body = new StringBuilder();
        body.append("<h2>一、会议通知</h2><table>")
                .append("<tr><td class='n'>会议名称</td><td colspan='3'>").append(esc(s(m.get("title")))).append("</td></tr>")
                .append("<tr><td class='n'>会议类型</td><td>").append(esc(typeName(s(m.get("meeting_type")))))
                .append("</td><td class='n'>召开时间</td><td>").append(esc(date16(m.get("start_time")))).append("</td></tr>")
                .append("<tr><td class='n'>会议地点</td><td colspan='3'>")
                .append("online".equals(s(m.get("mode"))) ? esc("线上（" + (s(m.get("meeting_no")).isBlank()?"腾讯会议":s(m.get("meeting_no"))) + "）") : esc(s(m.get("location")).isBlank()?"公司会议室":s(m.get("location"))))
                .append("</td></tr><tr><td class='n'>参会人员</td><td colspan='3'>").append(esc(attendeeNames(atts))).append("</td></tr></table>");
        body.append("<h2>二、会议签到表</h2><table><tr><th>序号</th><th>姓名</th><th>岗位</th><th>签名</th><th>签到时间</th><th>状态</th></tr>");
        int n = 0;
        for (Map<String, Object> a : atts) {
            n++;
            body.append("<tr><td>").append(n).append("</td><td>").append(esc(s(a.get("name"))))
                    .append("</td><td>").append(esc(s(a.get("post")))).append("</td><td>").append(signImg(s(a.get("sign_image"))))
                    .append("</td><td>").append(esc(date16(a.get("sign_time")))).append("</td><td>").append(esc(signName(s(a.get("sign_status"))))).append("</td></tr>");
        }
        body.append("</table><h2>三、会议记录</h2><table>")
                .append("<tr><td class='n'>会议名称</td><td colspan='3'>").append(esc(s(m.get("title")))).append("</td></tr>")
                .append("<tr><td class='n'>主持人</td><td>").append(esc(s(m.get("host_name"))))
                .append("</td><td class='n'>记录人</td><td>").append(esc(s(m.get("recorder_name")))).append("</td></tr></table>")
                .append("<div class='record'>").append(esc(s(rec.get("content"))).replace("\n", "<br/>")).append("</div>")
                .append("<h2>四、会场照片</h2>");
        List<String> photos = parseJsonArr(s(rec.get("venue_photos")));
        body.append(photos.isEmpty() ? "<p class='ph'>（未上传会场照片）</p>" : "<p class='ph'>" + esc(String.join("、", photos)) + "</p>");
        return body.toString();
    }

    private List<Map<String, Object>> archives() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`occur_date`,`status` FROM `archive_item` " +
                "WHERE `tenant_id`=? AND `biz_module`='meeting' AND `deleted`=0 ORDER BY `id` DESC LIMIT 20", tid())) {
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
        out.put("persons", persons);
        out.put("meetingTypes", MEETING_TYPES.stream().map(t -> t[0]).toList());
        return Result.ok(out);
    }

    private List<Map<String, Object>> referencesData() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] refs = {
                {"person", "B1 人员基础信息", "主持人/记录人/参会人", "SELECT COUNT(*) FROM `meeting_attendee` WHERE `tenant_id`=? AND `user_id` IS NOT NULL AND `deleted`=0"},
                {"archive", "B7 电子档案柜", "盒三安全生产会议归档", "SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='meeting' AND `deleted`=0"},
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

    /** 小程序预留：未归档/即将召开会议预警聚合（不新增小程序页面） */
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`title`,`meeting_type`,`start_time`,`status` FROM `meeting` " +
                "WHERE `tenant_id`=? AND `deleted`=0 AND `status` IN ('published','ongoing','finished') " +
                "ORDER BY `start_time` ASC LIMIT 20", tid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("meetingId", String.valueOf(r.get("id")));
            m.put("title", s(r.get("title")));
            m.put("type", s(r.get("meeting_type")));
            m.put("status", s(r.get("status")));
            m.put("text", "archived".equals(s(r.get("status"))) ? "已归档会议" : "待归档会议，请尽快录入纪要并归档");
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list); out.put("total", list.size());
        return Result.ok(out);
    }

    /** 小程序预留：我参与的会议（按参会人聚合，与 PC 同源数据） */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Long userId) {
        StringBuilder sql = new StringBuilder("SELECT m.`id`,m.`title`,m.`meeting_type`,m.`start_time`,m.`status`," +
                "a.`sign_status` FROM `meeting` m JOIN `meeting_attendee` a ON a.`meeting_id`=m.`id` AND a.`deleted`=0 " +
                "WHERE m.`tenant_id`=? AND m.`deleted`=0");
        List<Object> args = new ArrayList<>(); args.add(tid());
        if (userId != null) { sql.append(" AND a.`user_id`=?"); args.add(userId); }
        sql.append(" ORDER BY m.`start_time` DESC LIMIT 30");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("meetingId", String.valueOf(r.get("id")));
            m.put("typeName", typeName(s(r.get("meeting_type"))));
            m.put("statusName", statusName(s(r.get("status"))));
            m.put("signStatusName", signName(s(r.get("sign_status"))));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", LocalDate.now().toString());
        out.put("tasks", list); out.put("total", list.size());
        out.put("note", "小程序端仅预留数据接口，具体会议签到页面后续统一建设");
        return Result.ok(out);
    }
}
