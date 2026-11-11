package com.safetrans.archive.service;

import cn.hutool.core.util.IdUtil;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.service.SealService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三书签订（承诺书 / 岗位危害告知书）——用户 2026-11-11 反馈「三书是动态记录」。
 *
 * <p>三种书通过「签订动作」产生（不是静态范本一次性生成）：
 * <ul>
 *   <li><b>安全生产责任书</b>：上下级签订后生成（沿用 {@link ResponsibilitySignService} 双签）；</li>
 *   <li><b>安全生产承诺书</b>：与公司签订后生成（公司盖章 + 本人签字，一次签署即生效）；</li>
 *   <li><b>岗位危害告知书</b>：本人签订后生成（本人签字即生效）；</li>
 * </ul>
 * 服务商 / 超管视角（企业之上）可直接从范本生成成品（不走签订，盖公司章 + 空白签字栏）。
 * 签订或生成后的 PDF 同步落到 person_archive_item（P06 / P07）的 file_url，供一人一档预览。
 */
@Service
public class TripleBookService {

    private static final Map<String, String> BOOK = Map.of(
            "PROMISE", "安全生产承诺书",
            "NOTICE", "岗位危害告知书");

    private static final Map<String, String> ITEM_CODE = Map.of(
            "PROMISE", "P06",
            "NOTICE", "P07");

    private final JdbcTemplate jdbc;
    private final ArchiveService archiveService;
    private final PdfService pdfService;
    private final SealService sealService;
    private final TenantService tenantService;

