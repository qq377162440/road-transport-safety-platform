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
 * C4 考核奖惩 + 诚信考核 · 完整流程闭环（详细设计 19）。
 *
 * <p>年度责任制考核：系统按组织架构（岗位）自动生成考核明细、默认 100 分；
 * 驾驶员联动 14 培训教育靶向违规情况，系统提示、安全员人工酌情减分；
 * 一键提交 → 一键签名（主要负责人 + 安全管理员）→ 一键生成台账（带盖章）；
 * 归档盒一（年度责任制考核记录）+ 沉淀一人一档（考核/诚信/奖惩记录）。
 */
@RestController
@RequestMapping("/api/flow/credit")
public class CreditFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public CreditFlowController(JdbcTemplate jdbc, PdfService pdfService,
                               StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    // 考核状态机
    private static final Map<String, String> ASSESS_STATUS = new LinkedHashMap<>();
    static { ASSESS_STATUS.put("draft", "草稿"); ASSESS_STATUS.put("confirming", "待确认"); ASSESS_STATUS.put("completed", "已公示"); }
    // 等级
    private static final Map<String, String> LEVEL = new LinkedHashMap<>();
    static { LEVEL.put("excellent", "优秀"); LEVEL.put("pass", "合格"); LEVEL.put("fail", "不合格"); }

    // ==================================================================
    // 通用工具
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
    private int year() { return LocalDate.now().getYear(); }
    private boolean isDanger() {
        return count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
    }
    private String statusName(Map<String, String> map, String code) { return map.getOrDefault(code, code == null ? "" : code); }
    private String operator() { TenantContextHolder.Context c = TenantContextHolder.get(); return c == null ? "" : s(c.getUsername()); }

    private void log(String bizType, long bizId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `credit_flow_log` (`id`,`tenant_id`,`biz_type`,`biz_id`,`action`," +
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
                "table{width:100%;border-collapse:collapse;margin-top:6px}" +
                "td,th{border:1px solid #666;padding:6px;font-size:11px;text-align:left;vertical-align:top}" +
                "th{background:#f2f2f2;text-align:center}" +
                ".n{background:#f7f7f7;text-align:center;width:120px}" +
                ".tip{text-align:center;color:#555;font-size:11px;margin:0 0 10px}" +
                ".foot{margin-top:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:8px}" +
                ".seal{text-align:right;margin-top:10px;font-size:12px;color:#333}" +
                ".seal-box img{max-height:80px}" +
                "</style></head><body>" + body + "</body></html>";
    }
    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }
    /** JSON 字符串安全包裹（用于 targets 快照） */
    private String jsonStr(String v) {
        return "\"" + s(v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    // ==================================================================
    // 1. 概览
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("isDanger", isDanger());
        out.put("assessStatus", ASSESS_STATUS);
        out.put("level", LEVEL);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("assesses", count("SELECT COUNT(*) FROM `credit_assess` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("completed", count("SELECT COUNT(*) FROM `credit_assess` WHERE `tenant_id`=? AND `status`='completed' AND `deleted`=0", tid()));
        stats.put("items", count("SELECT COUNT(*) FROM `credit_assess_item` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("persons", count("SELECT COUNT(*) FROM `person` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("posts", count("SELECT COUNT(*) FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0 AND `enabled`=1", tid()));
        out.put("stats", stats);
        out.put("assesses", assesses());
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 创建年度考核（按岗位自动生成明细，默认 100 分）
    // ==================================================================
    @PostMapping("/assess")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> b) {
        int y = b.get("year") == null ? year() : i(b.get("year"));
        // 一年一次：已存在则复用
        Map<String, Object> exist = one("SELECT * FROM `credit_assess` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), y);
        long assessId;
        if (!exist.isEmpty()) {
            assessId = l(exist.get("id"));
        } else {
            assessId = IdUtil.getSnowflakeNextId();
            String name = y + "年度安全生产责任制考核";
            exec("INSERT INTO `credit_assess` (`id`,`tenant_id`,`year`,`assess_name`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,0)",
                    assessId, tid(), y, name, "draft", LocalDateTime.now(), TenantContextHolder.userId());
            log("credit", assessId, "create", "", "draft", "创建年度考核：" + name);
            // 按岗位自动生成考核明细（默认 100 分，快照责任目标）
            int n = 0;
            for (Map<String, Object> p : q("SELECT `id`,`post_name`,`goals`,`duties`,`assess_std` FROM `org_post` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `enabled`=1", tid())) {
                long iid = IdUtil.getSnowflakeNextId();
                String targets = "{\"goals\":" + jsonStr(s(p.get("goals"))) + ",\"duties\":" +
                        jsonStr(s(p.get("duties"))) + ",\"assessStd\":" + jsonStr(s(p.get("assess_std"))) + "}";
                exec("INSERT INTO `credit_assess_item` (`id`,`tenant_id`,`assess_id`,`user_id`,`post_id`,`post_name`," +
                                "`targets`,`score`,`deduction`,`level`,`created_at`,`created_by`,`deleted`) " +
                                "VALUES (?,?,?,0,?,?,?,100,0,'',NOW(),?,0)",
                        iid, tid(), assessId, l(p.get("id")), s(p.get("post_name")), targets, TenantContextHolder.userId());
                n++;
            }
            Map<String, Object> out0 = new LinkedHashMap<>();
            out0.put("ok", true); out0.put("assessId", String.valueOf(assessId));
            out0.put("assessName", name); out0.put("itemCount", n);
            return Result.ok(out0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("assessId", String.valueOf(assessId));
        out.put("assessName", s(exist.get("assess_name"))); out.put("itemCount", items(assessId).size());
        out.put("reused", true);
        return Result.ok(out);
    }

    @GetMapping("/assess/list")
    public Result<List<Map<String, Object>>> assessListApi() { return Result.ok(assesses()); }

    private List<Map<String, Object>> assesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `credit_assess` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `year` DESC", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", statusName(ASSESS_STATUS, s(r.get("status"))));
            m.put("itemCount", count("SELECT COUNT(*) FROM `credit_assess_item` WHERE `assess_id`=? AND `deleted`=0", l(r.get("id"))));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/assess/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("考核不存在"));
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("id", String.valueOf(m.get("id")));
        out.put("statusName", statusName(ASSESS_STATUS, s(m.get("status"))));
        out.put("items", items(id));
        return Result.ok(out);
    }

    @GetMapping("/assess/{id}/items")
    public Result<List<Map<String, Object>>> itemsApi(@PathVariable long id) { return Result.ok(items(id)); }

    private List<Map<String, Object>> items(long assessId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `credit_assess_item` WHERE `assess_id`=? AND `deleted`=0 ORDER BY `id`", assessId)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("levelName", statusName(LEVEL, s(r.get("level"))));
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // 3. 驾驶员违规联动（提示安全员，来源 14 靶向教育/交通违法）
    // ==================================================================
    @GetMapping("/assess/{id}/violations")
    public Result<List<Map<String, Object>>> violationsApi(@PathVariable long id) { return Result.ok(violations()); }

    private List<Map<String, Object>> violations() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT v.*, p.`name` person_name FROM `traffic_violation` v " +
                "LEFT JOIN `person` p ON p.`id`=v.`driver_id` AND p.`deleted`=0 " +
                "WHERE v.`tenant_id`=? AND v.`deleted`=0 AND v.`edu_status`<>'none' " +
                "AND YEAR(v.`violation_time`)=? ORDER BY v.`violation_time` DESC", tid(), year())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("driverName", s(r.get("driver_name")));
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // 4. 酌情减分（系统提示 + 人工确认，仅驾驶员类岗位）
    // ==================================================================
    @PostMapping("/assess/{id}/deduct")
    public Result<Map<String, Object>> deduct(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("考核不存在"));
        long itemId = l(b.get("itemId"));
        Map<String, Object> it = one("SELECT * FROM `credit_assess_item` WHERE `id`=? AND `assess_id`=? AND `deleted`=0", itemId, id);
        if (it.isEmpty()) return Result.ok(fail("考核项不存在"));
        int deduction = i(b.get("deduction"));
        if (deduction < 0) deduction = 0;
        exec("UPDATE `credit_assess_item` SET `deduction`=?,`deduction_note`=? WHERE `id`=?",
                deduction, s(b.get("deductionNote")), itemId);
        return Result.ok(Map.of("ok", true, "itemId", String.valueOf(itemId), "deduction", deduction));
    }

    // ==================================================================
    // 5. 一键签名（主要负责人 + 安全管理员，数据取自企业账号）
    // ==================================================================
    @PostMapping("/assess/{id}/sign")
    public Result<Map<String, Object>> sign(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("考核不存在"));
        Map<String, String> vars = entVars();
        List<Map<String, Object>> signs = new ArrayList<>();
        signs.add(signEntry("主要负责人", vars.get("legalPerson")));
        signs.add(signEntry("安全管理员", vars.get("safetyCharge")));
        for (Map<String, Object> it : items(id)) {
            exec("UPDATE `credit_assess_item` SET `sign_image`=?,`sign_time`=? WHERE `id`=?",
                    toJson(signs), LocalDateTime.now(), l(it.get("id")));
        }
        log("credit", id, "sign", s(m.get("status")), s(m.get("status")), "一键签名：主要负责人/安全管理员");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("signInfo", signs);
        return Result.ok(out);
    }

    private Map<String, Object> signEntry(String role, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role); m.put("name", name == null ? "" : name); m.put("time", LocalDateTime.now().toString());
        return m;
    }

    // ==================================================================
    // 6. 一键提交 + 生成台账 + 归档（盒一 & 一人一档）
    // ==================================================================
    @PostMapping("/assess/{id}/submit")
    public Result<Map<String, Object>> submit(@PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("考核不存在"));
        // 计算等级
        for (Map<String, Object> it : items(id)) {
            int finalScore = i(it.get("score")) - i(it.get("deduction"));
            String lv = finalScore >= 90 ? "excellent" : (finalScore >= 60 ? "pass" : "fail");
            exec("UPDATE `credit_assess_item` SET `level`=? WHERE `id`=?", lv, l(it.get("id")));
        }
        exec("UPDATE `credit_assess` SET `status`='completed',`updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
        log("credit", id, "submit", s(m.get("status")), "completed", "一键提交并公示");
        // 生成台账 + 归档
        long pdfId = generateLedger(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", "completed"); out.put("statusName", "已公示");
        out.put("pdfId", String.valueOf(pdfId));
        return Result.ok(out);
    }

    /** 生成两类台账（责任制考核记录 + 考核奖惩公示），带盖章，返回 pdf_id */
    private long generateLedger(long assessId) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=?", assessId);
        if (m.isEmpty()) return 0L;
        int y = i(m.get("year"));
        List<Map<String, Object>> its = items(assessId);
        String content = ledgerHtml(y, its);
        byte[] pdf = pdfService.render(page(y + "年度安全生产责任制考核台账", content));
        String key = tid() + "/credit/ledger_" + assessId + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        // 覆盖旧的台账 PDF
        for (Map<String, Object> old : q("SELECT `id` FROM `archive_pdf` WHERE `item_id`=? AND `tenant_id`=? AND `template_code`='CREDIT_LEDGER' AND `deleted`=0", assessId, tid())) {
            exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `id`=?", l(old.get("id")));
        }
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,1,'CREDIT_LEDGER',?,?,?,?,0)",
                pid, tid(), assessId, (short) y, y + "年度安全生产责任制考核台账.pdf", key, pdf.length,
                null, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        exec("UPDATE `credit_assess` SET `ledger_pdf`=? WHERE `id`=?", key, assessId);
        // 归档盒一 + 一人一档
        archiveToCabinet(assessId, y, key, pdf.length);
        return pid;
    }

    @PostMapping("/assess/{id}/generate")
    public Result<Map<String, Object>> generate(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `credit_assess` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("考核不存在"));
        long pdfId = generateLedger(id);
        return Result.ok(Map.of("ok", true, "pdfId", String.valueOf(pdfId)));
    }

    private String ledgerHtml(int y, List<Map<String, Object>> its) {
        StringBuilder b = new StringBuilder();
        // 台账一：安全生产责任制考核记录
        b.append("<h2>一、安全生产责任制考核记录（").append(y).append(" 年度）</h2>");
        b.append("<table><tr><th>岗位</th><th>责任目标（考核内容/标准）</th><th>应得分</th><th>减分</th><th>实得分</th><th>等级</th><th>签字</th></tr>");
        for (Map<String, Object> it : its) {
            int finalScore = i(it.get("score")) - i(it.get("deduction"));
            String targets = s(it.get("targets"));
            String goalTxt = targets.length() > 120 ? targets.substring(0, 120) + "…" : targets;
            b.append("<tr><td class='n'>").append(esc(s(it.get("post_name")))).append("</td>")
                    .append("<td>").append(esc(goalTxt)).append("</td>")
                    .append("<td class='n'>").append(s(it.get("score"))).append("</td>")
                    .append("<td class='n'>").append(s(it.get("deduction"))).append("</td>")
                    .append("<td class='n'>").append(finalScore).append("</td>")
                    .append("<td class='n'>").append(esc(statusName(LEVEL, s(it.get("level"))))).append("</td>")
                    .append("<td class='n'>").append(esc(s(it.get("sign_image")).length() > 2 ? "已签" : "—")).append("</td></tr>");
        }
        b.append("</table>");
        // 台账二：安全生产考核奖惩公示
        b.append("<h2>二、安全生产考核奖惩公示（").append(y).append(" 年度）</h2>");
        b.append("<table><tr><th>被考核对象（岗位）</th><th>考核结果</th><th>奖惩措施</th><th>备注</th></tr>");
        for (Map<String, Object> it : its) {
            int finalScore = i(it.get("score")) - i(it.get("deduction"));
            String lv = statusName(LEVEL, s(it.get("level")));
            String reward = finalScore >= 90 ? "记功·公开表扬" : (finalScore >= 60 ? "合格·按期履行" : "通报·限期整改");
            b.append("<tr><td class='n'>").append(esc(s(it.get("post_name")))).append("</td>")
                    .append("<td class='n'>").append(esc(lv)).append("（").append(finalScore).append(" 分）</td>")
                    .append("<td>").append(esc(reward)).append("</td>")
                    .append("<td>").append(esc(s(it.get("deduction_note")))).append("</td></tr>");
        }
        b.append("</table>");
        if (sealService.sealHtml() != null) b.append("<div class='seal'>").append(sealService.sealHtml()).append("</div>");
        b.append("<p class='foot'>生成时间：").append(LocalDateTime.now()).append("</p>");
        return b.toString();
    }

    /** 归档盒一（年度责任制考核记录）+ 沉淀一人一档（考核/诚信/奖惩记录） */
    private void archiveToCabinet(long assessId, int y, String pdfKey, int size) {
        String box = isDanger() ? "DANGER_BOX1" : "GENERAL_BOX1";
        Map<String, Object> cat = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long catalogId = l(cat.get("id"));
        if (catalogId == 0L) return;
        String title = (isDanger() ? "危货·" : "") + company() + "·" + y + "年度安全生产责任制考核台账";
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='credit' AND `biz_id`=? AND `deleted`=0 LIMIT 1", tid(), assessId);
        long itemId;
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`biz_module`,`biz_id`," +
                            "`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,2,1,'credit',?,?,?,?,1,?,?,0)",
                    itemId, tid(), catalogId, assessId, title, LocalDate.now(), y, LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
            exec("UPDATE `archive_item` SET `catalog_id`=?,`title`=? WHERE `id`=?", catalogId, title, itemId);
        }
        exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=? AND `template_code`='CREDIT_LEDGER'", itemId, tid());
        long pdfId = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,1,'CREDIT_BOX',NULL,?,?,?,0)",
                pdfId, tid(), itemId, (short) y, title + ".pdf", pdfKey, size,
                LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        log("credit", itemId, "archive", "", "archived", "归档到" + box);
        // 一人一档沉淀：每位从业人员各沉淀一份考核/诚信记录
        for (Map<String, Object> p : q("SELECT `id` FROM `person` WHERE `tenant_id`=? AND `deleted`=0", tid())) {
            long uid = l(p.get("id"));
            upsertPersonArchive(uid, "CREDIT_ASSESS", "年度责任制考核记录", assessId, pdfKey);
            upsertPersonArchive(uid, "CREDIT_HONEST", "诚信考核记录", assessId, pdfKey);
        }
    }

    private void upsertPersonArchive(long personId, String code, String name, long refId, String fileUrl) {
        Map<String, Object> exist = one("SELECT `id` FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0", personId, code, tid());
        if (exist.isEmpty()) {
            exec("INSERT INTO `person_archive_item` (`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,1,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), personId, code, "credit", refId, fileUrl, LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            exec("UPDATE `person_archive_item` SET `ref_id`=?,`file_url`=? WHERE `id`=?", refId, fileUrl, l(exist.get("id")));
        }
    }

    // ==================================================================
    // 7. 联动数据源 / 小程序预留
    // ==================================================================
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> posts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `id`,`post_name` FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0 AND `enabled`=1 ORDER BY `id`", tid()))
            posts.add(Map.of("id", String.valueOf(r.get("id")), "name", s(r.get("post_name"))));
        out.put("posts", posts);
        return Result.ok(out);
    }

    @GetMapping("/mine")
    public Result<Map<String, Object>> mine() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assessStatus", ASSESS_STATUS);
        out.put("level", LEVEL);
        return Result.ok(out);
    }

    // ==================================================================
    // 工具：企业变量（法人/安全负责人）
    // ==================================================================
    private Map<String, String> entVars() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("companyName", company());
        Map<String, Object> t = one("SELECT `legal_person`,`safety_charge` FROM `tenant_info` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        v.put("legalPerson", s(t.get("legal_person")));
        v.put("safetyCharge", s(t.get("safety_charge")));
        return v;
    }
    private String toJson(Object o) {
        if (o instanceof List) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object e : (List<?>) o) {
                if (!first) b.append(",");
                Map<?, ?> m = (Map<?, ?>) e;
                b.append("{");
                boolean f2 = true;
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (!f2) b.append(",");
                    b.append("\"").append(en.getKey()).append("\":").append(jsonStr(s(en.getValue())));
                    f2 = false;
                }
                b.append("}");
                first = false;
            }
            b.append("]");
            return b.toString();
        }
        return "[]";
    }

    private Map<String, Object> fail(String msg) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m; }
    private String safeDateTime(Object o) {
        String x = s(o).replace('T', ' ').trim();
        if (x.isBlank()) return LocalDateTime.now().toString();
        return x.length() > 19 ? x.substring(0, 19) : x;
    }
}
