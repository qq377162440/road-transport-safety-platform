package com.safetrans.phase3;

import cn.hutool.core.util.IdUtil;
import com.safetrans.biz.ArchiveRegistrar;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * D 阶段（三期 v1.2）专用业务闭环公共支撑。
 *
 * <p>通用 CRUD 引擎负责「白名单录入/查询」，本包下的控制器负责有状态流转、
 * 汇总计算、模板生成与归档；所有产生成果物的操作统一走 ArchiveRegistrar（铁律 6）。
 *
 * <p>表名/列名在本包中均为代码常量，不接收客户端传入的表名，杜绝 SQL 注入。
 */
public abstract class PhaseDSupport {

    protected final JdbcTemplate jdbc;
    protected final ArchiveRegistrar registrar;
    protected final PdfService pdfService;
    protected final StorageService storage;

    protected PhaseDSupport(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                            PdfService pdfService, StorageService storage) {
        this.jdbc = jdbc;
        this.registrar = registrar;
        this.pdfService = pdfService;
        this.storage = storage;
    }

    protected long tid() {
        Long t = TenantContextHolder.tenantId();
        return t == null ? 0L : t;
    }

    protected long uid() {
        Long u = TenantContextHolder.userId();
        return u == null ? 0L : u;
    }

    /** 平台级账号（服务商/超管）校验：企业账号不得跨租户操作。 */
    protected void requirePlatform() {
        if (!TenantContextHolder.isPlatform()) throw ErrorCode.FORBIDDEN.ex();
    }

    protected Map<String, Object> base() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant_id", tid());
        r.put("created_at", LocalDateTime.now());
        r.put("created_by", uid());
        r.put("deleted", 0);
        return r;
    }

    /** 按 Map 键动态插入，返回雪花 ID。表名/列名来自代码常量。 */
    protected long insert(String table, Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        long id = IdUtil.getSnowflakeNextId();
        r.put("id", id);
        String cols = String.join(",", r.keySet().stream().map(c -> "`" + c.replace("`", "") + "`").toList());
        String marks = String.join(",", Collections.nCopies(r.size(), "?"));
        jdbc.update("INSERT INTO `" + table.replace("`", "") + "` (" + cols + ") VALUES (" + marks + ")",
                r.values().toArray());
        return id;
    }

    /** 存在性校验，失败抛 3001 */
    protected void requireExists(String table, long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + table.replace("`", "") + "` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                Integer.class, id, tid());
        if (n == null || n == 0) throw ErrorCode.BIZ_NOT_FOUND.ex(table + "#" + id);
    }

    // ------------------------------------------------------------------
    // 入参工具
    // ------------------------------------------------------------------
    protected static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? null : String.valueOf(v);
    }

    protected static String str(Map<String, Object> b, String k, String def) {
        String v = str(b, k);
        return v == null || v.isBlank() ? def : v;
    }

    protected static String requiredStr(Map<String, Object> b, String k) {
        String v = str(b, k);
        if (v == null || v.isBlank()) throw ErrorCode.FIELD_REQUIRED.ex(k);
        return v;
    }

    protected static long lng(Map<String, Object> b, String k, long def) {
        Object v = b.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw ErrorCode.PARAM_INVALID.ex(k + " 必须是数字");
        }
    }

    protected static BigDecimal dec(Map<String, Object> b, String k, BigDecimal def) {
        Object v = b.get(k);
        if (v == null || String.valueOf(v).isBlank()) return def;
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw ErrorCode.PARAM_INVALID.ex(k + " 必须是金额");
        }
    }

    protected static int integer(Map<String, Object> b, String k, int def) {
        return (int) lng(b, k, def);
    }

    @SuppressWarnings("unchecked")
    protected static List<Object> list(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    // ------------------------------------------------------------------
    // HTML / PDF 归档
    // ------------------------------------------------------------------
    protected static String esc(Object s) {
        if (s == null) return "";
        return String.valueOf(s).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 完整 XHTML 文档（openhtmltopdf 要求标签闭合良好，中文字体走 classpath TTF）。 */
    protected static String html(String title, String body) {
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\" />"
                + "<style>body{font-family:'WenQuanYi Zen Hei',sans-serif;font-size:11pt;line-height:1.6;}"
                + "h1{font-size:17pt;text-align:center;margin:6px 0 16px}"
                + "h2{font-size:13pt;margin:14px 0 6px}"
                + "table{width:100%;border-collapse:collapse;margin:8px 0 12px}"
                + "th,td{border:1px solid #444;padding:5px 7px;font-size:10pt;text-align:left}"
                + "th{background:#eeeeee}p{margin:4px 0}</style>"
                + "<title>" + esc(title) + "</title></head><body>"
                + "<h1>" + esc(title) + "</h1>" + body + "</body></html>";
    }

    protected static String table(String[] heads, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("<table><tr>");
        for (String h : heads) sb.append("<th>").append(esc(h)).append("</th>");
        sb.append("</tr>");
        for (List<Object> r : rows) {
            sb.append("<tr>");
            for (Object c : r) sb.append("<td>").append(esc(c)).append("</td>");
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    /**
     * 渲染 PDF → 上传 MinIO → 登记 biz_file（供下载/打印）→ 幂等登记归档项。
     * 下载地址走 /api/file/{id}/download，前端以 axios blob 携带 token 获取后打开或另存。
     */
    protected Map<String, Object> archivePdf(String module, String catalog, long bizId,
                                             String title, String bodyHtml) {
        byte[] pdf = pdfService.render(html(title, bodyHtml));
        String key = tid() + "/" + module + "/" + bizId + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        long fileId = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `biz_file` " +
                        "(`id`,`tenant_id`,`biz_module`,`biz_id`,`file_name`,`object_key`,`file_size`," +
                        "`content_type`,`uploader`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                fileId, tid(), module, bizId, title + ".pdf", key, pdf.length,
                "application/pdf", uid(), LocalDateTime.now(), uid());
        registrar.register(module, catalog, bizId, title);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objectKey", key);
        out.put("fileId", fileId);
        out.put("downloadUrl", "/api/file/" + fileId + "/download");
        out.put("size", pdf.length);
        out.put("title", title);
        out.put("module", module);
        out.put("catalog", catalog);
        out.put("archived", true);
        return out;
    }

    protected static String today() {
        return LocalDate.now().toString();
    }
}
