package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * B1 人员基础信息 · 一人一档完整闭环（详细设计 07）。
 *
 * <p>与通用 CRUD 引擎的分工：/api/biz/person* 负责主数据录入，本控制器负责
 * 13 项归档目录的状态流转、完整度实时计算、证件子表、PDF 成品归档与到期预警。
 *
 * <p>铁律 6：每个归档项产生成品时都按 "person_{itemCode}" 登记一条 archive_item，
 * 幂等且有独立 biz_module，保证电子档案柜盒一能逐项看到，而不是每人只登记一条。
 */
@RestController
@RequestMapping("/api/flow/person")
public class PersonFlowController {

    private final JdbcTemplate jdbc;
    private final ArchiveRegistrar registrar;
    private final PdfService pdfService;
    private final StorageService storage;

    public PersonFlowController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                                PdfService pdfService, StorageService storage) {
        this.jdbc = jdbc;
        this.registrar = registrar;
        this.pdfService = pdfService;
        this.storage = storage;
    }

    /** 人员证件编码 → 一人一档归档项编码（逐一对齐 person_archive_dict P01~P13） */
    private static final Map<String, String> DOC_ITEM = Map.of(
            "id_card", "P01", "driver_license", "P02", "qualification", "P03",
            "labor_contract", "P04", "health_check", "P11",
            "two_type_exam", "P09", "continuing_edu", "P13");

    /** 模块间联动：人员作为这些模块的选人数据源（表/列名用于统计引用数） */
    private static final List<String[]> REFS = List.of(
            new String[]{"meeting", "安全会议签到", "meeting_attendee", "user_id"},
            new String[]{"check", "一日三检驾驶员", "check_task", "driver_id"},
            new String[]{"training", "培训学习与考试", "training_assign", "user_id"},
            new String[]{"assess", "责任制考核对象", "credit_assess_item", "user_id"},
            new String[]{"accident", "事故与违法当事人", "monitor_violation", "driver_id"},
            new String[]{"dangerspecial", "危货电子运单驾驶员", "danger_waybill", "driver_id"});

    private static final String DETAIL_SQL =
            "SELECT d.`item_code`, d.`item_name`, d.`source_type`, d.`is_optional`, d.`sort_no`, " +
                    "p.`id` AS item_id, p.`status` AS item_status, p.`file_url`, p.`expire_date`, " +
                    "p.`ref_id`, p.`source_type` AS item_source " +
                    "FROM `person_archive_dict` d LEFT JOIN `person_archive_item` p " +
                    "ON p.`item_code`=d.`item_code` AND p.`person_id`=? AND p.`tenant_id`=? AND p.`deleted`=0 " +
                    "WHERE d.`deleted`=0 ORDER BY d.`sort_no`";

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 与电子档案柜 ManagerArchiveService.GROUP 保持一致的语义分组，保证 B1 一人一档与档案柜对得上 */
    private static final Map<String, String> GROUP = new LinkedHashMap<>() {{
        put("P05", "三书");       put("P06", "三书");       put("P07", "三书");
        put("P08", "岗前培训");
        put("P10", "考核记录");   put("CREDIT_ASSESS", "考核记录");
        put("P12", "任命书");
        put("P01", "证照");       put("P02", "证照");       put("P03", "证照");
        put("P04", "证照");       put("P09", "证照");       put("P11", "证照");
        put("P13", "证照");
    }};

    private static String groupOf(String code) {
        return GROUP.getOrDefault(code, "其他");
    }

    /** 13 项归档目录 + 完整度实时计算（分母只算必备项；危货强制体检计入分母） */
    private Map<String, Object> compute(long personId, String bizType) {
        List<Map<String, Object>> raw = jdbc.queryForList(DETAIL_SQL, personId, tid());
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("证照", new ArrayList<>());
        groups.put("三书", new ArrayList<>());
        groups.put("岗前培训", new ArrayList<>());
        groups.put("考核记录", new ArrayList<>());
        groups.put("任命书", new ArrayList<>());
        groups.put("其他", new ArrayList<>());

        int required = 0, done = 0, expiredCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        for (Map<String, Object> r : raw) {
            String code = s(r.get("item_code"));
            String name = s(r.get("item_name"));
            // 字典定义决定分组与操作类型（固定，不随用户操作改变）；
            // item_source 只反映本次成品的实际产生方式（upload/auto/print），仅用于展示。
            String dictSource = s(r.get("source_type"));
            String actualSource = s(r.get("item_source"));
            String source = actualSource.isBlank() ? dictSource : actualSource;
            String st = s(r.get("item_status"));
            if (st.isBlank()) st = "0";
            String exp = s(r.get("expire_date"));
            boolean optional = "1".equals(s(r.get("is_optional")));
            boolean forced = "DANGER".equalsIgnoreCase(bizType) && name.contains("职业健康");
            boolean counts = !optional || forced;

            Integer daysLeft = null;
            if (exp.length() >= 10) {
                try {
                    daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.substring(0, 10)));
                } catch (Exception ignore) { /* 非日期值忽略 */ }
            }
            boolean expired = daysLeft != null && daysLeft < 0;
            int status = "1".equals(st) ? 1 : "2".equals(st) ? 2 : 0;
            if (expired) status = 2;

            if (counts) {
                required++;
                if (status == 1) done++;
                else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", code); m.put("itemName", name);
                    m.put("status", status); m.put("expired", expired);
                    m.put("optional", false); m.put("forced", forced);
                    missing.add(m);
                    if (expired) expiredCount++;
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemCode", code);
            item.put("itemName", name);
            item.put("sourceType", source);
            item.put("optional", optional);
            item.put("counts", counts);
            item.put("status", status);                 // 0缺 1齐 2已过期
            item.put("fileUrl", s(r.get("file_url")));
            item.put("expireDate", exp);
            item.put("daysLeft", daysLeft);
            item.put("group", groupOf(code));
            items.add(item);
            groups.get(groupOf(code)).add(item);
        }

        int percent = required == 0 ? 0 : (int) Math.round(done * 100.0 / required);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("groups", groups);
        out.put("percent", percent);
        out.put("required", required);
        out.put("done", done);
        out.put("missing", missing);
        out.put("expired", expiredCount);
        return out;
    }

    /** 某人员 30 天内到期 / 已过期的证件数 */
    private int expiringCount(long personId) {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `person_doc` WHERE `person_id`=? AND `tenant_id`=? AND `deleted`=0 " +
                            "AND `expire_date` IS NOT NULL AND `expire_date` <= ?",
                    Long.class, personId, tid(), LocalDate.now().plusDays(30).toString());
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    private Map<String, Object> personOrEmpty(long id) {
        try { return jdbc.queryForMap("SELECT * FROM `person` WHERE `id`=? AND `deleted`=0", id); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> dictOrEmpty(String code) {
        try {
            return jdbc.queryForMap(
                    "SELECT `item_code`,`item_name`,`source_type`,`is_optional` FROM `person_archive_dict` WHERE `item_code`=? AND `deleted`=0", code);
        } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    /** 归档项 upsert（状态机落库） */
    private void upsertItem(long personId, String itemCode, String sourceType,
                            String fileUrl, String expireDate, Long refId, int status) {
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                Long.class, personId, itemCode, tid());
        String exp = (expireDate == null || expireDate.isBlank()) ? null : expireDate;
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO `person_archive_item` " +
                            "(`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), personId, itemCode, sourceType, refId,
                    fileUrl, exp, status, LocalDateTime.now(), uid());
        } else {
            jdbc.update("UPDATE `person_archive_item` SET `source_type`=?, `ref_id`=?, `file_url`=?, " +
                            "`expire_date`=?, `status`=?, `updated_at`=? WHERE `id`=?",
                    sourceType, refId, fileUrl, exp, status, LocalDateTime.now(), ids.get(0));
        }
    }

    // ==================================================================
    // 1. 全员归档概览（列表页「归档完整度」列 + 驾驶舱统计）
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "GENERAL") String bizType) {
        List<Map<String, Object>> persons = jdbc.queryForList(
                "SELECT p.`id`, p.`name`, p.`telephone`, p.`person_type`, p.`post_ids`, p.`id_card`, p.`politic`, p.`gender`, " +
                "GROUP_CONCAT(DISTINCT o.`post_name` ORDER BY o.`id` SEPARATOR '、') AS `post_name` " +
                "FROM `person` p LEFT JOIN `org_member` m " +
                "  ON m.`user_id`=p.`id` AND m.`tenant_id`=p.`tenant_id` AND m.`deleted`=0 " +
                "LEFT JOIN `org_post` o ON o.`id`=m.`post_id` AND o.`deleted`=0 AND o.`enabled`=1 " +
                "WHERE p.`tenant_id`=? AND p.`deleted`=0 " +
                "GROUP BY p.`id`, p.`name`, p.`telephone`, p.`person_type`, p.`post_ids`, p.`id_card`, p.`politic`, p.`gender` " +
                "ORDER BY p.`id`", tid());
        List<Map<String, Object>> list = new ArrayList<>();
        int complete = 0, expiringTotal = 0;
        for (Map<String, Object> p : persons) {
            long pid = ((Number) p.get("id")).longValue();
            Map<String, Object> c = compute(pid, bizType);
            int expiring = expiringCount(pid);
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.put("percent", c.get("percent"));
            row.put("missingCount", ((List<?>) c.get("missing")).size());
            row.put("missing", c.get("missing"));
            row.put("expiring", expiring);
            list.add(row);
            if ((Integer) c.get("percent") >= 100) complete++;
            expiringTotal += expiring;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("complete", complete);
        out.put("pending", list.size() - complete);
        out.put("expiring", expiringTotal);
        out.put("bizType", bizType);
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 一人一档详情（13 项按四类分组 + 完整度 + 缺项 + 证件）
    // ==================================================================
    @GetMapping("/{id}/archive")
    public Result<Map<String, Object>> archive(@PathVariable long id,
                                               @RequestParam(defaultValue = "GENERAL") String bizType) {
        Map<String, Object> person = personOrEmpty(id);
        if (person.isEmpty()) return Result.ok(Map.of("ok", false, "msg", "人员不存在"));
        Map<String, Object> c = compute(id, bizType);
        List<Map<String, Object>> docs = new ArrayList<>();
        try {
            for (Map<String, Object> d : jdbc.queryForList(
                    "SELECT `id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status` " +
                            "FROM `person_doc` WHERE `person_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `expire_date`",
                    id, tid())) {
                Map<String, Object> row = new LinkedHashMap<>(d);
                String exp = s(d.get("expire_date"));
                Integer daysLeft = null;
                if (exp.length() >= 10) {
                    try { daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.substring(0, 10))); }
                    catch (Exception ignore) { }
                }
                row.put("daysLeft", daysLeft);
                row.put("warning", daysLeft != null && daysLeft <= 30);
                docs.add(row);
            }
        } catch (Exception ignore) { }
        Map<String, Object> out = new LinkedHashMap<>(c);
        out.put("ok", true);
        out.put("person", person);
        out.put("personId", id);
        out.put("docs", docs);
        out.put("expiring", expiringCount(id));
        out.put("bizType", bizType);
        return Result.ok(out);
    }

    // ==================================================================
    // 3. 逐项登记 / 状态流转（0缺 → 1齐 → 2已过期）
    // ==================================================================
    @PutMapping("/{id}/archive/{code}")
    public Result<Map<String, Object>> setItem(@PathVariable long id, @PathVariable String code,
                                               @RequestBody Map<String, Object> b) {
        Map<String, Object> dict = dictOrEmpty(code);
        if (dict.isEmpty()) return Result.ok(Map.of("ok", false, "msg", "未知归档项：" + code));
        String fileUrl = s(b.get("fileUrl"));
        String exp = s(b.get("expireDate"));
        String source = s(b.get("sourceType"));
        if (source.isBlank()) source = s(dict.get("source_type"));
        int status = 1;
        if (b.get("status") != null) {
            try { status = Integer.parseInt(String.valueOf(b.get("status"))); } catch (Exception ignore) { }
        }
        if (status == 1 && exp.length() >= 10) {
            try { if (LocalDate.parse(exp.substring(0, 10)).isBefore(LocalDate.now())) status = 2; }
            catch (Exception ignore) { }
        }
        Long refId = null;
        if (b.get("refId") != null) {
            try { refId = Long.parseLong(String.valueOf(b.get("refId"))); } catch (Exception ignore) { }
        }
        upsertItem(id, code, source, fileUrl, exp, refId, status);
        if (status == 1) {
            registrar.register("person_" + code, "BOX1", id, s(dict.get("item_name")));
        }
        Map<String, Object> c = compute(id, "GENERAL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", code);
        out.put("status", status);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    // ==================================================================
    // 4. 证件上传（person_doc 子表 + 自动落对应归档项）
    // ==================================================================
    @PostMapping("/{id}/doc")
    public Result<Map<String, Object>> addDoc(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String docCode = s(b.get("docCode"));
        String docName = s(b.get("docName"));
        if (docName.isBlank()) docName = docCode;
        String itemCode = DOC_ITEM.get(docCode);
        if (itemCode == null) return Result.ok(Map.of("ok", false, "msg", "未知证件类型：" + docCode));
        String fileUrl = s(b.get("fileUrl"));
        String issue = s(b.get("issueDate"));
        String exp = s(b.get("expireDate"));
        int status = 1;
        if (exp.length() >= 10) {
            try { if (LocalDate.parse(exp.substring(0, 10)).isBefore(LocalDate.now())) status = 2; }
            catch (Exception ignore) { }
        }
        jdbc.update("INSERT INTO `person_doc` " +
                        "(`id`,`tenant_id`,`person_id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, docCode, docName, fileUrl,
                issue.isBlank() ? null : issue, exp.isBlank() ? null : exp, status,
                LocalDateTime.now(), uid());
        upsertItem(id, itemCode, "upload", fileUrl, exp, null, status);
        if (status == 1) registrar.register("person_" + itemCode, "BOX1", id, docName);
        Map<String, Object> c = compute(id, "GENERAL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("docCode", docCode);
        out.put("itemCode", itemCode);
        out.put("status", status);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    // ==================================================================
    // 5. 生成归档 PDF（成品 / 空白表降级）+ 登记到电子档案柜盒一
    // ==================================================================
    @PostMapping("/{id}/generate")
    public Result<Map<String, Object>> generate(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String itemCode = s(b.get("itemCode"));
        Map<String, Object> dict = dictOrEmpty(itemCode);
        if (dict.isEmpty()) return Result.ok(Map.of("ok", false, "msg", "未知归档项：" + itemCode));
        boolean blank = Boolean.parseBoolean(String.valueOf(b.getOrDefault("blank", false)));
        // 已有真实归档件（上传扫描件 / 签订件）时拒绝被模板成品覆盖，保证三处内容一致
        List<Map<String, Object>> exist = jdbc.queryForList(
                "SELECT `file_url`, `source_type` FROM `person_archive_item` " +
                "WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1",
                id, itemCode, tid());
        if (!exist.isEmpty()) {
            String curUrl = s(exist.get(0).get("file_url"));
            String curSrc = s(exist.get(0).get("source_type"));
            if (!curUrl.isBlank() && !"auto".equals(curSrc) && !"print".equals(curSrc)) {
                return Result.ok(Map.of("ok", false,
                        "msg", "该项已有上传扫描件或签订生成的归档件，系统不覆盖；如需更换请重新上传证件"));
            }
        }
        Map<String, Object> person = personOrEmpty(id);
        String itemName = s(dict.get("item_name"));
        String html = buildHtml(person, itemName, itemCode, blank);
        byte[] pdf = pdfService.render(html);
        String key = tid() + "/person/" + id + "_" + itemCode + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        upsertItem(id, itemCode, blank ? "print" : "auto", key, null, null, 1);
        registrar.register("person_" + itemCode, "BOX1", id, itemName + (blank ? "（空白表）" : ""));
        Map<String, Object> c = compute(id, "GENERAL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", itemCode);
        out.put("objectKey", key);
        out.put("size", pdf.length);
        out.put("blank", blank);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    /** 在线查看归档件：优先返回真实归档文件（上传的扫描件/签订生成的 PDF），无文件才回退渲染模板 */
    @GetMapping("/{id}/archive/{code}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id, @PathVariable String code,
                                      @RequestParam(defaultValue = "false") boolean blank) {
        // 1) 读归档项的真实文件（上传扫描件 / 签订件 / 范本成品），保证「查看PDF」与上传、档案柜一致
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `file_url` FROM `person_archive_item` " +
                "WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0 LIMIT 1",
                id, code, tid());
        String fileUrl = rows.isEmpty() ? "" : s(rows.get(0).get("file_url"));
        if (!fileUrl.isBlank()) {
            try {
                byte[] data = storage.download(fileUrl);
                if (data != null && data.length > 0) {
                    String low = fileUrl.toLowerCase();
                    MediaType mt = low.endsWith(".png") ? MediaType.IMAGE_PNG
                            : low.endsWith(".jpg") || low.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                            : MediaType.APPLICATION_PDF;
                    String name = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\"")
                            .contentType(mt)
                            .body(data);
                }
            } catch (Exception ignore) { /* 对象缺失则回退模板渲染 */ }
        }
        // 2) 无归档文件：按需渲染（blank=true 给空白表，否则模板成品）
        Map<String, Object> dict = dictOrEmpty(code);
        String itemName = dict.isEmpty() ? "一人一档归档表" : s(dict.get("item_name"));
        byte[] bytes = pdfService.render(buildHtml(personOrEmpty(id), itemName, code, blank));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"person_" + code + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    private String buildHtml(Map<String, Object> p, String itemName, String itemCode, boolean blank) {
        String name = esc(s(p.get("name")));
        String tel = esc(s(p.get("telephone")));
        String idCard = esc(s(p.get("id_card")));
        String type = "employee".equals(s(p.get("person_type"))) ? "从业人员" : "管理人员";
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:36px;color:#222}")
                .append("h1{text-align:center;font-size:20px;letter-spacing:2px;margin-bottom:6px}")
                .append("table{width:100%;border-collapse:collapse;margin-top:18px}")
                .append("td,th{border:1px solid #666;padding:9px;font-size:13px}")
                .append(".n{background:#f5f5f5;text-align:center;width:110px}")
                .append(".tip{text-align:center;color:#999;font-size:12px}")
                .append(".foot{margin-top:26px;font-size:12px;color:#666}")
                .append("</style></head><body>");
        h.append("<h1>").append(esc(itemName)).append("</h1>");
        if (blank) {
            h.append("<p class='tip'>（空白表 · 暂无系统数据，请打印后手写补充）</p>")
                    .append("<table><tr><td class='n'>姓名</td><td></td><td class='n'>人员类型</td><td></td></tr>")
                    .append("<tr><td class='n'>手机号</td><td></td><td class='n'>身份证号</td><td></td></tr>")
                    .append("<tr><td class='n'>填表日期</td><td></td><td class='n'>本人签字</td><td></td></tr>")
                    .append("<tr><td class='n'>备注</td><td colspan='3'></td></tr></table>");
        } else {
            h.append("<table>")
                    .append("<tr><td class='n'>姓名</td><td>").append(name)
                    .append("</td><td class='n'>人员类型</td><td>").append(type).append("</td></tr>")
                    .append("<tr><td class='n'>手机号</td><td>").append(tel)
                    .append("</td><td class='n'>身份证号</td><td>").append(idCard).append("</td></tr>")
                    .append("<tr><td class='n'>归档项编码</td><td>").append(esc(itemCode))
                    .append("</td><td class='n'>生成日期</td><td>").append(LocalDate.now()).append("</td></tr>")
                    .append("<tr><td class='n'>企业确认</td><td colspan='3'>以上内容由系统按人员档案与台账范本自动生成，经核对无误后归档。</td></tr>")
                    .append("</table>");
        }
        h.append("<p class='foot'>道路运输安全生产管理平台 · 一人一档（盒一） · ").append(LocalDate.now()).append("</p>")
                .append("</body></html>");
        return h.toString();
    }

    // ==================================================================
    // 6. 证照到期预警（30/15/7 天）→ 驾驶舱 / 小程序消息
    // ==================================================================
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT d.`id`, d.`person_id`, p.`name` AS person_name, d.`doc_code`, d.`doc_name`, d.`expire_date` " +
                            "FROM `person_doc` d JOIN `person` p ON p.`id`=d.`person_id` " +
                            "WHERE d.`tenant_id`=? AND d.`deleted`=0 AND d.`expire_date` IS NOT NULL " +
                            "AND d.`expire_date` <= ? ORDER BY d.`expire_date`",
                    tid(), LocalDate.now().plusDays(days).toString());
            for (Map<String, Object> r : rows) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                String exp = s(r.get("expire_date"));
                int left = 0;
                try { left = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.substring(0, 10))); }
                catch (Exception ignore) { }
                row.put("daysLeft", left);
                row.put("level", left < 0 ? "expired" : left <= 7 ? "d7" : left <= 15 ? "d15" : "d30");
                list.add(row);
            }
        } catch (Exception ignore) { }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        return Result.ok(out);
    }

    // ==================================================================
    // 7. 人员下拉选项（模块间联动：各模块选人均来自 person 主表）
    // ==================================================================
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.ok(jdbc.queryForList(
                "SELECT `id`,`name`,`telephone`,`person_type`,`post_ids` FROM `person` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid()));
    }

    // ==================================================================
    // 8. 人员被哪些模块引用（模块间联动清单，容错：表/列不存在则计数 0）
    // ==================================================================
    @GetMapping("/{id}/references")
    public Result<List<Map<String, Object>>> references(@PathVariable long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : REFS) {
            int n = 0;
            try {
                Long c = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `" + r[2] + "` WHERE `" + r[3] + "`=? AND `deleted`=0",
                        Long.class, id);
                n = c == null ? 0 : c.intValue();
            } catch (Exception ignore) { }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", r[0]);
            row.put("name", r[1]);
            row.put("count", n);
            out.add(row);
        }
        return Result.ok(out);
    }
}
