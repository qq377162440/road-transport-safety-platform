package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.SealService;
import com.safetrans.tpl.service.TplRenderEngine;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;

/**
 * B3 制度档案引擎 · 完整流程闭环（详细设计 09 §7.3 / §7.10 / §7.11）。
 *
 * <p>与「台账范本管理」的分工：tpl_template 管空白范本（多套版本），本控制器管企业
 * 实际生效的 24 项制度成品——文件编号、实施日期、签发人、盖章、审批状态、版本留痕，
 * 最终逐项归档到电子档案柜盒二（BOX2）。两端共用同一份范本正文与企业印章，天然同源。
 *
 * <p>状态机：0未起草 → 1起草中 → 2待审核 → 3已审核待发布 → 4已发布 → 5已作废；
 * 2 可驳回为 6审核驳回（重新起草）；4 可修订生成新版本回到 1。每次流转写日志。
 *
 * <p>铁律 6：每个版本发布时按 "institution_{code}" 登记一条 archive_item，
 * 目录按 "institution_TOC" 登记，保证盒二逐项可见且版本可回溯。
 */
@RestController
@RequestMapping("/api/flow/institution")
public class InstitutionFlowController {

    private final JdbcTemplate jdbc;
    private final ArchiveRegistrar registrar;
    private final PdfService pdfService;
    private final StorageService storage;
    private final TplRenderEngine render;
    private final SealService sealService;

