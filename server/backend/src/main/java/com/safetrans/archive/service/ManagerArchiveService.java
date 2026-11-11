package com.safetrans.archive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.mapper.ArchiveItemMapper;
import com.safetrans.archive.mapper.ArchivePdfMapper;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.service.SealService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盒一「管理人员档案」——按人分类的一人一档 PDF 集合（用户 2026-11-11 需求 ①）。
 *
 * <p>与业务定义（《07-人员基础信息与一人一档》§二 / 《08》盒一目录 S07）一致：
 * <ul>
 *   <li>人员来源：人员基础信息里 person_type=manager 的管理人员（不是单个范本）；</li>
 *   <li>每人一份档案册 PDF，册内按 13 项归档目录 + 考核记录分节，包含<b>岗前培训、
 *       考核记录、三书（责任书/承诺书/告知书）、任命书、证照</b>等；</li>
 *   <li>按人归档：archive_item.person_id 落到本人，标题带姓名，档案柜按人分类展示；</li>
 *   <li>状态与完整度实时读 person_archive_item / person_doc，重新生成即刷新成品。</li>
 * </ul>
 */
@Service
public class ManagerArchiveService {

    /** 用户点名的重点材料 → 分组名（档案册里单独成章，便于检查） */
    private static final Map<String, String> GROUP = new LinkedHashMap<>() {{
        put("P05", "三书");       put("P06", "三书");       put("P07", "三书");
        put("P08", "岗前培训");
        put("P10", "考核记录");   put("CREDIT_ASSESS", "考核记录");
        put("P12", "任命书");
        put("P01", "证照");       put("P02", "证照");       put("P03", "证照");
        put("P04", "证照");       put("P09", "证照");       put("P11", "证照");
        put("P13", "证照");
    }};

    private static final String ITEM_SQL =
            "SELECT d.`item_code`, d.`item_name`, d.`source_type`, d.`is_optional`, d.`sort_no`, " +
            "i.`status`, i.`file_url`, i.`source_type` AS `actual_source`, i.`expire_date` " +
            "FROM `person_archive_dict` d LEFT JOIN `person_archive_item` i " +
            "ON i.`item_code`=d.`item_code` AND i.`person_id`=? AND i.`tenant_id`=? AND i.`deleted`=0 " +
            "WHERE d.`deleted`=0 ORDER BY d.`sort_no`";

    private final JdbcTemplate jdbc;
    private final ArchiveItemMapper itemMapper;
    private final ArchivePdfMapper pdfMapper;
    private final ArchiveService archiveService;
    private final PdfService pdfService;
    private final TenantService tenantService;
    private final SealService sealService;

