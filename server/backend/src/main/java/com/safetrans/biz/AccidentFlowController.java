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
 * C3 事故管理 + 交通违法 · 完整流程闭环（详细设计 16 + 20）。
 *
 * <p>事故管理：系统内只做「事故登记」，登记后自动生成标准格式事故快报表（带盖章），
 * 按年度自动汇总统计；事故处理（四不放过等）走人工增值服务（联系平台，收费）。
 *
 * <p>交通违法：登记交通违法/违规，联动 14 培训教育的违规类型字典，一键推送靶向教育。
 *
 * <p>档案盒：GENERAL_BOX10=盒十·事故档案（普货）/ DANGER_BOX11=盒十一·事故档案（危货）。
 */
@RestController
@RequestMapping("/api/flow/accident")
public class AccidentFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public AccidentFlowController(JdbcTemplate jdbc, PdfService pdfService,
                                 StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    // 事故状态机
    private static final Map<String, String> ACC_STATUS = new LinkedHashMap<>();
    static { ACC_STATUS.put("reported", "已登记"); ACC_STATUS.put("processing", "处理中"); ACC_STATUS.put("closed", "已结案"); }
    // 增值服务状态机
    private static final Map<String, String> SVC_STATUS = new LinkedHashMap<>();
    static { SVC_STATUS.put("pending", "待受理"); SVC_STATUS.put("processing", "服务中"); SVC_STATUS.put("completed", "已完成"); }
    // 靶向教育落实状态
    private static final Map<String, String> EDU_STATUS = new LinkedHashMap<>();
    static { EDU_STATUS.put("none", "未推送"); EDU_STATUS.put("pushed", "已推送"); EDU_STATUS.put("learned", "已学习"); EDU_STATUS.put("filed", "已归档"); }

    private static final Map<String, String> ACC_TYPE = new LinkedHashMap<>();
    static { ACC_TYPE.put("collision", "碰撞"); ACC_TYPE.put("rear_end", "追尾"); ACC_TYPE.put("scratch", "刮擦"); ACC_TYPE.put("rollover", "侧翻"); ACC_TYPE.put("fire", "起火"); ACC_TYPE.put("other", "其他"); }
    private static final Map<String, String> SEVERITY = new LinkedHashMap<>();
    static { SEVERITY.put("minor", "轻微"); SEVERITY.put("general", "一般"); SEVERITY.put("major", "较大"); SEVERITY.put("serious", "重大"); SEVERITY.put("catastrophic", "特别重大"); }

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
    private String date16(Object o) { String x = s(o).replace('T', ' '); return x.length() >= 16 ? x.substring(0, 16) : x; }
    private boolean isDanger() {
        return count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
    }
    private Map<String, Object> fail(String msg) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m; }
    private String statusName(Map<String, String> map, String code) { return map.getOrDefault(code, code == null ? "" : code); }
    private String operator() { TenantContextHolder.Context c = TenantContextHolder.get(); return c == null ? "" : s(c.getUsername()); }

    private void log(String bizType, long bizId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `accident_flow_log` (`id`,`tenant_id`,`biz_type`,`biz_id`,`action`," +
                            "`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), bizType, bizId, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
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
                ".n{background:#f7f7f7;text-align:center;width:120px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".ph{font-size:11px;color:#444;line-height:1.8}" +
                ".seal{text-align:right;margin-top:10px;font-size:12px;color:#333}" +
                ".seal-box img{max-height:80px}" +
                "</style></head><body>" + body + "</body></html>";
    }
    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    // ==================================================================
    // 1. 概览
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("isDanger", isDanger());
        out.put("accStatus", ACC_STATUS);
        out.put("svcStatus", SVC_STATUS);
        out.put("eduStatus", EDU_STATUS);
        out.put("accType", ACC_TYPE);
        out.put("severity", SEVERITY);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("accidents", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("closed", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND `status`='closed' AND `deleted`=0", tid()));
        stats.put("reports", count("SELECT COUNT(*) FROM `accident_quick_report` WHERE `tenant_id`=?", tid()));
        stats.put("violations", count("SELECT COUNT(*) FROM `traffic_violation` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("eduPushed", count("SELECT COUNT(*) FROM `traffic_violation` WHERE `tenant_id`=? AND `edu_status` IN ('pushed','learned','filed') AND `deleted`=0", tid()));
        stats.put("services", count("SELECT COUNT(*) FROM `accident_service` WHERE `tenant_id`=?", tid()));
        out.put("stats", stats);

        out.put("records", records(null));
        out.put("violations", violations(null));
        out.put("services", services());
        out.put("summaries", summaries());
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 事故登记 + 自动快报
    // ==================================================================
    @PostMapping("/record")
    public Result<Map<String, Object>> register(@RequestBody Map<String, Object> b) {
        if (s(b.get("accidentTime")).isBlank() || s(b.get("location")).isBlank())
            return Result.ok(fail("事故时间、地点为必填"));
        long id = IdUtil.getSnowflakeNextId();
        String accNo = "SG" + LocalDate.now().getYear() + "-" + String.format("%06d", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND `deleted`=0", tid()) + 1);
        exec("INSERT INTO `accident_record` (`id`,`tenant_id`,`accident_no`,`accident_time`,`location`,`vehicle_id`,`vehicle_plate`," +
                        "`accident_type`,`involve_person`,`description`,`loss_amount`,`responsibility`,`severity`,`status`,`source`," +
                        "`photos`,`reported_by`,`reported_at`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tid(), accNo, safeDateTime(b.get("accidentTime")), s(b.get("location")), l(b.get("vehicleId")), s(b.get("vehiclePlate")),
                s(b.get("accidentType")), s(b.get("involvePerson")), s(b.get("description")),
                s(b.get("lossAmount")).isBlank() ? null : Double.parseDouble(s(b.get("lossAmount"))),
                s(b.get("responsibility")), s(b.get("severity")), "reported", s(b.get("source")).isBlank() ? "pc" : s(b.get("source")),
                s(b.get("photos")), TenantContextHolder.userId(), LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        log("accident", id, "register", "", "reported", "登记事故：" + accNo);
        // 登记即生成事故快报表
        long pdfId = genQuickReport(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("accidentId", String.valueOf(id)); out.put("accidentNo", accNo); out.put("reportPdfId", pdfId > 0 ? String.valueOf(pdfId) : "");
        return Result.ok(out);
    }

    @GetMapping("/record/list")
    public Result<List<Map<String, Object>>> recordListApi(@RequestParam(required = false) String keyword) { return Result.ok(records(keyword)); }

    private List<Map<String, Object>> records(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM `accident_record` WHERE `tenant_id`=? AND `deleted`=0");
        List<Object> args = new ArrayList<>(); args.add(tid());
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (`accident_no` LIKE ? OR `location` LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY `accident_time` DESC LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(ACC_STATUS, s(r.get("status"))));
            m.put("typeName", statusName(ACC_TYPE, s(r.get("accident_type"))));
            m.put("severityName", statusName(SEVERITY, s(r.get("severity"))));
            m.put("hasReport", count("SELECT COUNT(*) FROM `accident_quick_report` WHERE `accident_id`=?", l(r.get("id"))) > 0);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/record/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `accident_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("事故不存在"));
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("id", String.valueOf(m.get("id")));
        out.put("statusName", statusName(ACC_STATUS, s(m.get("status"))));
        out.put("typeName", statusName(ACC_TYPE, s(m.get("accident_type"))));
        out.put("severityName", statusName(SEVERITY, s(m.get("severity"))));
        Map<String, Object> qr = one("SELECT * FROM `accident_quick_report` WHERE `accident_id`=? LIMIT 1", id);
        out.put("quickReport", qr.isEmpty() ? null : Map.of("reportNo", s(qr.get("report_no")), "reportContent", s(qr.get("report_content")), "pdfId", String.valueOf(l(qr.get("pdf_id"))), "reportTime", date16(qr.get("report_time"))));
        return Result.ok(out);
    }

    /** 重新生成事故快报表 */
    @PostMapping("/record/{id}/quick-report")
    public Result<Map<String, Object>> regenQuickReport(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `accident_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("事故不存在"));
        long pdfId = genQuickReport(id);
        return Result.ok(Map.of("ok", true, "pdfId", String.valueOf(pdfId)));
    }

    @PostMapping("/record/{id}/status")
    public Result<Map<String, Object>> changeStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `accident_record` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("事故不存在"));
        String st = s(b.get("status"));
        if (!ACC_STATUS.containsKey(st)) return Result.ok(fail("状态值不合法"));
        exec("UPDATE `accident_record` SET `status`=?,`updated_at`=? WHERE `id`=?", st, LocalDateTime.now(), id);
        log("accident", id, "status", s(m.get("status")), st, "状态变更");
        return Result.ok(Map.of("ok", true, "status", st, "statusName", statusName(ACC_STATUS, st)));
    }

    /** 生成事故快报表（标准格式 + 盖章），返回 pdf_id */
    private long genQuickReport(long accidentId) {
        Map<String, Object> m = one("SELECT * FROM `accident_record` WHERE `id`=?", accidentId);
        if (m.isEmpty()) return 0L;
        String content = quickReportHtml(m);
        byte[] pdf = pdfService.render(page("事故快报表", content));
        String key = tid() + "/accident/report_" + accidentId + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        String reportNo = "KB" + LocalDate.now().getYear() + "-" + String.format("%06d", accidentId % 1000000);
        // 删除旧快报
        for (Map<String, Object> old : q("SELECT `id`,`pdf_id` FROM `accident_quick_report` WHERE `accident_id`=?", accidentId)) {
            if (l(old.get("pdf_id")) > 0) exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `id`=?", l(old.get("pdf_id")));
            exec("DELETE FROM `accident_quick_report` WHERE `id`=?", l(old.get("id")));
        }
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,0,?,?,?,?,1,'ACCIDENT_REPORT',?,?,?,?,0)",
                pid, tid(), (short) LocalDate.now().getYear(), reportNo + ".pdf", key, pdf.length, null, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        long qid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `accident_quick_report` (`id`,`tenant_id`,`accident_id`,`report_content`,`report_no`,`report_time`,`pdf_id`,`created_at`) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                qid, tid(), accidentId, content, reportNo, LocalDateTime.now(), pid, LocalDateTime.now());
        return pid;
    }

    private String quickReportHtml(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(esc(company())).append(" · 事故快报表</h1>");
        b.append("<p class='tip'>（系统自动生成 · 标准格式）</p>");
        b.append("<table>");
        b.append(row2("事故编号", s(m.get("accident_no")), "发生时间", date16(m.get("accident_time"))));
        b.append(row2("事故地点", s(m.get("location")), "事故车辆", s(m.get("vehicle_plate"))));
        b.append(row2("事故类型", statusName(ACC_TYPE, s(m.get("accident_type"))), "事故等级", statusName(SEVERITY, s(m.get("severity")))));
        b.append(row2("直接经济损失", (s(m.get("loss_amount")).isBlank() ? "—" : s(m.get("loss_amount")) + " 元"), "责任初判", s(m.get("responsibility"))));
        b.append("</table>");
        b.append("<h3>涉及人员</h3><p class='ph'>").append(esc(s(m.get("involve_person"))).isBlank() ? "（未填写）" : esc(s(m.get("involve_person")))).append("</p>");
        b.append("<h3>事故经过</h3><p class='ph'>").append(esc(s(m.get("description"))).isBlank() ? "（未填写）" : esc(s(m.get("description")))).append("</p>");
        String photos = s(m.get("photos"));
        if (!photos.isBlank()) {
            b.append("<h3>现场照片</h3>");
            for (String p : photos.split(",")) if (!p.isBlank()) b.append("<p class='ph'>· ").append(esc(p)).append("</p>");
        }
        b.append("<p class='ph' style='margin-top:10px'>注：事故处理（四不放过资料、对接交警/保险/主管部门）请通过「联系人工处理」由平台专业服务完成。</p>");
        if (sealService.sealHtml() != null) b.append("<div class='seal'>").append(sealService.sealHtml()).append("</div>");
        b.append("<p class='foot'>生成时间：").append(LocalDateTime.now()).append("</p>");
        return b.toString();
    }

    private String row2(String k1, String v1, String k2, String v2) {
        return "<tr><td class='n'>" + esc(k1) + "</td><td>" + esc(v1) + "</td><td class='n'>" + esc(k2) + "</td><td>" + esc(v2) + "</td></tr>";
    }

    // ==================================================================
    // 3. 年度事故汇总统计表（按年自动聚合）
    // ==================================================================
    @GetMapping("/summary/{year}")
    public Result<Map<String, Object>> summary(@PathVariable int year) { return Result.ok(buildSummary(year)); }

    @PostMapping("/summary/{year}/pdf")
    public Result<Map<String, Object>> summaryPdf(@PathVariable int year) {
        Map<String, Object> sm = buildSummary(year);
        byte[] pdf = pdfService.render(page(year + "年度事故汇总统计表", summaryHtml(sm)));
        String key = tid() + "/accident/summary_" + year + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        // 更新/插入汇总表
        for (Map<String, Object> old : q("SELECT `id`,`pdf_id` FROM `accident_year_summary` WHERE `tenant_id`=? AND `year`=?", tid(), year)) {
            if (l(old.get("pdf_id")) > 0) exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `id`=?", l(old.get("pdf_id")));
            exec("DELETE FROM `accident_year_summary` WHERE `id`=?", l(old.get("id")));
        }
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,0,?,?,?,?,1,'ACCIDENT_SUMMARY',?,?,?,?,0)",
                pid, tid(), (short) year, year + "年度事故汇总统计表.pdf", key, pdf.length, null, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        long sid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `accident_year_summary` (`id`,`tenant_id`,`year`,`total_count`,`total_loss`,`minor_count`,`general_count`,`major_count`,`pdf_id`,`created_at`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                sid, tid(), year, i(sm.get("totalCount")), d(sm.get("totalLoss")), i(sm.get("minorCount")), i(sm.get("generalCount")), i(sm.get("majorCount")), pid, LocalDateTime.now());
        return Result.ok(Map.of("ok", true, "summaryId", String.valueOf(sid), "pdfId", String.valueOf(pid)));
    }

    private List<Map<String, Object>> summaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `accident_year_summary` WHERE `tenant_id`=? ORDER BY `year` DESC", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("pdfId", String.valueOf(l(r.get("pdf_id"))));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> buildSummary(int year) {
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("year", year);
        sm.put("totalCount", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `deleted`=0", tid(), year));
        sm.put("totalLoss", count("SELECT COALESCE(SUM(`loss_amount`),0) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `deleted`=0", tid(), year));
        sm.put("minorCount", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `severity`='minor' AND `deleted`=0", tid(), year));
        sm.put("generalCount", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `severity`='general' AND `deleted`=0", tid(), year));
        sm.put("majorCount", count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `severity` IN ('major','serious','catastrophic') AND `deleted`=0", tid(), year));
        // 按类型统计
        List<Map<String, Object>> byType = new ArrayList<>();
        for (String code : ACC_TYPE.keySet()) {
            long c = count("SELECT COUNT(*) FROM `accident_record` WHERE `tenant_id`=? AND YEAR(`accident_time`)=? AND `accident_type`=? AND `deleted`=0", tid(), year, code);
            if (c > 0) { Map<String, Object> t = new LinkedHashMap<>(); t.put("typeName", ACC_TYPE.get(code)); t.put("count", c); byType.add(t); }
        }
        sm.put("byType", byType);
        return sm;
    }

    private String summaryHtml(Map<String, Object> sm) {
        int year = i(sm.get("year"));
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(esc(company())).append(" · ").append(year).append("年度事故汇总统计表</h1>");
        b.append("<table><tr><th>统计项</th><th>数值</th></tr>");
        b.append("<tr><td class='n'>事故总数</td><td>").append(s(sm.get("totalCount"))).append(" 起</td></tr>");
        b.append("<tr><td class='n'>直接经济损失</td><td>").append(s(sm.get("totalLoss"))).append(" 元</td></tr>");
        b.append("<tr><td class='n'>轻微事故</td><td>").append(s(sm.get("minorCount"))).append(" 起</td></tr>");
        b.append("<tr><td class='n'>一般事故</td><td>").append(s(sm.get("generalCount"))).append(" 起</td></tr>");
        b.append("<tr><td class='n'>较大及以上事故</td><td>").append(s(sm.get("majorCount"))).append(" 起</td></tr>");
        b.append("</table>");
        List<Map<String, Object>> byType = (List<Map<String, Object>>) sm.get("byType");
        if (byType != null && !byType.isEmpty()) {
            b.append("<h3>按事故类型</h3><table><tr><th>事故类型</th><th>起数</th></tr>");
            for (Map<String, Object> t : byType) b.append("<tr><td>").append(esc(s(t.get("typeName")))).append("</td><td>").append(s(t.get("count"))).append("</td></tr>");
            b.append("</table>");
        }
        if (sealService.sealHtml() != null) b.append("<div class='seal'>").append(sealService.sealHtml()).append("</div>");
        b.append("<p class='foot'>统计年度：").append(year).append(" · 生成时间：").append(LocalDateTime.now()).append("</p>");
        return b.toString();
    }

    // ==================================================================
    // 4. 增值服务申请（联系人工处理）
    // ==================================================================
    @PostMapping("/service")
    public Result<Map<String, Object>> applyService(@RequestBody Map<String, Object> b) {
        if (s(b.get("serviceType")).isBlank()) return Result.ok(fail("请选择服务项目"));
        long id = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `accident_service` (`id`,`tenant_id`,`accident_id`,`service_type`,`service_desc`,`contact_name`,`contact_phone`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                id, tid(), l(b.get("accidentId")), s(b.get("serviceType")), s(b.get("serviceDesc")), s(b.get("contactName")), s(b.get("contactPhone")),
                "pending", LocalDateTime.now(), TenantContextHolder.userId());
        return Result.ok(Map.of("ok", true, "serviceId", String.valueOf(id)));
    }

    @GetMapping("/service/list")
    public Result<List<Map<String, Object>>> serviceListApi() { return Result.ok(services()); }

    private List<Map<String, Object>> services() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `accident_service` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC LIMIT 50", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(SVC_STATUS, s(r.get("status"))));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/service/{id}/status")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> svcStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `accident_service` WHERE `id`=? AND `tenant_id`=?", id, tid());
        if (m.isEmpty()) return Result.ok(fail("服务申请不存在"));
        String st = s(b.get("status"));
        if (!SVC_STATUS.containsKey(st)) return Result.ok(fail("状态值不合法"));
        exec("UPDATE `accident_service` SET `status`=?,`updated_at`=? WHERE `id`=?", st, LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true, "status", st, "statusName", statusName(SVC_STATUS, st)));
    }

    // ==================================================================
    // 5. 交通违法登记 + 联动靶向教育
    // ==================================================================
    @PostMapping("/violation")
    public Result<Map<String, Object>> addViolation(@RequestBody Map<String, Object> b) {
        if (s(b.get("violationTime")).isBlank()) return Result.ok(fail("违法时间必填"));
        long id = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `traffic_violation` (`id`,`tenant_id`,`vehicle_id`,`vehicle_plate`,`driver_id`,`driver_name`," +
                        "`violation_type`,`violation_time`,`location`,`punish_no`,`fine_amount`,`deduct_points`,`description`,`edu_status`,`source`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tid(), l(b.get("vehicleId")), s(b.get("vehiclePlate")), l(b.get("driverId")), s(b.get("driverName")),
                s(b.get("violationType")), safeDateTime(b.get("violationTime")), s(b.get("location")), s(b.get("punishNo")),
                s(b.get("fineAmount")).isBlank() ? null : Double.parseDouble(s(b.get("fineAmount"))), i(b.get("deductPoints")),
                s(b.get("description")), "none", s(b.get("source")).isBlank() ? "pc" : s(b.get("source")), LocalDateTime.now(), TenantContextHolder.userId());
        return Result.ok(Map.of("ok", true, "violationId", String.valueOf(id)));
    }

    @GetMapping("/violation/list")
    public Result<List<Map<String, Object>>> violationListApi(@RequestParam(required = false) String keyword) { return Result.ok(violations(keyword)); }

    private List<Map<String, Object>> violations(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM `traffic_violation` WHERE `tenant_id`=? AND `deleted`=0");
        List<Object> args = new ArrayList<>(); args.add(tid());
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (`vehicle_plate` LIKE ? OR `driver_name` LIKE ? OR `violation_type` LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY `violation_time` DESC LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("eduStatusName", statusName(EDU_STATUS, s(r.get("edu_status"))));
            out.add(m);
        }
        return out;
    }

    /** 联动推送靶向教育（创建 14 培训教育的 targeted 培训计划） */
    @PostMapping("/violation/{id}/edu")
    public Result<Map<String, Object>> pushEdu(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `traffic_violation` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("违法记录不存在"));
        if (!"none".equals(s(m.get("edu_status")))) return Result.ok(fail("已推送靶向教育"));
        String vtype = s(m.get("violation_type"));
        String vname = s(one("SELECT `violation_name` FROM `violation_dict` WHERE `violation_code`=? AND `deleted`=0 LIMIT 1", vtype).get("violation_name"));
        if (vname.isBlank()) vname = vtype;
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `training_plan` (`id`,`tenant_id`,`plan_name`,`plan_type`,`violation_type`,`cargo_type`,`year`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                pid, tid(), vname + "·靶向教育", "targeted", vtype, isDanger() ? "danger" : "general", LocalDate.now().getYear(), "draft", LocalDateTime.now(), TenantContextHolder.userId());
        exec("UPDATE `traffic_violation` SET `edu_status`='pushed',`edu_plan_id`=?,`updated_at`=? WHERE `id`=?", pid, LocalDateTime.now(), id);
        log("violation", id, "edu", "none", "pushed", "推送靶向教育：" + vname);
        return Result.ok(Map.of("ok", true, "planId", String.valueOf(pid), "planName", vname + "·靶向教育"));
    }

    @PostMapping("/violation/{id}/edu-status")
    public Result<Map<String, Object>> eduStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `traffic_violation` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("违法记录不存在"));
        String st = s(b.get("eduStatus"));
        if (!EDU_STATUS.containsKey(st) || "none".equals(st)) return Result.ok(fail("状态值不合法"));
        exec("UPDATE `traffic_violation` SET `edu_status`=?,`updated_at`=? WHERE `id`=?", st, LocalDateTime.now(), id);
        return Result.ok(Map.of("ok", true, "eduStatus", st, "eduStatusName", statusName(EDU_STATUS, st)));
    }

    // ==================================================================
    // 6. 一键大归档：盒十（普货）/ 盒十一（危货）· 事故档案
    // ==================================================================
    @PostMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestBody Map<String, Object> b) {
        String box = isDanger() ? "DANGER_BOX11" : "GENERAL_BOX10";
        Map<String, Object> cat = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long catalogId = l(cat.get("id"));
        if (catalogId == 0L) return Result.ok(fail("未找到归档目录：" + box));

        String title = (isDanger() ? "危货·" : "") + company() + "·事故档案（整盒归档）";
        byte[] pdf = pdfService.render(page("事故档案整盒归档", bigArchiveHtml()));
        String key = tid() + "/accident/box_" + LocalDate.now() + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='accident' AND `biz_id`=? AND `deleted`=0 LIMIT 1", tid(), catalogId);
        long itemId;
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`biz_module`,`biz_id`," +
                            "`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,2,1,'accident',?,?,?,?,1,?,?,0)",
                    itemId, tid(), catalogId, itemId, title, LocalDate.now(), LocalDate.now().getYear(), LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
            exec("UPDATE `archive_item` SET `catalog_id`=?,`title`=?,`occur_date`=? WHERE `id`=?", catalogId, title, LocalDate.now(), itemId);
        }
        exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=?", itemId, tid());
        long pdfId = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,1,'ACCIDENT_BOX',NULL,?,?,?,0)",
                pdfId, tid(), itemId, (short) LocalDate.now().getYear(), title + ".pdf", key, pdf.length,
                LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        log("archive", itemId, "archive", "", "archived", "整盒归档到" + box);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true); res.put("box", box); res.put("catalogId", String.valueOf(catalogId));
        res.put("itemId", String.valueOf(itemId)); res.put("pdfId", String.valueOf(pdfId)); res.put("size", pdf.length); res.put("title", title);
        return Result.ok(res);
    }

    private String bigArchiveHtml() {
        StringBuilder body = new StringBuilder();
        // 1. 事故快报表
        body.append("<h2>一、事故快报表</h2>");
        List<Map<String, Object>> recs = q("SELECT * FROM `accident_record` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `accident_time` DESC", tid());
        if (recs.isEmpty()) body.append("<p class='ph'>（暂无事故登记）</p>");
        for (Map<String, Object> r : recs) {
            Map<String, Object> qr = one("SELECT * FROM `accident_quick_report` WHERE `accident_id`=? LIMIT 1", l(r.get("id")));
            body.append("<h3>").append(esc(s(r.get("accident_no")))).append(" · ").append(esc(s(r.get("location")))).append("（").append(statusName(ACC_STATUS, s(r.get("status")))).append("）</h3>");
            if (!qr.isEmpty()) body.append("<div class='record'>").append(s(qr.get("report_content"))).append("</div>");
            else body.append("<p class='ph'>（快报表未生成）</p>");
        }
        // 2. 年度事故汇总
        body.append("<h2>二、年度事故汇总统计表</h2>");
        List<Map<String, Object>> sums = q("SELECT * FROM `accident_year_summary` WHERE `tenant_id`=? ORDER BY `year` DESC", tid());
        if (sums.isEmpty()) body.append("<p class='ph'>（暂无年度汇总，可在年度汇总页生成）</p>");
        for (Map<String, Object> sm : sums) {
            body.append("<h3>").append(s(sm.get("year"))).append(" 年度</h3>");
            body.append("<p class='ph'>事故总数 ").append(s(sm.get("total_count"))).append(" 起 · 直接经济损失 ").append(s(sm.get("total_loss"))).append(" 元</p>");
        }
        // 3. 交通违法登记
        body.append("<h2>三、交通违法/违规登记</h2>");
        List<Map<String, Object>> vs = q("SELECT * FROM `traffic_violation` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `violation_time` DESC", tid());
        if (vs.isEmpty()) body.append("<p class='ph'>（暂无交通违法登记）</p>");
        for (Map<String, Object> v : vs) {
            body.append("<p class='ph'>· ").append(date10(v.get("violation_time"))).append(" ")
                    .append(esc(s(v.get("vehicle_plate")))).append(" / ").append(esc(s(v.get("driver_name"))))
                    .append(" / ").append(esc(s(v.get("violation_type"))))
                    .append(" / 教育落实：").append(statusName(EDU_STATUS, s(v.get("edu_status")))).append("</p>");
        }
        // 4. 四不放过资料（占位，联系人工服务）
        body.append("<h2>四、事故四不放过资料</h2>");
        body.append("<p class='ph'>（本项由平台人工增值服务补齐：事故资料整理、四不放过资料制作、处理指导、责任分析。请联系平台人工服务。）</p>");
        // 5. 增值服务申请
        body.append("<h2>五、联系人工处理（增值服务）</h2>");
        List<Map<String, Object>> svs = q("SELECT * FROM `accident_service` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC", tid());
        if (svs.isEmpty()) body.append("<p class='ph'>（暂无服务申请）</p>");
        for (Map<String, Object> sv : svs) body.append("<p class='ph'>· ").append(esc(s(sv.get("service_type")))).append("（").append(statusName(SVC_STATUS, s(sv.get("status")))).append("）</p>");

        if (sealService.sealHtml() != null) body.append("<div class='seal'>").append(sealService.sealHtml()).append("</div>");
        body.append("<p class='foot'>本盒由系统按事故管理留痕实时生成 · 带盖章 · ").append(LocalDate.now()).append("</p>");
        return body.toString();
    }

    // ==================================================================
    // 7. 联动数据源 / 小程序预留
    // ==================================================================
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> persons = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`name` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` LIMIT 100", tid()))
            persons.add(Map.of("id", String.valueOf(r.get("id")), "name", s(r.get("name"))));
        out.put("persons", persons);
        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`plate_no` FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` LIMIT 100", tid()))
            vehicles.add(Map.of("id", String.valueOf(r.get("id")), "plate", s(r.get("plate_no"))));
        out.put("vehicles", vehicles);
        List<Map<String, Object>> vtypes = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `violation_code`,`violation_name` FROM `violation_dict` WHERE `deleted`=0 ORDER BY `id` LIMIT 100", tid()))
            vtypes.add(Map.of("code", s(r.get("violation_code")), "name", s(r.get("violation_name"))));
        out.put("violationTypes", vtypes);
        return Result.ok(out);
    }

    @GetMapping("/mine")
    public Result<Map<String, Object>> mine() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accStatus", ACC_STATUS);
        return Result.ok(out);
    }

    private String safeDateTime(Object o) {
        String x = s(o).replace('T', ' ').trim();
        if (x.isBlank()) return LocalDateTime.now().toString();
        return x.length() > 19 ? x.substring(0, 19) : x;
    }
    private double d(Object o) { if (o == null) return 0.0; try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; } }
}