    public InstitutionFlowController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                                     PdfService pdfService, StorageService storage,
                                     TplRenderEngine render, SealService sealService) {
        this.jdbc = jdbc;
        this.registrar = registrar;
        this.pdfService = pdfService;
        this.storage = storage;
        this.render = render;
        this.sealService = sealService;
    }

    /** 制度编号前缀：与内置范本正文「制度编号：YT-ZD01」保持同源 */
    private static final String ABBR = "YT";
    // 盒二归档项 catalog_id：档案柜盒视图按 BOX_CATALOG_BASE(50000) + 盒号 过滤，
    // 故盒二登记必须落 50002，才能被 listUploadItems 正确捞回并展示来源徽标。
    private static final long BOX2_CATALOG_ID = 50002L;

    /** 24 项通用制度层字典（详细设计 09 §7.3.0，与 tpl_template ZD01~ZD24 一一对应） */
    private static final String[][] ITEMS = {
            {"ZD01", "安全生产责任制", "ALL", "通用管理制度"},
            {"ZD02", "安全生产会议制度", "ALL", "通用管理制度"},
            {"ZD03", "安全生产台账及档案管理制度", "ALL", "通用管理制度"},
            {"ZD04", "安全生产费用提取和使用管理制度", "ALL", "通用管理制度"},
            {"ZD05", "车辆安全管理制度", "ALL", "通用管理制度"},
            {"ZD06", "安全生产教育培训制度", "ALL", "通用管理制度"},
            {"ZD07", "安全生产监督检查制度", "ALL", "通用管理制度"},
            {"ZD08", "隐患排查与治理制度", "ALL", "通用管理制度"},
            {"ZD09", "安全隐患报告和举报奖励制度", "ALL", "通用管理制度"},
            {"ZD10", "安全生产考核与奖惩制度", "ALL", "通用管理制度"},
            {"ZD11", "安全事故报告、统计与处理制度", "ALL", "通用管理制度"},
            {"ZD12", "驾驶员管理制度", "ALL", "通用管理制度"},
            {"ZD13", "动态监控管理制度", "ALL", "通用管理制度"},
            {"ZD14", "风险分级管控制度", "ALL", "通用管理制度"},
            {"ZD15", "风险动态监控管理制度", "ALL", "通用管理制度"},
            {"ZD16", "应急预案管理制度", "ALL", "通用管理制度"},
            {"ZD17", "劳动保护用品配备和使用管理制度", "ALL", "通用管理制度"},
            {"ZD18", "超限超载运输管理规定", "ALL", "通用管理制度"},
            {"ZD19", "车辆装载及装卸作业安全规定", "ALL", "通用管理制度"},
            {"ZD20", "防止货物脱落、扬撒工作措施", "ALL", "通用管理制度"},
            {"ZD21", "消防安全管理制度", "ALL", "通用管理制度"},
            {"ZD22", "重大危险源管理制度", "ALL", "通用管理制度"},
            {"ZD23", "安全生产操作规程（普货）", "GENERAL", "专项规程与风险手册"},
            {"ZD24", "风险辨识与风险评价手册（普货）", "GENERAL", "专项规程与风险手册"},
    };

    /** 普货 7 大制度类 → 覆盖车型（详细设计 09 §7.5） */
    private static final String[][] GENERAL_CLASSES = {
            {"厢式及封闭运输类", "厢车、飞翼车"},
            {"栏板及仓栅运输类", "高栏车、仓栅车、栏板车"},
            {"集装箱及牵引运输类", "集装箱车、牵引车"},
            {"自卸及工程运输类", "泥头车、自卸车"},
            {"罐体及散装物料运输类", "液体罐车、粉粒物料运输车"},
            {"平板及大件类", "平板车、低平板车、大件运输车"},
            {"专项功能类", "冷藏车、随车吊、搅拌车"},
    };

    /** 危货两层结构（详细设计 09 §7.4） */
    private static final String[] DANGER_STRUCT = {"罐式", "厢式包装", "集装箱", "气瓶"};
    private static final String[] DANGER_STAGE = {"驾驶", "押运", "装卸管理", "危险货物运输", "动态监控", "组织调度", "防护用品穿戴", "罐体清洗消毒", "各级维护", "安全检查检测"};

    /** 模块间联动：制度被哪些模块引用（表/列不存在时计数 0，容错） */
    private static final List<String[]> REFS = List.of(
            new String[]{"meeting", "安全会议（依据制度）", "meeting", "institution_code"},
            new String[]{"training", "培训宣贯（制度依据）", "training_task", "institution_code"},
            new String[]{"check", "一日三检（检查依据）", "check_task", "institution_code"},
            new String[]{"danger", "隐患排查（判定依据）", "danger_record", "institution_code"},
            new String[]{"assess", "责任制考核（考核依据）", "credit_assess_item", "institution_code"},
            new String[]{"emergency", "应急预案（制度关联）", "emergency_plan", "institution_code"});

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String date10(Object o) {
        String v = s(o);
        return v.length() >= 10 ? v.substring(0, 10) : "";
    }

    private static Object dateParam(String v) { return v == null || v.isBlank() ? null : v; }

    private static int intOf(Object o, int def) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static long longOf(Object o) {
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    public static String statusText(int st) {
        return switch (st) {
            case 1 -> "起草中";
            case 2 -> "待审核";
            case 3 -> "待发布";
            case 4 -> "已发布";
            case 5 -> "已作废";
            case 6 -> "审核驳回";
            default -> "未起草";
        };
    }

    private static String[] dictOf(String code) {
        for (String[] d : ITEMS) if (d[0].equalsIgnoreCase(code)) return d;
        return null;
    }

    private String company() { return sealService.companyName(); }

    // ==================================================================
    // 1. 制度字典数据：24 项 + 状态 + 范本版本数 + 签署进度 + 完整度
    // ==================================================================
    private Map<String, Object> buildCatalog() {
        Map<String, Map<String, Object>> state = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT * FROM `institution_item` WHERE `tenant_id`=? AND `deleted`=0", tid())) {
                state.put(s(r.get("item_code")), r);
            }
        } catch (Exception ignore) { }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("通用管理制度", new ArrayList<>());
        groups.put("专项规程与风险手册", new ArrayList<>());
        List<Map<String, Object>> items = new ArrayList<>();
        int published = 0;

        for (String[] d : ITEMS) {
            String code = d[0];
            Map<String, Object> st = state.getOrDefault(code, Collections.emptyMap());
            int status = intOf(st.get("status"), 0);
            if (status == 4) published++;

            long tplCount;
            try {
                Long n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `tpl_template` WHERE `code`=? AND `box_no`=2 AND `deleted`=0",
                        Long.class, code);
                tplCount = n == null ? 0 : n;
            } catch (Exception e) { tplCount = 0; }

            int signTotal = 0, signDone = 0;
            try {
                List<Map<String, Object>> sg = jdbc.queryForList(
                        "SELECT `status` FROM `institution_sign` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                        code, tid());
                signTotal = sg.size();
                for (Map<String, Object> x : sg) if (intOf(x.get("status"), 0) == 1) signDone++;
            } catch (Exception ignore) { }

            int versions = 0;
            try {
                Long n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `institution_version` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                        Long.class, code, tid());
                versions = n == null ? 0 : n.intValue();
            } catch (Exception ignore) { }

            String review = date10(st.get("review_date"));
            Integer daysLeft = null;
            if (review.length() == 10) {
                try { daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(review)); }
                catch (Exception ignore) { }
            }

            Map<String, Object> it = new LinkedHashMap<>();
            it.put("itemCode", code);
            it.put("itemName", d[1]);
            it.put("docNo", ABBR + "-" + code);
            it.put("bizScope", d[2]);
            it.put("group", d[3]);
            it.put("status", status);
            it.put("statusText", statusText(status));
            it.put("version", s(st.get("current_version")));
            it.put("versionSeq", intOf(st.get("version_seq"), 0));
            it.put("versionCount", versions);
            it.put("tplId", s(st.get("tpl_id")));
            it.put("tplLabel", s(st.get("tpl_label")));
            it.put("tplCount", tplCount);
            it.put("issuerId", s(st.get("issuer_id")));
            it.put("issuerName", s(st.get("issuer_name")));
            it.put("effectiveDate", date10(st.get("effective_date")));
            it.put("reviewDate", review);
            it.put("reviewDaysLeft", daysLeft);
            it.put("fileUrl", s(st.get("file_url")));
            it.put("signTotal", signTotal);
            it.put("signDone", signDone);
            items.add(it);
            groups.computeIfAbsent(d[3], k -> new ArrayList<>()).add(it);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("groups", groups);
        out.put("total", ITEMS.length);
        out.put("published", published);
        out.put("percent", ITEMS.length == 0 ? 0 : (int) Math.round(published * 100.0 / ITEMS.length));
        out.put("abbr", ABBR);
        out.put("company", company());
        return out;
    }

    @GetMapping("/catalog")
    public Result<Map<String, Object>> catalog() {
        return Result.ok(buildCatalog());
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>(buildCatalog());
        int[] cnt = new int[7];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        for (Map<String, Object> it : items) cnt[intOf(it.get("status"), 0)]++;
        out.put("drafting", cnt[1]);
        out.put("reviewing", cnt[2]);
        out.put("pendingPublish", cnt[3]);
        out.put("abolished", cnt[5]);
        out.put("rejected", cnt[6]);
        out.put("notStarted", cnt[0]);
        out.put("expiring", expiringList(days).size());
        out.put("statusMap", Map.of(
                "0", "未起草", "1", "起草中", "2", "待审核", "3", "待发布",
                "4", "已发布", "5", "已作废", "6", "审核驳回"));
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 单条制度详情（当前版本 / 版本历史 / 签署进度 / 流转日志）
    // ==================================================================
    @GetMapping("/{code}")
    public Result<Map<String, Object>> detail(@PathVariable String code) {
        String[] d = dictOf(code);
        if (d == null) return Result.ok(Map.of("ok", false, "msg", "未知制度编码：" + code));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", code);
        out.put("itemName", d[1]);
        out.put("docNo", ABBR + "-" + code);
        out.put("bizScope", d[2]);
        out.put("company", company());
        out.put("abbr", ABBR);

        Map<String, Object> item = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM `institution_item` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0", code, tid());
            if (!rows.isEmpty()) item = rows.get(0);
        } catch (Exception ignore) { }
        out.put("item", item);
        out.put("status", intOf(item.get("status"), 0));
        out.put("statusText", statusText(intOf(item.get("status"), 0)));

        out.put("versions", query("SELECT * FROM `institution_version` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `version_seq` DESC", code, tid()));
        out.put("signs", query("SELECT * FROM `institution_sign` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `id`", code, tid()));
        out.put("logs", query("SELECT * FROM `institution_flow_log` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC", code, tid()));
        out.put("templates", templatesOf(code));
        out.put("references", referencesOf(code));
        return Result.ok(out);
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        try { return jdbc.queryForList(sql, args); } catch (Exception e) { return new ArrayList<>(); }
    }

    private List<Map<String, Object>> templatesOf(String code) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT `id`,`name`,`code`,`version_label`,`version`,`need_seal`,`tenant_id`,`catalog_id` " +
                            "FROM `tpl_template` WHERE `code`=? AND `box_no`=2 AND `deleted`=0 ORDER BY `id`", code)) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                row.put("label", s(r.get("version_label")).isBlank() ? "内置版" : s(r.get("version_label")));
                out.add(row);
            }
        } catch (Exception ignore) { }
        return out;
    }

    private List<Map<String, Object>> referencesOf(String code) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : REFS) {
            int n = 0;
            try {
                Long c = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `" + r[2] + "` WHERE `" + r[3] + "`=? AND `deleted`=0",
                        Long.class, code);
                n = c == null ? 0 : c.intValue();
            } catch (Exception ignore) { }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", r[0]); row.put("name", r[1]); row.put("count", n);
            out.add(row);
        }
        return out;
    }

    private List<Map<String, String>> parseRefs(String refs) {
        List<Map<String, String>> out = new ArrayList<>();
        if (refs == null || refs.isBlank()) return out;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Object> arr = om.readValue(refs, List.class);
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, String> r = new LinkedHashMap<>();
                    r.put("type", s(m.get("type")));
                    r.put("name", s(m.get("name")));
                    out.add(r);
                }
            }
        } catch (Exception ignore) { }
        return out;
    }

    @GetMapping("/templates")
    public Result<Map<String, Object>> templates(@RequestParam(required = false) String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (code != null && !code.isBlank()) {
            out.put("list", templatesOf(code));
        } else {
            Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
            for (String[] d : ITEMS) all.put(d[0], templatesOf(d[0]));
            out.put("all", all);
        }
        return Result.ok(out);
    }

    /** 签发人 / 签署人下拉：B1 person 主表同源 */
    @GetMapping("/persons")
    public Result<List<Map<String, Object>>> persons() {
        return Result.ok(query("SELECT `id`,`name`,`telephone`,`person_type` FROM `person` " +
                "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid()));
    }

    // ==================================================================
    // 3. 状态机：起草 → 提交审核 → 审核/驳回 → 发布 → 修订 → 作废
    // ==================================================================
    private Map<String, Object> itemOrEmpty(String code) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM `institution_item` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0", code, tid());
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private void upsertItem(String code, String name, String scope, int status, String version, int seq,
                            Long tplId, String tplLabel, Long issuerId, String issuerName,
                            String eff, String review, String fileUrl) {
        Map<String, Object> cur = itemOrEmpty(code);
        if (cur.isEmpty()) {
            jdbc.update("INSERT INTO `institution_item` " +
                            "(`id`,`tenant_id`,`item_code`,`item_name`,`doc_no`,`biz_scope`,`status`,`current_version`,`version_seq`," +
                            " `tpl_id`,`tpl_label`,`issuer_id`,`issuer_name`,`effective_date`,`review_date`,`file_url`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), code, name, ABBR + "-" + code, scope, status, version, seq,
                    tplId, tplLabel, issuerId, issuerName, dateParam(eff), dateParam(review), fileUrl,
                    LocalDateTime.now(), uid());
        } else {
            jdbc.update("UPDATE `institution_item` SET `status`=?, `current_version`=?, `version_seq`=?, " +
                            "`tpl_id`=?, `tpl_label`=?, `issuer_id`=?, `issuer_name`=?, `effective_date`=?, " +
                            "`review_date`=?, `file_url`=?, `updated_at`=? WHERE `id`=?",
                    status, version, seq, tplId, tplLabel, issuerId, issuerName,
                    dateParam(eff), dateParam(review), fileUrl, LocalDateTime.now(), cur.get("id"));
        }
    }

    private void log(String code, String version, int from, int to, String action, String remark) {
        try {
            jdbc.update("INSERT INTO `institution_flow_log` " +
                            "(`id`,`tenant_id`,`item_code`,`version`,`from_status`,`to_status`,`action`,`operator`,`remark`,`created_at`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), code, version, from, to, action,
                    "当前用户", remark == null ? "" : remark, LocalDateTime.now());
        } catch (Exception ignore) { }
    }

    private int statusOf(String code) {
        return intOf(itemOrEmpty(code).get("status"), 0);
    }

    private Result<Map<String, Object>> reject(String msg) {
        return Result.ok(Map.of("ok", false, "msg", msg));
    }

    private Result<Map<String, Object>> okState(String code, String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", code);
        out.put("action", action);
        out.put("status", statusOf(code));
        out.put("statusText", statusText(statusOf(code)));
        out.put("percent", buildCatalog().get("percent"));
        return Result.ok(out);
    }

    /** 起草：选范本版本 + 签发人 + 实施日期 + 复审日期，生成新版本与不盖章草稿 PDF */
    @PostMapping("/{code}/draft")
    public Result<Map<String, Object>> draft(@PathVariable String code, @RequestBody Map<String, Object> b) {
        String[] d = dictOf(code);
        if (d == null) return reject("未知制度编码：" + code);
        int cur = statusOf(code);
        if (cur != 0 && cur != 1 && cur != 6) return reject("当前状态不可起草：" + statusText(cur));

        long tplId = longOf(b.get("tplId"));
        List<Map<String, Object>> tpls = templatesOf(code);
        Map<String, Object> tpl = new LinkedHashMap<>();
        if (tplId != 0) {
            for (Map<String, Object> t : tpls) if (longOf(t.get("id")) == tplId) tpl = t;
        }
        if (tpl.isEmpty() && !tpls.isEmpty()) tpl = tpls.get(0);
        String tplLabel = s(tpl.get("label"));

        String eff = date10(b.get("effectiveDate"));
        String review = date10(b.get("reviewDate"));
        long issuerId = longOf(b.get("issuerId"));
        String issuerName = s(b.get("issuerName"));
        if (issuerName.isBlank() && issuerId != 0) {
            issuerName = s(query("SELECT `name` FROM `person` WHERE `id`=? AND `deleted`=0", issuerId)
                    .stream().findFirst().orElse(Map.of()).get("name"));
        }
        if (issuerName.isBlank()) issuerName = "主要负责人";
        String reason = s(b.get("reason"));

        Map<String, Object> old = itemOrEmpty(code);
        // 「起草中」再次起草视为编辑当前草稿：覆盖同一版本，不生成冗余历史版本；
        // 只有「未起草 / 审核驳回」才开启新版本序号，修订（已发布）另走 revise 端点。
        boolean redraft = cur == 1;
        int seq = redraft ? Math.max(1, intOf(old.get("version_seq"), 1)) : intOf(old.get("version_seq"), 0) + 1;
        String version = "v" + seq;
        if (redraft) {
            jdbc.update("DELETE FROM `institution_version` " +
                            "WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0",
                    code, version, tid());
        }
        byte[] pdf = buildDocPdf(code, d[1], ABBR + "-" + code, version, tplLabel, issuerName, eff, tpl, false);
        String key = tid() + "/institution/" + code + "_" + version + "_draft_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");

        long versionId = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `institution_version` " +
                        "(`id`,`tenant_id`,`item_code`,`version`,`version_seq`,`tpl_id`,`tpl_label`,`doc_no`," +
                        " `issuer_id`,`issuer_name`,`effective_date`,`review_date`,`reason`,`status`,`file_url`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,0)",
                versionId, tid(), code, version, seq, tpl.isEmpty() ? null : longOf(tpl.get("id")), tplLabel,
                ABBR + "-" + code, issuerId == 0 ? null : issuerId, issuerName, dateParam(eff), dateParam(review),
                reason, key, LocalDateTime.now(), uid());

        upsertItem(code, d[1], d[2], 1, version, seq, tpl.isEmpty() ? null : longOf(tpl.get("id")),
                tplLabel, issuerId == 0 ? null : issuerId, issuerName, eff, review, key);
        log(code, version, cur, 1, "draft", (redraft ? "编辑草稿 " : "起草 ") + version
                + "，采用范本「" + tplLabel + "」" + (reason.isBlank() ? "" : "，原因：" + reason));

        Map<String, Object> out = new LinkedHashMap<>(okState(code, "draft").getData());
        out.put("version", version);
        out.put("versionId", versionId);
        out.put("objectKey", key);
        out.put("size", pdf.length);
        out.put("tplLabel", tplLabel);
        return Result.ok(out);
    }

    @PutMapping("/{code}/submit")
    public Result<Map<String, Object>> submit(@PathVariable String code, @RequestBody(required = false) Map<String, Object> b) {
        if (dictOf(code) == null) return reject("未知制度编码：" + code);
        if (statusOf(code) != 1) return reject("仅「起草中」可提交审核，当前：" + statusText(statusOf(code)));
        String v = s(itemOrEmpty(code).get("current_version"));
        jdbc.update("UPDATE `institution_item` SET `status`=2, `updated_at`=? WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                LocalDateTime.now(), code, tid());
        jdbc.update("UPDATE `institution_version` SET `status`=2 WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0",
                code, v, tid());
        log(code, v, 1, 2, "submit", s(b == null ? null : b.get("remark")));
        return okState(code, "submit");
    }

    @PutMapping("/{code}/approve")
    public Result<Map<String, Object>> approve(@PathVariable String code, @RequestBody(required = false) Map<String, Object> b) {
        if (dictOf(code) == null) return reject("未知制度编码：" + code);
        if (statusOf(code) != 2) return reject("仅「待审核」可审核，当前：" + statusText(statusOf(code)));
        boolean pass = b == null || !"false".equalsIgnoreCase(String.valueOf(b.getOrDefault("pass", true)));
        int to = pass ? 3 : 6;
        String v = s(itemOrEmpty(code).get("current_version"));
        jdbc.update("UPDATE `institution_item` SET `status`=?, `updated_at`=? WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                to, LocalDateTime.now(), code, tid());
        jdbc.update("UPDATE `institution_version` SET `status`=? WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0",
                pass ? 3 : 6, code, v, tid());
        log(code, v, 2, to, pass ? "approve" : "reject", s(b == null ? null : b.get("remark")));
        return okState(code, pass ? "approve" : "reject");
    }

    /** 发布：盖章成品 PDF + 版本落库 + 登记电子档案柜盒二 */
    @PutMapping("/{code}/publish")
    public Result<Map<String, Object>> publish(@PathVariable String code, @RequestBody(required = false) Map<String, Object> b) {
        String[] d = dictOf(code);
        if (d == null) return reject("未知制度编码：" + code);
        if (statusOf(code) != 3) return reject("仅「待发布」可发布，当前：" + statusText(statusOf(code)));
        Map<String, Object> item = itemOrEmpty(code);
        String version = s(item.get("current_version"));
        String tplLabel = s(item.get("tpl_label"));
        String issuer = s(item.get("issuer_name"));
        String eff = date10(item.get("effective_date"));
        long tplId = longOf(item.get("tpl_id"));
        Map<String, Object> tpl = new LinkedHashMap<>();
        for (Map<String, Object> t : templatesOf(code)) if (longOf(t.get("id")) == tplId) tpl = t;

        byte[] pdf = buildDocPdf(code, d[1], ABBR + "-" + code, version, tplLabel, issuer, eff, tpl, true);
        String key = tid() + "/institution/" + code + "_" + version + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        jdbc.update("UPDATE `institution_version` SET `status`=4, `file_url`=? " +
                "WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0", key, code, version, tid());
        jdbc.update("UPDATE `institution_item` SET `status`=4, `file_url`=?, `updated_at`=? " +
                "WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0", key, LocalDateTime.now(), code, tid());

        List<Map<String, Object>> vrows = query("SELECT `id` FROM `institution_version` " +
                "WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0", code, version, tid());
        long versionId = vrows.isEmpty() ? IdUtil.getSnowflakeNextId() : longOf(vrows.get(0).get("id"));
        // 落 archive_pdf + pdf_id，使盒二柜视图可预览/下载该制度成品
        long pdfId = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `archive_pdf` " +
                        "(`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`,`template_code`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                pdfId, tid(), 0L, LocalDate.now().getYear(),
                ABBR + "-" + code + ".pdf", key, (long) pdf.length, "institution",
                LocalDateTime.now(), LocalDateTime.now(), uid());
        registrar.register("institution_" + code, BOX2_CATALOG_ID, versionId,
                ABBR + "-" + code + " " + d[1] + "（" + version + "）", 2);
        jdbc.update("UPDATE `archive_item` SET `pdf_id`=?, `status`=1 WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                pdfId, "institution_" + code, versionId, tid());
        log(code, version, 3, 4, "publish", "发布盖章成品并归档电子档案柜盒二" +
                (b == null ? "" : "；" + s(b.get("remark"))));

        Map<String, Object> out = new LinkedHashMap<>(okState(code, "publish").getData());
        out.put("version", version);
        out.put("objectKey", key);
        out.put("size", pdf.length);
        out.put("sealed", true);
        out.put("archivedTo", "BOX2");
        return Result.ok(out);
    }

    /** 修订：从「已发布」生成新版本，旧版本历史保留 */
    @PostMapping("/{code}/revise")
    public Result<Map<String, Object>> revise(@PathVariable String code, @RequestBody Map<String, Object> b) {
        String[] d = dictOf(code);
        if (d == null) return reject("未知制度编码：" + code);
        if (statusOf(code) != 4) return reject("仅「已发布」可发起修订，当前：" + statusText(statusOf(code)));
        Map<String, Object> old = itemOrEmpty(code);
        int seq = intOf(old.get("version_seq"), 0) + 1;
        String version = "v" + seq;
        String reason = s(b.get("reason"));
        String eff = date10(b.get("effectiveDate"));
        if (eff.isBlank()) eff = LocalDate.now().toString();
        String review = date10(b.get("reviewDate"));
        long tplId = longOf(b.get("tplId"));
        if (tplId == 0) tplId = longOf(old.get("tpl_id"));
        Map<String, Object> tpl = new LinkedHashMap<>();
        for (Map<String, Object> t : templatesOf(code)) if (longOf(t.get("id")) == tplId) tpl = t;
        String tplLabel = s(tpl.get("label"));
        if (tplLabel.isBlank()) tplLabel = s(old.get("tpl_label"));
        String issuer = s(old.get("issuer_name"));

        byte[] pdf = buildDocPdf(code, d[1], ABBR + "-" + code, version, tplLabel, issuer, eff, tpl, false);
        String key = tid() + "/institution/" + code + "_" + version + "_draft_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        jdbc.update("INSERT INTO `institution_version` " +
                        "(`id`,`tenant_id`,`item_code`,`version`,`version_seq`,`tpl_id`,`tpl_label`,`doc_no`," +
                        " `issuer_id`,`issuer_name`,`effective_date`,`review_date`,`reason`,`status`,`file_url`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), code, version, seq, tplId, tplLabel, ABBR + "-" + code,
                longOf(old.get("issuer_id")) == 0 ? null : longOf(old.get("issuer_id")), issuer,
                eff, dateParam(review), reason, key, LocalDateTime.now(), uid());
        upsertItem(code, d[1], d[2], 1, version, seq, tplId, tplLabel,
                longOf(old.get("issuer_id")) == 0 ? null : longOf(old.get("issuer_id")), issuer, eff, review, key);
        log(code, version, 4, 1, "revise", "修订生成 " + version + "：" + reason);

        Map<String, Object> out = new LinkedHashMap<>(okState(code, "revise").getData());
        out.put("version", version);
        out.put("objectKey", key);
        out.put("size", pdf.length);
        return Result.ok(out);
    }

    @PutMapping("/{code}/abolish")
    public Result<Map<String, Object>> abolish(@PathVariable String code, @RequestBody(required = false) Map<String, Object> b) {
        if (dictOf(code) == null) return reject("未知制度编码：" + code);
        if (statusOf(code) != 4) return reject("仅「已发布」可作废，当前：" + statusText(statusOf(code)));
        String v = s(itemOrEmpty(code).get("current_version"));
        jdbc.update("UPDATE `institution_item` SET `status`=5, `updated_at`=? WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                LocalDateTime.now(), code, tid());
        jdbc.update("UPDATE `institution_version` SET `status`=5 WHERE `item_code`=? AND `version`=? AND `tenant_id`=? AND `deleted`=0",
                code, v, tid());
        log(code, v, 4, 5, "abolish", s(b == null ? null : b.get("reason")));
        return okState(code, "abolish");
    }

    // ==================================================================
    // 4. 签署 / 盖章进度子表（原型 doc_publish.html）
    // ==================================================================
    @PostMapping("/{code}/sign")
    public Result<Map<String, Object>> addSign(@PathVariable String code, @RequestBody Map<String, Object> b) {
        if (dictOf(code) == null) return reject("未知制度编码：" + code);
        String signerName = s(b.get("signerName"));
        long signerId = longOf(b.get("signerId"));
        if (signerName.isBlank() && signerId != 0) {
            signerName = s(query("SELECT `name` FROM `person` WHERE `id`=? AND `deleted`=0", signerId)
                    .stream().findFirst().orElse(Map.of()).get("name"));
        }
        String type = s(b.get("signType"));
        if (type.isBlank()) type = "sign";
        String version = s(itemOrEmpty(code).get("current_version"));
        long id = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `institution_sign` " +
                        "(`id`,`tenant_id`,`item_code`,`version`,`signer_id`,`signer_name`,`sign_type`,`status`,`created_at`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,0,?,0)",
                id, tid(), code, version, signerId == 0 ? null : signerId,
                signerName.isBlank() ? "待指定" : signerName, type, LocalDateTime.now());
        log(code, version, statusOf(code), statusOf(code), "sign", "新增签署项：" + signerName + "（" + ("seal".equals(type) ? "盖章" : "签字") + "）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("id", id); out.put("itemCode", code);
        out.put("signType", type); out.put("signerName", signerName);
        return Result.ok(out);
    }

    @PutMapping("/{code}/sign/{signId}/done")
    public Result<Map<String, Object>> signDone(@PathVariable String code, @PathVariable long signId) {
        jdbc.update("UPDATE `institution_sign` SET `status`=1, `signed_at`=? " +
                "WHERE `id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                LocalDateTime.now(), signId, code, tid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("id", signId); out.put("itemCode", code);
        return Result.ok(out);
    }

    // ==================================================================
    // 5. 成品 PDF（制度文件头 + 范本正文 + 签发人 + 落款 + 印章）
    // ==================================================================
    private byte[] buildDocPdf(String code, String name, String docNo, String version, String tplLabel,
                               String issuer, String eff, Map<String, Object> tpl, boolean sealed) {
        String content = "";
        if (!tpl.isEmpty()) {
            try {
                List<Map<String, Object>> rows = query("SELECT `content` FROM `tpl_template` WHERE `id`=? AND `deleted`=0",
                        longOf(tpl.get("id")));
                if (!rows.isEmpty()) content = s(rows.get(0).get("content"));
            } catch (Exception ignore) { }
        }
        if (content.isBlank()) {
            content = "<div class=\"doc-h1\">1.总则</div><div class=\"doc-p\">为加强本公司安全生产管理，落实企业安全生产主体责任，依据《中华人民共和国安全生产法》等法律法规，结合公司实际，特制定本制度。</div>"
                    + "<div class=\"doc-h1\">2.适用范围</div><div class=\"doc-p\">本公司各部门及全体从业人员，自实施之日起执行。</div>";
        }
        Map<String, String> vars = render.defaultVars(company(), LocalDate.now().getYear(), "主要负责人", name);
        vars.put("doc_no", docNo);
        vars.put("issuer", esc(issuer.isBlank() ? "主要负责人" : issuer));
        vars.put("实施日期", eff.isBlank() ? LocalDate.now().toString() : eff);
        vars.put("effect_date", eff.isBlank() ? LocalDate.now().toString() : eff);
        vars.put("发布日期", eff.isBlank() ? LocalDate.now().toString() : eff);

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"doc-no\">文件编号：").append(esc(docNo)).append("　版本号：").append(esc(version)).append("</div>");
        body.append("<div class=\"doc-h0\">").append(esc(name)).append("</div>");
        body.append("<table class=\"doc-tbl\"><tr><th>实施日期</th><td>")
                .append(esc(eff.isBlank() ? LocalDate.now().toString() : eff))
                .append("</td><th>签 发 人</th><td>").append(esc(issuer.isBlank() ? "主要负责人" : issuer))
                .append("</td><th>范本版本</th><td>").append(esc(tplLabel.isBlank() ? "内置版" : tplLabel))
                .append("</td></tr></table>");
        body.append("<div class=\"doc-p\">为加强本公司安全生产管理，依据国家法律法规及企业实际，发布《")
                .append(esc(name)).append("》，自实施之日起执行。</div>");
        body.append(content);
        body.append("<div class=\"doc-sign\"><div class=\"sign-zone\"><div>")
                .append(esc(company())).append("（盖章）</div><div>")
                .append(eff.isBlank() ? LocalDate.now().toString() : eff).append("</div>");
        if (sealed) body.append(sealService.sealHtml());
        body.append("</div></div>");
        if (!sealed) body.append("<div class=\"doc-p\" style=\"color:#999;font-size:9pt\">（草稿 · 未盖章，仅供审核预览）</div>");
        return pdfService.render(render.renderDocument(body.toString(), vars));
    }

    /** 在线查看 / 下载制度 PDF（默认当前版本、已发布盖章态） */
    @GetMapping(value = "/{code}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String code,
                                      @RequestParam(required = false) String version,
                                      @RequestParam(defaultValue = "true") boolean sealed) {
        String[] d = dictOf(code);
        String name = d == null ? "制度文件" : d[1];
        Map<String, Object> item = itemOrEmpty(code);
        String v = version == null || version.isBlank() ? s(item.get("current_version")) : version;
        String docNo = ABBR + "-" + code;
        String tplLabel = s(item.get("tpl_label"));
        String issuer = s(item.get("issuer_name"));
        String eff = date10(item.get("effective_date"));
        long tplId = longOf(item.get("tpl_id"));
        Map<String, Object> tpl = new LinkedHashMap<>();
        for (Map<String, Object> t : templatesOf(code)) if (longOf(t.get("id")) == tplId) tpl = t;
        byte[] bytes = buildDocPdf(code, name, docNo, v, tplLabel, issuer, eff, tpl, sealed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"institution_" + code + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 第 25 项「安全生产管理制度目录」：按盒二已发布制度自动生成（设计文档 7.3.0） */
    @PostMapping("/toc")
    public Result<Map<String, Object>> toc() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) buildCatalog().get("items");
        StringBuilder body = new StringBuilder();
        body.append("<div class=\"doc-h0\">安全生产管理制度目录</div>");
        body.append("<div class=\"doc-p\">本目录所列制度编号采用「公司字母简写 + ZD + 两位序号」规则自动生成（当前简写：")
                .append(esc(ABBR)).append("），由系统按盒二实际发布制度实时生成。</div>");
        body.append("<div class=\"doc-h1\">一、安全生产管理制度清单（共 ").append(items.size()).append(" 项）</div>");
        body.append("<table class=\"doc-tbl\"><tr><th>序号</th><th>制度编号</th><th>制度名称</th><th>状态</th><th>当前版本</th></tr>");
        int i = 1;
        for (Map<String, Object> it : items) {
            body.append("<tr><td>").append(i++).append("</td><td>").append(esc(s(it.get("docNo"))))
                    .append("</td><td>").append(esc(s(it.get("itemName"))))
                    .append("</td><td>").append(esc(s(it.get("statusText"))))
                    .append("</td><td>").append(esc(s(it.get("version")))).append("</td></tr>");
        }
        body.append("</table><div class=\"doc-sign\"><div class=\"sign-zone\"><div>")
                .append(esc(company())).append("（盖章）</div><div>").append(LocalDate.now())
                .append("</div>").append(sealService.sealHtml()).append("</div></div>");
        Map<String, String> vars = render.defaultVars(company(), LocalDate.now().getYear(), "主要负责人", "安全生产管理制度目录");
        byte[] pdf = pdfService.render(render.renderDocument(body.toString(), vars));
        String key = tid() + "/institution/TOC_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        long tocId = IdUtil.getSnowflakeNextId();
        long pdfId = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `archive_pdf` " +
                        "(`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`,`template_code`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                pdfId, tid(), 0L, LocalDate.now().getYear(),
                "安全生产管理制度目录.pdf", key, (long) pdf.length, "institution",
                LocalDateTime.now(), LocalDateTime.now(), uid());
        registrar.register("institution_TOC", BOX2_CATALOG_ID, tocId, "安全生产管理制度目录（按盒二实际制度自动生成）", 2);
        jdbc.update("UPDATE `archive_item` SET `pdf_id`=?, `status`=1 WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                pdfId, "institution_TOC", tocId, tid());
        log("TOC", "-", 4, 4, "toc", "自动生成制度目录并归档盒二");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("itemCount", items.size());
        out.put("published", buildCatalog().get("published"));
        out.put("objectKey", key); out.put("size", pdf.length);
        out.put("archivedTo", "BOX2");
        return Result.ok(out);
    }

    // ==================================================================
    // 6. 制度包拼装（详细设计 09 §7.3 / §7.4 / §7.5）
    // ==================================================================
    @PostMapping("/assemble")
    public Result<Map<String, Object>> assemble(@RequestBody(required = false) Map<String, Object> b) {
        String biz = s(b == null ? null : b.get("bizType")).toUpperCase();
        if (biz.isBlank()) biz = "GENERAL";
        boolean danger = "DANGER".equals(biz);
        List<String> types = new ArrayList<>();
        Object raw = b == null ? null : b.get("vehicleTypes");
        if (raw instanceof Collection<?> c) for (Object x : c) types.add(s(x));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("bizType", biz);
        out.put("vehicleTypes", types);
        out.put("company", company());
        List<Map<String, Object>> layers = new ArrayList<>();
        Map<String, Object> l1 = new LinkedHashMap<>();
        l1.put("layer", "① 通用制度层");
        l1.put("desc", "所有企业共用的 1 套通用安全管理制度，不因车型变化");
        l1.put("count", 22);
        l1.put("codes", Arrays.stream(ITEMS).limit(22).map(x -> x[0]).toList());
        layers.add(l1);

        Map<String, Object> l2 = new LinkedHashMap<>();
        if (danger) {
            l2.put("layer", "② 专项规程层（危货两层结构）");
            l2.put("desc", "车辆结构 × 作业环节/岗位交叉装配；危险货物类别降级为条件性附加条款");
            l2.put("vehicleStruct", List.of(DANGER_STRUCT));
            l2.put("stages", List.of(DANGER_STAGE));
            l2.put("count", DANGER_STRUCT.length * DANGER_STAGE.length);
            l2.put("note", "ZD23/ZD24 为普货版，危货须替换为危货专项规程（建议采用「危货通用版」范本）");
            l2.put("recommendTpl", "危货通用版");
        } else {
            l2.put("layer", "② 专项规程层（普货 7 大类）");
            l2.put("desc", "按作业特点把 14+ 种车型收敛为 7 个制度大类，同类车型共用一套规程");
            List<Map<String, Object>> classes = new ArrayList<>();
            for (String[] c : GENERAL_CLASSES) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c[0]); m.put("vehicles", c[1]);
                m.put("applicable", types.isEmpty() || Arrays.stream(c[1].split("、")).anyMatch(types::contains));
                classes.add(m);
            }
            l2.put("classes", classes);
            l2.put("count", GENERAL_CLASSES.length);
            l2.put("codes", List.of("ZD23", "ZD24"));
        }
        layers.add(l2);

        Map<String, Object> l3 = new LinkedHashMap<>();
        l3.put("layer", "③ 附件层");
        l3.put("desc", "特殊业务用附件补充，不重做整套制度；应急预案按车型一套（危货占位）");
        l3.put("count", 1);
        layers.add(l3);
        out.put("layers", layers);
        out.put("totalLayers", layers.size());
        out.put("note", danger
                ? "危货制度最终需结合企业实际承运的危险货物类别及适用法规审核"
                : "普货可较大程度通用；新增车型只判断是否涉及新风险/新操作要求，无须重编整套制度");
        return Result.ok(out);
    }

    // ==================================================================
    // 6.5 通用产物归档辅助：覆盖式登记「生成型」归档项（支持重复生成刷新）
    // ==================================================================
    /** 生成型文档（制度目录 / 法律法规标准目录 / 制度汇编）的归档项 bizId 固定，重新生成即刷新 */
    private long LAW_CATALOG_BIZ = 990001L;
    private long ASSEMBLE_BIZ = 990002L;

    /**
     * 生成型文档（制度目录 / 法律法规标准目录 / 制度汇编）的归档：上传 MinIO 后登记 archive_item，
     * 并同步落 archive_pdf + pdf_id，使档案柜柜视图可预览/下载。覆盖式：软删旧项后重新登记。
     */
    private String registerGeneratedDoc(String moduleCode, String title, String boxCode,
                                       byte[] pdf, int source, long bizId) {
        String key = tid() + "/institution/" + moduleCode + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        long pdfId = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `archive_pdf` " +
                        "(`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`,`template_code`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                pdfId, tid(), 0L, LocalDate.now().getYear(),
                title + ".pdf", key, (long) pdf.length, "institution",
                LocalDateTime.now(), LocalDateTime.now(), uid());
        // 覆盖式：先软删旧的同 biz_module+biz_id，再登记新的一条（保留历史可追溯）
        jdbc.update("UPDATE `archive_item` SET `deleted`=1 WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                moduleCode, bizId, tid());
        registrar.register(moduleCode, BOX2_CATALOG_ID, bizId, title, source);
        // 回填最新 archive_item 的 pdf_id / status，使柜视图可下载
        jdbc.update("UPDATE `archive_item` SET `pdf_id`=?, `status`=1 WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                pdfId, moduleCode, bizId, tid());
        return key;
    }

    // ==================================================================
    // 6.6 适用安全生产法律法规标准目录清单（用户 2026-11-12 需求一）
    // 盒二制度所引用的法律法规/标准，生成 PDF 版目录并归档盒二。
    // ==================================================================
    @PostMapping("/law-catalog")
    public Result<Map<String, Object>> lawCatalog() {
        List<Map<String, Object>> laws = query(
                "SELECT `law_name`,`law_no`,`apply_level`,`region`,`category`,`biz_type`,`effective_date`,`remark`,`refs` " +
                        "FROM `law_catalog` WHERE `tenant_id`=0 AND `deleted`=0 AND `is_applicable`<>'no' " +
                        "ORDER BY `category`, `sort_no`");
        if (laws.isEmpty()) return reject("法规清单库暂无数据，无法生成法律法规标准目录");
        byte[] pdf = pdfService.render(buildLawCatalogHtml(laws));
        // 标题须与盒二目录占位项「适用安全生产法律法规标准目录清单」完全一致，才能在档案柜柜视图按标题归并到对应行
        String title = "适用安全生产法律法规标准目录清单";
        String key = registerGeneratedDoc("institution_law_catalog", title, "BOX2", pdf, 2, LAW_CATALOG_BIZ);
        log("LAW_CAT", "-", 4, 4, "law-catalog", "生成法规标准目录并归档盒二，共 " + laws.size() + " 部");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("count", laws.size());
        out.put("objectKey", key); out.put("size", pdf.length); out.put("archivedTo", "BOX2");
        return Result.ok(out);
    }

    private String buildLawCatalogHtml(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String cat = s(r.get("category"));
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }
        // 制度→法规反查：law_catalog.refs 存「法规引用制度」，反推被盒二制度引用的法规
        Map<String, String> citedBy = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : rows) {
                String refs = s(r.get("refs"));
                if (refs.isBlank()) continue;
                List<Map<String, String>> list = parseRefs(refs);
                if (list.isEmpty()) continue;
                citedBy.put(s(r.get("law_name")), list.stream().map(m -> m.get("name")).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("、")));
            }
        } catch (Exception ignore) { }
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:40px;color:#222}")
                .append("h1{text-align:center;font-size:22px;letter-spacing:2px;margin:0 0 4px}")
                .append(".sub{text-align:center;font-size:12px;color:#666;margin-bottom:20px}")
                .append("h2{font-size:15px;border-left:4px solid #1a5bb0;padding-left:10px;margin:22px 0 10px}")
                .append("table{width:100%;border-collapse:collapse;font-size:11px}")
                .append("th,td{border:1px solid #888;padding:6px 8px;text-align:left;vertical-align:top}")
                .append("th{background:#eef3fb;font-weight:600}")
                .append(".no{width:34px;text-align:center}.name{font-weight:600}.lv{width:46px;text-align:center}")
                .append(".biz{width:52px;text-align:center}.date{width:82px}.cite{color:#1a5bb0}")
                .append("</style></head><body>");
        h.append("<h1>适用安全生产法律法规标准目录清单</h1>")
                .append("<div class=\"sub\">本清单为盒二制度档案所引用的现行有效法律法规与标准 · 共 ").append(rows.size()).append(" 部 · 生成时间 ")
                .append(LocalDateTime.now().toString().replace("T", " ").substring(0, 16)).append("</div>");
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            h.append("<h2>").append(esc(e.getKey())).append("（").append(e.getValue().size()).append("）</h2>");
            h.append("<table><thead><tr><th class=\"no\">序号</th><th>法规/标准名称</th><th>文号/版本</th>")
                    .append("<th class=\"lv\">层级</th><th>适用地区</th><th class=\"biz\">业务</th>")
                    .append("<th class=\"date\">施行日期</th><th>被盒二制度引用</th></tr></thead><tbody>");
            int i = 0;
            for (Map<String, Object> r : e.getValue()) {
                i++;
                String biz = switch (s(r.get("biz_type"))) {
                    case "danger" -> "危货";
                    case "cargo" -> "普货";
                    default -> "通用";
                };
                String cite = citedBy.getOrDefault(s(r.get("law_name")), "");
                h.append("<tr><td class=\"no\">").append(i).append("</td>")
                        .append("<td class=\"name\">").append(esc(s(r.get("law_name")))).append("</td>")
                        .append("<td>").append(esc(s(r.get("law_no")))).append("</td>")
                        .append("<td class=\"lv\">").append(esc(s(r.get("apply_level")))).append("</td>")
                        .append("<td>").append(esc(s(r.get("region")))).append("</td>")
                        .append("<td class=\"biz\">").append(biz).append("</td>")
                        .append("<td class=\"date\">").append(esc(s(r.get("effective_date")))).append("</td>")
                        .append("<td class=\"cite\">").append(cite.isBlank() ? "—" : esc(cite)).append("</td>")
                        .append("</tr>");
            }
            h.append("</tbody></table>");
        }
        h.append("<div class=\"doc-sign\" style=\"margin-top:18px\"><div class=\"sign-zone\"><div>")
                .append(esc(company())).append("（盖章）</div><div>").append(LocalDate.now())
                .append("</div>").append(sealService.sealHtml()).append("</div></div>");
        h.append("</body></html>");
        return h.toString();
    }

    // ==================================================================
    // 6.7 制度汇编（含操作规程）（用户 2026-11-12 需求二）
    // 盒二已发布制度的成品 PDF 合并为一份汇编，归档盒二。
    // ==================================================================
    @PostMapping("/assemble-pdf")
    public Result<Map<String, Object>> assemblePdf(@RequestBody(required = false) Map<String, Object> b) {
        String biz = s(b == null ? null : b.get("bizType")).toUpperCase();
        if (biz.isBlank()) biz = "GENERAL";
        List<Map<String, Object>> published = query(
                "SELECT `item_code`,`item_name`,`current_version`,`file_url` FROM `institution_item` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 AND `status`=4 AND `file_url` IS NOT NULL AND `file_url`<>'' ORDER BY `item_code`",
                tid());
        if (published.isEmpty()) return reject("尚无已发布制度，无法生成制度汇编");

        List<byte[]> parts = new ArrayList<>();
        for (Map<String, Object> it : published) {
            try {
                byte[] d = storage.download(s(it.get("file_url")));
                if (d != null && d.length > 0) parts.add(d);
            } catch (Exception ignore) { }
        }
        if (parts.isEmpty()) return reject("已发布制度暂无可用的成品 PDF");
        try {
            byte[] cover = buildAssembleCover(published, biz);
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.addSource(new java.io.ByteArrayInputStream(cover));
            for (byte[] p : parts) merger.addSource(new java.io.ByteArrayInputStream(p));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            merger.setDestinationStream(bos);
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
            byte[] pdf = bos.toByteArray();
            // 标题须与盒二目录占位项「制度汇编（含操作规程）」完全一致，才能在档案柜柜视图按标题归并到对应行
            String title = "制度汇编（含操作规程）";
            String key = registerGeneratedDoc("institution_assemble", title, "BOX2", pdf, 2, ASSEMBLE_BIZ);
            log("ASSEMBLE", "-", 4, 4, "assemble-pdf", "生成制度汇编并归档盒二，共 " + published.size() + " 项");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("count", published.size());
            out.put("objectKey", key); out.put("size", pdf.length); out.put("archivedTo", "BOX2");
            return Result.ok(out);
        } catch (Exception e) {
            throw new BizException("制度汇编合并失败：" + e.getMessage());
        }
    }

    private byte[] buildAssembleCover(List<Map<String, Object>> items, String biz) {
        StringBuilder body = new StringBuilder();
        body.append("<div style=\"text-align:center;margin-top:140px\">")
                .append("<div style=\"font-size:30px;letter-spacing:4px;font-weight:700\">").append(esc(company())).append("</div>")
                .append("<div style=\"font-size:17px;margin-top:14px;font-weight:600\">安全生产管理制度汇编（含操作规程）</div>")
                .append("<div style=\"font-size:14px;color:#444;margin-top:8px\">")
                .append(biz.equals("DANGER") ? "危险货物运输专项" : "普货通用").append(" · 共 ").append(items.size()).append(" 项 · ").append(LocalDate.now().getYear()).append(" 版</div>")
                .append("<div class=\"doc-sign\" style=\"margin-top:40px\"><div class=\"sign-zone\"><div>")
                .append(esc(company())).append("（盖章）</div><div>").append(LocalDate.now())
                .append("</div>").append(sealService.sealHtml()).append("</div></div>")
                .append("</div>");
        body.append("<h2 style=\"font-size:16px;border-left:4px solid #1a5bb0;padding-left:10px;margin:30px 0 12px\">制度清单（共 ").append(items.size()).append(" 项）</h2>");
        body.append("<table class=\"doc-tbl\"><thead><tr><th class=\"no\">序号</th><th>制度编号</th><th>制度名称</th><th>版本</th></tr></thead><tbody>");
        int i = 0;
        for (Map<String, Object> it : items) {
            i++;
            body.append("<tr><td class=\"no\">").append(i).append("</td>")
                    .append("<td>").append(esc(ABBR + "-" + s(it.get("item_code")))).append("</td>")
                    .append("<td>").append(esc(s(it.get("item_name")))).append("</td>")
                    .append("<td>").append(esc(s(it.get("current_version")))).append("</td></tr>");
        }
        body.append("</tbody></table>");
        Map<String, String> vars = render.defaultVars(company(), LocalDate.now().getYear(), "主要负责人", "制度汇编");
        return pdfService.render(render.renderDocument(body.toString(), vars));
    }

    // ==================================================================
    // 6.8 服务商强制生成并归档（用户 2026-11-12 需求三·来源3）
    // 服务商视角：调用指定企业的印章 + 主要负责人签字信息，代企业生成成品并归档到该企业。
    // ==================================================================
    @RequireRole({ "provider", "admin" })
    @PostMapping("/{code}/force-publish")
    public Result<Map<String, Object>> forcePublish(@PathVariable String code,
                                                   @RequestBody(required = false) Map<String, Object> b) {
        String[] d = dictOf(code);
        if (d == null) return reject("未知制度编码：" + code);
        long targetTenant = longOf(b == null ? null : b.get("tenantId"));
        if (targetTenant <= 0) return reject("缺少目标企业 tenantId");
        // 校验目标企业存在
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `sys_tenant` WHERE `id`=? AND `deleted`=0", Integer.class, targetTenant);
        if (exists == null || exists == 0) return reject("目标企业不存在（tenantId=" + targetTenant + "）");

        // 临时切换租户上下文：后续 buildDocPdf 套用目标企业的印章与签发人信息
        TenantContextHolder.Context ctx = new TenantContextHolder.Context();
        ctx.setTenantId(targetTenant);
        ctx.setUserType("provider");
        TenantContextHolder.Context prev = TenantContextHolder.get();
        TenantContextHolder.set(ctx);
        try {
            // 取目标企业「主要负责人」姓名作签发人
            String issuer = "";
            try {
                issuer = s(jdbc.queryForObject(
                        "SELECT `name` FROM `person` WHERE `tenant_id`=? AND `person_type` IN ('legal','principal','leader') AND `deleted`=0 LIMIT 1",
                        String.class, targetTenant));
            } catch (Exception ignore) { }
            if (issuer.isBlank()) issuer = companyOf(targetTenant);
            String version = s(b == null ? null : b.get("version"));
            if (version.isBlank()) {
                try {
                    Map<String, Object> it = jdbc.queryForMap(
                            "SELECT `current_version`,`tpl_label`,`tpl_id`,`effective_date` FROM `institution_item` WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                            code, targetTenant);
                    version = s(it.get("current_version"));
                } catch (Exception ignore) { }
            }
            if (version.isBlank()) version = "v1";
            Map<String, Object> tpl = new LinkedHashMap<>();
            for (Map<String, Object> t : templatesOf(code)) tpl = t; // 复用当前租户范本（服务商共享范本）
            byte[] pdf = buildDocPdf(code, d[1], ABBR + "-" + code, version, s(tpl.get("label")), issuer,
                    LocalDate.now().toString(), tpl, true);
            String key = targetTenant + "/institution/" + code + "_" + version + "_force_" + System.currentTimeMillis() + ".pdf";
            storage.upload(key, pdf, "application/pdf");
            // 更新目标企业制度状态为已发布 + 成品文件
            jdbc.update("UPDATE `institution_item` SET `status`=4, `file_url`=?, `issuer_name`=?, `updated_at`=? " +
                            "WHERE `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                    key, issuer, LocalDateTime.now(), code, targetTenant);
            long versionId = IdUtil.getSnowflakeNextId();
            // 落 archive_pdf + pdf_id，使目标企业盒二柜视图可预览/下载该制度成品
            long pdfId = IdUtil.getSnowflakeNextId();
            jdbc.update("INSERT INTO `archive_pdf` " +
                            "(`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`,`template_code`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    pdfId, targetTenant, 0L, LocalDate.now().getYear(),
                    ABBR + "-" + code + ".pdf", key, (long) pdf.length, "institution",
                    LocalDateTime.now(), LocalDateTime.now(), uid());
            registrar.register("institution_" + code, BOX2_CATALOG_ID, versionId,
                    ABBR + "-" + code + " " + d[1] + "（" + version + "）", targetTenant, 3);
            jdbc.update("UPDATE `archive_item` SET `pdf_id`=?, `status`=1 WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                    pdfId, "institution_" + code, versionId, targetTenant);
            log(code, version, 0, 4, "force-publish", "服务商强制生成并归档到企业 tenantId=" + targetTenant);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("tenantId", targetTenant);
            out.put("version", version); out.put("objectKey", key);
            out.put("size", pdf.length); out.put("sealed", true); out.put("archivedTo", "BOX2");
            out.put("source", 3);
            return Result.ok(out);
        } finally {
            if (prev != null) TenantContextHolder.set(prev); else TenantContextHolder.clear();
        }
    }

    private String companyOf(long tenantId) {
        try {
            return s(jdbc.queryForObject("SELECT `name` FROM `sys_tenant` WHERE `id`=?", String.class, tenantId));
        } catch (Exception e) { return "企业"; }
    }

    // ==================================================================
    // 7. 复审到期预警 + 小程序预留数据接口（仅 API，不做小程序页面）
    // ==================================================================
    private List<Map<String, Object>> expiringList(int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT `item_code`,`item_name`,`doc_no`,`current_version`,`review_date` FROM `institution_item` " +
                            "WHERE `tenant_id`=? AND `deleted`=0 AND `status`=4 AND `review_date` IS NOT NULL " +
                            "AND `review_date` <= ? ORDER BY `review_date`",
                    tid(), LocalDate.now().plusDays(days).toString())) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                String exp = date10(r.get("review_date"));
                int left;
                try { left = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp)); }
                catch (Exception e) { continue; }
                row.put("daysLeft", left);
                row.put("level", left < 0 ? "expired" : left <= 7 ? "d7" : left <= 15 ? "d15" : "d30");
                out.add(row);
            }
        } catch (Exception ignore) { }
        return out;
    }

    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", expiringList(days));
        out.put("total", expiringList(days).size());
        return Result.ok(out);
    }

    /** 小程序预留：待我审核 / 待我签署 / 复审临期，统一数据契约，后续小程序统一做页面 */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) Long personId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("toReview", query("SELECT `item_code`,`item_name`,`current_version` FROM `institution_item` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `status`=2", tid()).size());
        } catch (Exception e) { out.put("toReview", 0); }
        try {
            Object arg = personId == null ? -1L : personId;
            out.put("toSign", query("SELECT `id` FROM `institution_sign` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `status`=0 AND (`signer_id`=? OR `signer_id` IS NULL)",
                    tid(), arg).size());
        } catch (Exception e) { out.put("toSign", 0); }
        out.put("reviewDue", expiringList(30).size());
        return Result.ok(out);
    }

    // ==================================================================
    // 8. 模块间联动：已发布制度作为其他模块数据源 + 引用清单
    // ==================================================================
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.ok(query("SELECT `item_code`,`item_name`,`doc_no`,`current_version`,`effective_date` " +
                "FROM `institution_item` WHERE `tenant_id`=? AND `deleted`=0 AND `status`=4 ORDER BY `item_code`", tid()));
    }

    @GetMapping("/{code}/references")
    public Result<List<Map<String, Object>>> references(@PathVariable String code) {
        if (dictOf(code) == null) return Result.ok(new ArrayList<>());
        return Result.ok(referencesOf(code));
    }
}