    public ManagerArchiveService(JdbcTemplate jdbc, ArchiveItemMapper itemMapper, ArchivePdfMapper pdfMapper,
                                 ArchiveService archiveService, PdfService pdfService,
                                 TenantService tenantService, SealService sealService) {
        this.jdbc = jdbc;
        this.itemMapper = itemMapper;
        this.pdfMapper = pdfMapper;
        this.archiveService = archiveService;
        this.pdfService = pdfService;
        this.tenantService = tenantService;
        this.sealService = sealService;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 管理人员名册：person_type=manager，附带岗位名（org_member → org_post） */
    private List<Map<String, Object>> managers() {
        return jdbc.queryForList(
                "SELECT p.`id`, p.`name`, p.`telephone`, p.`id_card`, p.`person_type`, " +
                "GROUP_CONCAT(DISTINCT o.`post_name` ORDER BY o.`id` SEPARATOR '、') AS `post_name` " +
                "FROM `person` p LEFT JOIN `org_member` m " +
                "  ON m.`user_id`=p.`id` AND m.`tenant_id`=p.`tenant_id` AND m.`deleted`=0 " +
                "LEFT JOIN `org_post` o ON o.`id`=m.`post_id` AND o.`deleted`=0 AND o.`enabled`=1 " +
                "WHERE p.`tenant_id`=? AND p.`deleted`=0 AND p.`person_type`='manager' " +
                "GROUP BY p.`id`, p.`name`, p.`telephone`, p.`id_card`, p.`person_type` ORDER BY p.`id`", tid());
    }

    /** 某人的一人一档 13 项 + 考核记录，带完整度与分组 */
    private Map<String, Object> itemsOf(long personId) {
        List<Map<String, Object>> items = new ArrayList<>();
        int required = 0, done = 0;
        for (Map<String, Object> r : jdbc.queryForList(ITEM_SQL, personId, tid())) {
            String code = s(r.get("item_code"));
            String dictSource = s(r.get("source_type"));
            String actual = s(r.get("actual_source"));
            String source = actual.isBlank() ? dictSource : actual;
            boolean optional = "1".equals(s(r.get("is_optional")));
            int status = "1".equals(s(r.get("status"))) ? 1 : "2".equals(s(r.get("status"))) ? 2 : 0;
            String exp = s(r.get("expire_date"));
            if (exp.length() > 10) exp = exp.substring(0, 10);
            if (!optional) {
                required++;
                if (status == 1) done++;
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("code", code);
            it.put("name", s(r.get("item_name")));
            it.put("group", GROUP.getOrDefault(code, "其他"));
            it.put("source", source);
            it.put("status", status);
            it.put("optional", optional);
            it.put("fileUrl", s(r.get("file_url")));
            it.put("expireDate", exp);
            items.add(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("required", required);
        out.put("done", done);
        out.put("percent", required == 0 ? 0 : (int) Math.round(done * 100.0 / required));
        return out;
    }

    /** 某人的证照明细（person_doc 子表） */
    private List<Map<String, Object>> docsOf(long personId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT `doc_code`,`doc_name`,`issue_date`,`expire_date`,`status` FROM `person_doc` " +
                "WHERE `person_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `doc_code`", personId, tid())) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", s(row.get("doc_name")));
            d.put("issue", s(row.get("issue_date")));
            d.put("expire", s(row.get("expire_date")));
            out.add(d);
        }
        return out;
    }

    private String titleOf(String name) { return "管理人员档案-" + name; }

    /** 该人当前年度档案册的归档记录（按人分类的索引） */
    private Map<String, Object> pdfStateOf(long personId, String name, int year) {
        ArchiveItem it = itemMapper.selectOne(new LambdaQueryWrapper<ArchiveItem>()
                .eq(ArchiveItem::getTenantId, tid())
                .eq(ArchiveItem::getBizModule, "manager_archive")
                .eq(ArchiveItem::getPersonId, personId)
                .eq(ArchiveItem::getTitle, titleOf(name))
                .eq(ArchiveItem::getArchiveYear, year)
                .last("limit 1"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", it != null && it.getPdfId() != null);
        m.put("pdfId", it == null ? null : it.getPdfId());
        if (it != null && it.getPdfId() != null) {
            ArchivePdf pdf = pdfMapper.selectById(it.getPdfId());
            m.put("genTime", pdf == null ? null : pdf.getGenTime());
            m.put("size", pdf == null ? null : pdf.getFileSize());
        }
        return m;
    }

    /**
     * 档案柜「管理人员档案」总览：按人分类的 PDF 集合。
     * 每人带一人一档明细、重点材料分组统计、档案册 PDF 状态。
     */
    public Map<String, Object> overview(int year) {
        List<Map<String, Object>> persons = new ArrayList<>();
        int generated = 0;
        for (Map<String, Object> p : managers()) {
            long pid = ((Number) p.get("id")).longValue();
            String name = s(p.get("name"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("personId", pid);
            row.put("name", name);
            row.put("postName", s(p.get("post_name")));
            row.put("telephone", s(p.get("telephone")));
            row.put("idCard", s(p.get("id_card")));
            Map<String, Object> c = itemsOf(pid);
            row.putAll(c);
            row.put("docs", docsOf(pid));
            // 重点材料分组计数（岗前培训 / 考核记录 / 三书 / 任命书）
            Map<String, int[]> focus = new LinkedHashMap<>();
            focus.put("三书", new int[]{0, 0});
            focus.put("岗前培训", new int[]{0, 0});
            focus.put("考核记录", new int[]{0, 0});
            focus.put("任命书", new int[]{0, 0});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) c.get("items");
            for (Map<String, Object> it : items) {
                int[] f = focus.get(s(it.get("group")));
                if (f != null) { f[1]++; if (((Number) it.get("status")).intValue() == 1) f[0]++; }
            }
            Map<String, Object> focusOut = new LinkedHashMap<>();
            focus.forEach((k, v) -> focusOut.put(k, Map.of("done", v[0], "total", v[1])));
            row.put("focus", focusOut);
            Map<String, Object> st = pdfStateOf(pid, name, year);
            row.putAll(st);
            if (Boolean.TRUE.equals(st.get("generated"))) generated++;
            persons.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("company", tenantService.currentTenantName());
        out.put("persons", persons);
        out.put("total", persons.size());
        out.put("generated", generated);
        return out;
    }

    /** 为一人（或全部管理人员）生成档案册 PDF 并按人归档 */
    @Transactional
    public List<Map<String, Object>> generate(int year, Long personId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : managers()) {
            long pid = ((Number) p.get("id")).longValue();
            if (personId != null && personId != 0 && pid != personId) continue;
            String name = s(p.get("name"));
            byte[] bytes = pdfService.render(buildHtml(p, itemsOf(pid), docsOf(pid), year));
            ArchivePdf pdf = archiveService.generateByBytes(
                    titleOf(name), 1, year, bytes, "MANAGER_ARCHIVE", pid, "manager_archive");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("personId", pid);
            row.put("name", name);
            row.put("pdfId", pdf.getId());
            row.put("size", pdf.getFileSize());
            out.add(row);
        }
        if (out.isEmpty() && personId != null) throw new BizException("该人员不是管理人员或未录入人员基础信息");
        return out;
    }

    /** 档案册 HTML：封面 + 基本信息 + 归档目录 + 重点材料分章 + 证照明细 + 落款盖章 */
    private String buildHtml(Map<String, Object> p, Map<String, Object> c,
                             List<Map<String, Object>> docs, int year) {
        String name = esc(s(p.get("name")));
        String post = esc(s(p.get("post_name")));
        String tel = esc(s(p.get("telephone")));
        String idCard = esc(s(p.get("id_card")));
        String company = esc(tenantService.currentTenantName());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) c.get("items");

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"/><style>")
                .append("@page{size:A4;margin:18mm 16mm;}")
                .append("body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',sans-serif;font-size:11pt;line-height:1.7;color:#1a1a1a;}")
                .append("h1{text-align:center;font-size:22pt;letter-spacing:4px;margin:26px 0 8px;}")
                .append("h2{font-size:14pt;border-bottom:1px solid #999;padding-bottom:4px;margin:20px 0 10px;}")
                .append("h3{font-size:12pt;margin:12px 0 6px;color:#333;}")
                .append("table{width:100%;border-collapse:collapse;margin:8px 0 14px;}")
                .append("th,td{border:1px solid #888;padding:6px 8px;font-size:10pt;text-align:left;}")
                .append("th{background:#f0f2f5;font-weight:bold;}")
                .append(".cover{text-align:center;padding-top:80px;page-break-after:always;}")
                .append(".cover .co{font-size:15pt;margin-bottom:30px;}")
                .append(".cover .meta{margin-top:60px;font-size:12pt;line-height:2.2;}")
                .append(".sec{page-break-inside:avoid;}")
                .append(".ok{color:#15803d;font-weight:bold;}.miss{color:#b91c1c;font-weight:bold;}")
                .append(".tip{font-size:9.5pt;color:#555;}")
                .append(".sign{margin-top:26px;text-align:right;font-size:11pt;line-height:2;}")
                .append(".seal-box{text-align:right;margin-top:-56px;margin-bottom:-20px;padding-right:26px;}")
                .append(".seal-img{width:130px;height:130px;}")
                .append("</style><title>管理人员档案</title></head><body>");

        // 封面
        h.append("<div class=\"cover\"><div class=\"co\">").append(company).append("</div>")
                .append("<h1>管理人员档案</h1>")
                .append("<div style=\"font-size:14pt;letter-spacing:2px\">一人一档 · ").append(year).append(" 年度</div>")
                .append("<div class=\"meta\">姓名：").append(name)
                .append("<br/>岗位：").append(post.isEmpty() ? "—" : post)
                .append("<br/>归档年度：").append(year)
                .append("<br/>编制日期：").append(LocalDate.now())
                .append("</div></div>");

        // 基本信息 + 完整度
        h.append("<h2>一、人员基本信息</h2><table>")
                .append("<tr><th style=\"width:22%\">姓名</th><td>").append(name)
                .append("</td><th style=\"width:22%\">岗位</th><td>").append(post.isEmpty() ? "—" : post).append("</td></tr>")
                .append("<tr><th>联系电话</th><td>").append(tel)
                .append("</td><th>身份证号</th><td>").append(idCard).append("</td></tr>")
                .append("<tr><th>人员类别</th><td>管理人员</td><th>档案完整度</th><td>")
                .append(c.get("done")).append(" / ").append(c.get("required"))
                .append(" 项（").append(c.get("percent")).append("%）</td></tr></table>");

        // 归档目录（13 项 + 考核记录）
        h.append("<h2>二、一人一档归档目录</h2><table><tr><th style=\"width:8%\">序号</th><th style=\"width:16%\">分类</th>")
                .append("<th>归档项</th><th style=\"width:16%\">生产方式</th><th style=\"width:14%\">状态</th></tr>");
        int idx = 1;
        for (Map<String, Object> it : items) {
            int st = ((Number) it.get("status")).intValue();
            String src = sourceLabel(s(it.get("source")));
            h.append("<tr><td>").append(idx++).append("</td><td>").append(esc(s(it.get("group"))))
                    .append("</td><td>").append(esc(s(it.get("name"))))
                    .append("</td><td>").append(src).append("</td><td class=\"")
                    .append(st == 1 ? "ok" : "miss").append("\">")
                    .append(st == 1 ? "已归档" : st == 2 ? "已过期" : "缺失").append("</td></tr>");
        }
        h.append("</table>");

        // 重点材料分章（用户点名：三书 / 岗前培训 / 考核记录 / 任命书）
        String[] chapters = {"三书", "岗前培训", "考核记录", "任命书"};
        String[] titles = {"三、安全生产三书（责任书 / 承诺书 / 岗位危害告知书）",
                "四、岗前三级安全教育培训", "五、年度安全生产责任制考核记录", "六、人员任命书"};
        String[] notes = {
                "三书按自然年度一年一签；责任书须由安全管理架构中的上级与本人双方签署后方可生效。",
                "岗前三级安全教育（公司级 / 部门级 / 班组级）培训与考核记录，入职一次归档。",
                "年度责任制考核、诚信考核与奖惩记录，由考核模块汇总后沉淀到一人一档。",
                "管理人员任命书由组织架构联动生成，岗位调整后自动重生成。"};
        for (int i = 0; i < chapters.length; i++) {
            h.append("<div class=\"sec\"><h2>").append(titles[i]).append("</h2>")
                    .append("<div class=\"tip\">").append(notes[i]).append("</div><table>")
                    .append("<tr><th>归档项</th><th style=\"width:16%\">生产方式</th><th style=\"width:14%\">状态</th>")
                    .append("<th style=\"width:20%\">有效期至</th></tr>");
            boolean any = false;
            for (Map<String, Object> it : items) {
                if (!chapters[i].equals(s(it.get("group")))) continue;
                any = true;
                int st = ((Number) it.get("status")).intValue();
                h.append("<tr><td>").append(esc(s(it.get("name")))).append("</td><td>")
                        .append(sourceLabel(s(it.get("source")))).append("</td><td class=\"")
                        .append(st == 1 ? "ok" : "miss").append("\">")
                        .append(st == 1 ? "已归档" : st == 2 ? "已过期" : "缺失")
                        .append("</td><td>").append(esc(s(it.get("expireDate")))).append("</td></tr>");
            }
            if (!any) h.append("<tr><td colspan=\"4\">（暂无该分类归档项）</td></tr>");
            h.append("</table></div>");
        }

        // 证照明细
        h.append("<div class=\"sec\"><h2>七、证照与资格证明明细</h2><table>")
                .append("<tr><th>证件名称</th><th style=\"width:22%\">发证日期</th><th style=\"width:22%\">有效期至</th></tr>");
        if (docs.isEmpty()) {
            h.append("<tr><td colspan=\"3\">（暂无证照记录）</td></tr>");
        } else {
            for (Map<String, Object> d : docs) {
                h.append("<tr><td>").append(esc(s(d.get("name")))).append("</td><td>")
                        .append(esc(cut(s(d.get("issue"))))).append("</td><td>")
                        .append(esc(cut(s(d.get("expire"))))).append("</td></tr>");
            }
        }
        h.append("</table></div>");

        h.append("<div class=\"sign\">").append(company).append("<br/>")
                .append(year).append(" 年 ").append(LocalDate.now().getMonthValue())
                .append(" 月 ").append(LocalDate.now().getDayOfMonth()).append(" 日</div>")
                .append(sealService.sealHtml())
                .append("</body></html>");
        return h.toString();
    }

    private static String cut(String v) { return v.length() > 10 ? v.substring(0, 10) : v; }

    private static String sourceLabel(String src) {
        return switch (src) {
            case "upload" -> "证件上传";
            case "credit" -> "考核汇总";
            case "print" -> "打印手写";
            default -> "系统生成";
        };
    }
}