    public TripleBookService(JdbcTemplate jdbc, ArchiveService archiveService,
                             PdfService pdfService, SealService sealService,
                             TenantService tenantService) {
        this.jdbc = jdbc;
        this.archiveService = archiveService;
        this.pdfService = pdfService;
        this.sealService = sealService;
        this.tenantService = tenantService;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String titleOf(String bookType) {
        return BOOK.getOrDefault(bookType, "三书");
    }

    private String itemCodeOf(String bookType) {
        String c = ITEM_CODE.get(bookType);
        if (c == null) throw new BizException("不支持的签订类型：" + bookType);
        return c;
    }

    /** 应签人员：组织架构中启用岗位上的当前人员（与责任书双签范围一致）。 */
    private List<Map<String, Object>> signers() {
        return jdbc.queryForList(
                "SELECT DISTINCT p.`id` AS `person_id`, p.`name` AS `person_name`, " +
                "o.`id` AS `post_id`, o.`post_name` " +
                "FROM `org_member` m " +
                "JOIN `person` p ON p.`id`=m.`user_id` AND p.`deleted`=0 " +
                "JOIN `org_post` o ON o.`id`=m.`post_id` AND o.`deleted`=0 AND o.`enabled`=1 " +
                "WHERE m.`tenant_id`=? AND m.`deleted`=0 " +
                "ORDER BY o.`id`, p.`id`", tid());
    }

    private Map<String, Object> personOf(long personId) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT p.`id` AS `person_id`, p.`name` AS `person_name`, " +
                "GROUP_CONCAT(DISTINCT o.`post_name` ORDER BY o.`id` SEPARATOR '、') AS `post_name` " +
                "FROM `person` p " +
                "LEFT JOIN `org_member` m ON m.`user_id`=p.`id` AND m.`tenant_id`=p.`tenant_id` AND m.`deleted`=0 " +
                "LEFT JOIN `org_post` o ON o.`id`=m.`post_id` AND o.`deleted`=0 AND o.`enabled`=1 " +
                "WHERE p.`id`=? AND p.`tenant_id`=? AND p.`deleted`=0 GROUP BY p.`id`",
                personId, tid());
        if (list.isEmpty()) throw new BizException("人员不存在或未录入：" + personId);
        return list.get(0);
    }

    // ==================================================================
    // 1. 查询
    // ==================================================================

    /** 某类型书的本年度签订总览（每人一条，含已签 / 待签状态）。 */
    public Map<String, Object> overview(String bookType, int year) {
        String title = titleOf(bookType);
        long t = tid();
        List<Map<String, Object>> rows = new ArrayList<>();
        int effective = 0;
        for (Map<String, Object> p : signers()) {
            long pid = num(p.get("person_id"));
            List<Map<String, Object>> rec = jdbc.queryForList(
                    "SELECT * FROM `triple_book_sign` WHERE `tenant_id`=? AND `sign_year`=? " +
                    "AND `book_type`=? AND `person_id`=? AND `deleted`=0 AND `status`<>'void' " +
                    "ORDER BY `id` DESC LIMIT 1", t, year, bookType, pid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("personId", pid);
            row.put("personName", s(p.get("person_name")));
            row.put("postName", s(p.get("post_name")));
            boolean signed = !rec.isEmpty() && "effective".equals(s(rec.get(0).get("status")));
            if (rec.isEmpty()) {
                row.put("id", null);
                row.put("status", "pending");
                row.put("statusLabel", "待签订");
                row.put("effectiveAt", null);
                row.put("pdfId", null);
                row.put("fileUrl", null);
                row.put("signName", null);
            } else {
                Map<String, Object> r = rec.get(0);
                row.put("id", num(r.get("id")));
                row.put("status", s(r.get("status")));
                row.put("statusLabel", "effective".equals(s(r.get("status"))) ? "已签订" : "待签订");
                row.put("effectiveAt", r.get("effective_at"));
                row.put("pdfId", r.get("pdf_id"));
                row.put("fileUrl", s(r.get("file_url")));
                row.put("signName", s(r.get("sign_name")));
            }
            if (signed) effective++;
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("bookType", bookType);
        out.put("title", title);
        out.put("company", tenantService.currentTenantName());
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("effective", effective);
        return out;
    }

    // ==================================================================
    // 2. 签署（企业视角：签订动作产生动态记录）
    // ==================================================================

    /**
     * 单次签署即生效：
     * <ul><li>承诺书（PROMISE）= 公司盖章 + 本人签字；</li>
     *     <li>告知书（NOTICE）= 本人签字。</li></ul>
     * 生成 PDF 后落到 person_archive_item（P06 / P07）。
     */
    @Transactional
    public Map<String, Object> sign(String bookType, long personId, int year, String signName) {
        String title = titleOf(bookType);
        Map<String, Object> p = personOf(personId);
        String name = signName == null || signName.isBlank()
                ? s(p.get("person_name")) : signName.trim();
        String text = esc(name) + "（已电子签署 " + LocalDate.now() + "）";
        byte[] bytes = render(title, p, year, name, text, true);

        ArchivePdf pdf = archiveService.generateByBytes(
                title + "-" + s(p.get("person_name")), 1, year, bytes,
                "TRIPLE_BOOK_SIGN", personId, "triple_book_sign");
        String fileUrl = pdf.getFilePath();
        upsertPersonItem(personId, itemCodeOf(bookType), fileUrl, "sign");

        long id = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `triple_book_sign` " +
                "(`id`,`tenant_id`,`sign_year`,`book_type`,`person_id`,`person_name`,`post_id`,`post_name`," +
                " `company_name`,`sign_name`,`sign_image`,`status`,`effective_at`,`pdf_id`,`file_url`," +
                " `created_at`,`updated_at`,`created_by`,`deleted`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,'effective',NOW(),?,?,NOW(),NOW(),0,0)",
                id, tid(), year, bookType, personId, s(p.get("person_name")),
                p.get("post_id"), s(p.get("post_name")),
                tenantService.currentTenantName(), name, text, pdf.getId(), fileUrl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("personId", personId);
        out.put("personName", s(p.get("person_name")));
        out.put("title", title);
        out.put("status", "effective");
        out.put("statusLabel", "已签订");
        out.put("pdfId", pdf.getId());
        out.put("fileUrl", fileUrl);
        return out;
    }

    // ==================================================================
    // 3. 服务商 / 超管：从范本直接生成成品（不走签订）
    // ==================================================================

    @Transactional
    public Map<String, Object> generate(String bookType, long personId, int year) {
        String title = titleOf(bookType);
        Map<String, Object> p = personOf(personId);
        byte[] bytes = render(title, p, year, s(p.get("person_name")), "", false);

        ArchivePdf pdf = archiveService.generateByBytes(
                title + "-" + s(p.get("person_name")), 1, year, bytes,
                "TRIPLE_BOOK_TPL", personId, "triple_book_sign");
        String fileUrl = pdf.getFilePath();
        upsertPersonItem(personId, itemCodeOf(bookType), fileUrl, "tpl");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personId", personId);
        out.put("personName", s(p.get("person_name")));
        out.put("title", title);
        out.put("pdfId", pdf.getId());
        out.put("fileUrl", fileUrl);
        return out;
    }

    // ==================================================================
    // 4. 落一人一档
    // ==================================================================

    private void upsertPersonItem(long personId, String itemCode, String fileUrl, String sourceType) {
        long t = tid();
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? " +
                "AND `tenant_id`=? AND `deleted`=0", Long.class, personId, itemCode, t);
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO `person_archive_item` " +
                    "(`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`," +
                    " `status`,`created_at`,`created_by`,`deleted`) " +
                    "VALUES (?,?,?,?,?,NULL,?,NULL,1,NOW(),0,0)",
                    IdUtil.getSnowflakeNextId(), t, personId, itemCode, sourceType, fileUrl);
        } else {
            jdbc.update("UPDATE `person_archive_item` SET `file_url`=?, `source_type`=?, `status`=1, " +
                    "`updated_at`=NOW() WHERE `id`=?", fileUrl, sourceType, ids.get(0));
        }
    }

    // ==================================================================
    // 5. PDF 渲染（统一三书版式 + 公司章 + 签字栏）
    // ==================================================================

    private byte[] render(String title, Map<String, Object> p, int year,
                          String signName, String signImage, boolean signed) {
        String company = esc(tenantService.currentTenantName());
        String personName = esc(s(p.get("person_name")));
        String postName = esc(s(p.get("post_name")));

        String bodyText = switch (title) {
            case "安全生产承诺书" ->
                    "本人作为公司安全生产责任体系中的一员，郑重承诺：严格遵守《中华人民共和国安全生产法》" +
                    "及公司安全生产规章制度，认真履行本岗位安全生产职责，接受安全教育培训，服从安全管理，" +
                    "自觉抵制违章指挥、违章作业，发现事故隐患及时报告，确保生产安全。";
            case "岗位危害告知书" ->
                    "依据《中华人民共和国安全生产法》及公司安全生产管理制度，现将本岗位存在的主要危险有害因素、" +
                    "可能导致的职业危害、应当采取的防范措施及应急处置要求如实告知本人。本人已了解岗位危害，" +
                    "承诺严格遵守安全操作规程，正确佩戴和使用劳动防护用品。";
            default ->
                    "本人承诺认真履行安全生产责任，遵守安全生产法律法规及公司规章制度。";
        };

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"/><style>")
                .append("@page{size:A4;margin:20mm 18mm;}")
                .append("body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',sans-serif;font-size:12pt;line-height:2;color:#1a1a1a;}")
                .append("h1{text-align:center;font-size:22pt;letter-spacing:4px;margin:30px 0 20px;}")
                .append(".meta{font-size:12pt;line-height:2.4;margin:10px 0 24px;}")
                .append(".body{font-size:12pt;line-height:2.2;text-indent:2em;margin:16px 0 30px;}")
                .append(".sign{margin-top:40px;font-size:12pt;line-height:2.6;}")
                .append(".sign-row{display:inline-block;min-width:240px;border-bottom:1px solid #333;}")
                .append(".seal-box{text-align:right;margin-top:-40px;margin-bottom:-20px;padding-right:30px;}")
                .append(".seal-img{width:120px;height:120px;}")
                .append("</style><title>").append(title).append("</title></head><body>");

        h.append("<h1>").append(title).append("</h1>");
        h.append("<div class=\"meta\">单位：").append(company)
                .append("<br/>姓名：").append(personName)
                .append("<br/>岗位：").append(postName.isEmpty() ? "—" : postName)
                .append("<br/>年度：").append(year).append(" 年</div>");
        h.append("<div class=\"body\">").append(bodyText).append("</div>");
        h.append("<div class=\"sign\">签署人：<span class=\"sign-row\">")
                .append(signName.isEmpty() ? "" : esc(signName)).append("</span>")
                .append("<br/>签署日期：").append(year).append(" 年 ")
                .append(LocalDate.now().getMonthValue()).append(" 月 ")
                .append(LocalDate.now().getDayOfMonth()).append(" 日</div>");

        if (signed && !signImage.isBlank()) {
            h.append("<div class=\"sign\" style=\"margin-top:14px;\">电子签名：").append(esc(signImage))
                    .append("</div>");
        }
        h.append("<div class=\"seal-box\">").append(sealService.sealHtml()).append("</div>");
        h.append("</body></html>");
        return pdfService.render(h.toString());
    }
}
