package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.common.web.Result;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 法律法规清单模块：展示平台安全生产适用的法律法规标准清单，并提供法规原文库。
 *
 * <p>数据来源：服务商提供的《适用安全生产法律法规标准清单（整理版）》88 条为初始模板（tenant_id=0 平台内置）。
 * 法规原文库：law_document 表登记原文文件（文件本体存 MinIO），提取正文供全文查询，
 * 可标记 law_id + ref_note 实现「制度—法规—原文」三级可追溯。
 * 权限模型：
 * <ul>
 *   <li>服务商(provider) / 超管(admin)：对清单与原文增删查改（上传、标记引用、删除）；</li>
 *   <li>企业(enterprise)：只读查询、在线预览、下载原件与清单 PDF。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/flow/law")
public class LawFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;

    public LawFlowController(JdbcTemplate jdbc, PdfService pdfService, StorageService storage) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
    }

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static final String SELECT_SQL =
            "SELECT `id`,`law_name`,`law_no`,`law_level`,`apply_level`,`region`,`category`,`version_label`," +
            "`effective_date`,`revise_date`,`status`,`apply_scope`,`biz_type`,`is_applicable`,`summary`,`remark`,`refs`,`sort_no` " +
            "FROM `law_catalog` WHERE `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?) ";

    /** 法规清单总览：分类统计 + 状态统计 + 列表（支持关键字/分类/适用层级/适用业务筛选） */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String applyLevel,
                                                @RequestParam(required = false) String bizType) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (`law_name` LIKE ? OR `law_no` LIKE ? OR `summary` LIKE ? OR `remark` LIKE ? OR `refs` LIKE ?) ");
            String k = "%" + keyword.trim() + "%";
            args.add(k); args.add(k); args.add(k); args.add(k); args.add(k);
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            sql.append(" AND `status`=? ");
            args.add(status);
        }
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            sql.append(" AND `category`=? ");
            args.add(category);
        }
        if (applyLevel != null && !applyLevel.isBlank() && !"all".equals(applyLevel)) {
            sql.append(" AND `apply_level`=? ");
            args.add(applyLevel);
        }
        if (bizType != null && !bizType.isBlank() && !"all".equals(bizType)) {
            sql.append(" AND `biz_type`=? ");
            args.add(bizType);
        }
        sql.append(" ORDER BY `sort_no`,`id` ");
        List<Map<String, Object>> raw = jdbc.queryForList(sql.toString(), args.toArray());

        // 原文份数：按关联法规分组，law_id=0 为「未关联原文」
        Map<Long, Integer> docCnt = new LinkedHashMap<>();
        int docTotal = 0;
        for (Map<String, Object> d : jdbc.queryForList(
                "SELECT `law_id`, COUNT(*) c FROM `law_document` WHERE `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?) GROUP BY `law_id`",
                tid())) {
            int c = ((Number) d.get("c")).intValue();
            docCnt.put(((Number) d.get("law_id")).longValue(), c);
            docTotal += c;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        int active = 0, superseded = 0, repealed = 0, refCount = 0;
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            List<Map<String, String>> refs = parseRefs(s(r.get("refs")));
            row.put("refs", refs);
            row.put("refCount", refs.size());
            row.put("docCount", docCnt.getOrDefault(((Number) r.get("id")).longValue(), 0));
            refCount += refs.size();
            list.add(row);
            byCategory.merge(s(r.get("category")), 1, Integer::sum);
            switch (s(r.get("status"))) {
                case "superseded" -> superseded++;
                case "repealed" -> repealed++;
                default -> active++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("active", active);
        out.put("superseded", superseded);
        out.put("repealed", repealed);
        out.put("refCount", refCount);
        out.put("docTotal", docTotal);
        out.put("categories", byCategory.keySet());
        return Result.ok(out);
    }

    /** 单部法规详情（含引用制度 + 已关联原文） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_SQL + " AND `id`=? ", tid(), id);
        if (rows.isEmpty()) throw new BizException("法规不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("refs", parseRefs(s(rows.get(0).get("refs"))));
        row.put("docs", jdbc.queryForList(
                "SELECT `id`,`file_name`,`ext`,`file_size`,`text_len`,`ref_note`,`doc_tag` FROM `law_document` " +
                "WHERE `deleted`=0 AND `law_id`=? AND (`tenant_id`=0 OR `tenant_id`=?) ORDER BY `sort_no`,`id`", id, tid()));
        return Result.ok(row);
    }

    /** 新增 / 编辑法规（仅服务商/超管；企业补充条目 tenant_id=当前租户；内置条目编辑时另存为企业版本） */
    @PostMapping("/save")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> b) {
        long id = 0;
        try { id = Long.parseLong(String.valueOf(b.getOrDefault("id", "0"))); } catch (Exception ignore) { }
        String name = s(b.get("lawName"));
        if (name.isBlank()) throw new BizException("法规名称必填");
        List<String> refsName = new ArrayList<>();
        Object refsObj = b.get("refs");
        if (refsObj instanceof List<?> ls) {
            for (Object o : ls) refsName.add(s(o));
        } else if (refsObj != null && !s(refsObj).isBlank()) {
            for (String x : s(refsObj).split("[,，、;；\\n]")) if (!x.isBlank()) refsName.add(x.trim());
        }
        StringBuilder refsJson = new StringBuilder("[");
        for (int i = 0; i < refsName.size(); i++) {
            if (i > 0) refsJson.append(",");
            refsJson.append("{\"type\":\"doc\",\"name\":\"")
                    .append(refsName.get(i).replace("\"", "\\\"")).append("\"}");
        }
        refsJson.append("]");
        String st = s(b.getOrDefault("status", "active"));
        if (!List.of("active", "superseded", "repealed").contains(st)) st = "active";
        String scope = "danger".equals(b.get("bizType")) ? "danger" : "all";
        String biz = s(b.getOrDefault("bizType", "all"));
        if (!List.of("all", "cargo", "danger").contains(biz)) biz = "all";
        String app = "conditional".equals(b.get("isApplicable")) ? "conditional" : "yes";
        String level = s(b.getOrDefault("lawLevel", "部门规章"));
        String applyLevel = s(b.getOrDefault("applyLevel", "国家"));
        if (id > 0) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `law_catalog` WHERE `id`=? AND `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?)",
                    Integer.class, id, tid());
            if (n == null || n == 0) throw new BizException("法规不存在");
            jdbc.update("UPDATE `law_catalog` SET `law_name`=?,`law_no`=?,`law_level`=?,`apply_level`=?,`region`=?,`category`=?," +
                            "`version_label`=?,`effective_date`=?,`revise_date`=?,`status`=?,`apply_scope`=?,`biz_type`=?,`is_applicable`=?," +
                            "`summary`=?,`remark`=?,`refs`=?,`updated_at`=NOW() WHERE `id`=?",
                    name, s(b.get("lawNo")), level, applyLevel, s(b.get("region")), s(b.getOrDefault("category", "综合安全")),
                    s(b.get("versionLabel")), s(b.get("effectiveDate")), s(b.get("reviseDate")), st, scope, biz, app,
                    s(b.get("summary")), s(b.get("remark")), refsJson.toString(), id);
        } else {
            id = IdUtil.getSnowflakeNextId();
            jdbc.update("INSERT INTO `law_catalog` (`id`,`tenant_id`,`law_name`,`law_no`,`law_level`,`apply_level`,`region`,`category`," +
                            "`version_label`,`effective_date`,`revise_date`,`status`,`apply_scope`,`biz_type`,`is_applicable`,`summary`,`remark`,`refs`,`sort_no`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?,0)",
                    id, tid(), name, s(b.get("lawNo")), level, applyLevel, s(b.get("region")),
                    s(b.getOrDefault("category", "综合安全")), s(b.get("versionLabel")), s(b.get("effectiveDate")),
                    s(b.get("reviseDate")), st, scope, biz, app, s(b.get("summary")), s(b.get("remark")),
                    refsJson.toString(), 0, uid());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return Result.ok(out);
    }

    /** 删除（仅服务商/超管，且仅企业自有条目；内置条目 tenant_id=0 不允许删除） */
    @DeleteMapping("/{id}")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> remove(@PathVariable long id) {
        int n = jdbc.update("UPDATE `law_catalog` SET `deleted`=1 WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (n == 0) throw new BizException("内置法规不允许删除，可将其状态改为「已废止」");
        return Result.ok(Map.of("ok", true));
    }

    /** 下载法规清单 PDF（企业只读也能下载；服务商/超管同样可用） */
    @GetMapping("/export/pdf")
    @RequireRole({"enterprise", "provider", "admin"})
    public ResponseEntity<byte[]> exportPdf() {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        sql.append(" ORDER BY `sort_no`,`id` ");
        List<Map<String, Object>> raw = jdbc.queryForList(sql.toString(), tid());
        byte[] bytes = pdfService.render(buildHtml(raw));
        String name = "适用安全生产法律法规标准清单.pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    // ==================================================================================
    //  法规原文库：列表 / 在线预览与下载 / 正文 / 上传 / 标记引用 / 删除
    // ==================================================================================

    /** 原文列表（可关键字检索文件名·引用标记·关联法规·正文；企业只读可用） */
    @GetMapping("/doc/list")
    public Result<Map<String, Object>> docList(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long lawId,
                                               @RequestParam(required = false, defaultValue = "false") boolean onlyUnlinked) {
        StringBuilder sql = new StringBuilder(
                "SELECT `id`,`file_name`,`object_name`,`ext`,`file_size`,`content_type`,`text_len`,`law_id`,`law_name`," +
                "`ref_note`,`doc_tag`,`sort_no`,`created_at` FROM `law_document` " +
                "WHERE `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?) ");
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (lawId != null && lawId > 0) { sql.append(" AND `law_id`=? "); args.add(lawId); }
        if (onlyUnlinked) sql.append(" AND `law_id`=0 ");
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (`file_name` LIKE ? OR `ref_note` LIKE ? OR `law_name` LIKE ? OR `content_text` LIKE ?) ");
            String k = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) args.add(k);
        }
        sql.append(" ORDER BY `sort_no`,`id` ");
        List<Map<String, Object>> list = jdbc.queryForList(sql.toString(), args.toArray());
        int linked = 0, withText = 0;
        long totalSize = 0;
        for (Map<String, Object> r : list) {
            long size = ((Number) r.get("file_size")).longValue();
            r.put("sizeText", humanSize(size));
            totalSize += size;
            if (((Number) r.getOrDefault("law_id", 0)).longValue() > 0) linked++;
            if (((Number) r.getOrDefault("text_len", 0)).intValue() > 0) withText++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("linked", linked);
        out.put("unlinked", list.size() - linked);
        out.put("withText", withText);
        out.put("totalSizeText", humanSize(totalSize));
        return Result.ok(out);
    }

    /** 在线预览 / 下载原文文件（PDF 默认 inline，其余默认 attachment） */
    @GetMapping("/doc/{id}/file")
    public ResponseEntity<byte[]> docFile(@PathVariable long id,
                                          @RequestParam(required = false) Boolean inline) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `file_name`,`object_name`,`ext`,`content_type` FROM `law_document` " +
                "WHERE `id`=? AND `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?)", id, tid());
        if (rows.isEmpty()) throw new BizException("原文不存在");
        Map<String, Object> r = rows.get(0);
        String name = s(r.get("file_name"));
        String ext = s(r.get("ext")).toLowerCase();
        byte[] data;
        try {
            data = storage.download(s(r.get("object_name")));
        } catch (Exception e) {
            throw new BizException("原文文件读取失败：" + e.getMessage());
        }
        boolean inl = inline != null ? inline : "pdf".equals(ext);
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inl ? "inline" : "attachment") + "; filename=\"" + enc + "\"; filename*=UTF-8''" + enc)
                .contentType(MediaType.parseMediaType(contentTypeOf(ext, s(r.get("content_type")))))
                .body(data);
    }

    /** 读取提取的正文（供在线阅读 / 全文查询；扫描件返回空） */
    @GetMapping("/doc/{id}/text")
    public Result<Map<String, Object>> docText(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `file_name`,`content_text`,`text_len` FROM `law_document` " +
                "WHERE `id`=? AND `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?)", id, tid());
        if (rows.isEmpty()) throw new BizException("原文不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", s(rows.get(0).get("file_name")));
        out.put("textLen", ((Number) rows.get(0).getOrDefault("text_len", 0)).intValue());
        out.put("contentText", s(rows.get(0).get("content_text")));
        return Result.ok(out);
    }

    /** 上传法规原文（仅服务商/超管）：存 MinIO + 提取正文 + 登记元数据，可同时关联法规与引用标记 */
    @PostMapping("/doc/upload")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> docUpload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false, defaultValue = "0") long lawId,
                                                 @RequestParam(required = false) String refNote,
                                                 @RequestParam(required = false) String docTag,
                                                 @RequestParam(required = false, defaultValue = "0") int sortNo) {
        if (file.isEmpty()) throw new BizException("文件不能为空");
        String origin = file.getOriginalFilename();
        if (origin == null || origin.isBlank()) throw new BizException("文件名无效");
        String name = origin.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) ext = name.substring(dot + 1).toLowerCase();
        byte[] data;
        try { data = file.getBytes(); } catch (Exception e) { throw new BizException("文件读取失败"); }

        String text = extractText(data, ext);
        if (text.length() > 300000) text = text.substring(0, 300000);

        long id = IdUtil.getSnowflakeNextId();
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String objectName = "law_docs/" + id + "/" + safe;
        String ct = contentTypeOf(ext, file.getContentType());
        storage.upload(objectName, data, ct);

        String lawName = "";
        if (lawId > 0) {
            List<Map<String, Object>> lr = jdbc.queryForList(
                    "SELECT `law_name` FROM `law_catalog` WHERE `id`=? AND `deleted`=0", lawId);
            if (!lr.isEmpty()) lawName = s(lr.get(0).get("law_name")); else lawId = 0;
        }
        jdbc.update("INSERT INTO `law_document` (`id`,`tenant_id`,`file_name`,`object_name`,`ext`,`file_size`,`content_type`," +
                        "`content_text`,`text_len`,`law_id`,`law_name`,`ref_note`,`doc_tag`,`sort_no`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,0,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?,0)",
                id, name, objectName, ext, data.length, ct, text, text.length(),
                lawId, lawName, s(refNote), s(docTag), sortNo, uid());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("fileName", name);
        out.put("textLen", text.length());
        out.put("lawName", lawName);
        return Result.ok(out);
    }

    /** 标记引用（仅服务商/超管）：把原文关联到法规条目，并填写引用该原文的制度/条款/章节 */
    @PostMapping("/doc/mark")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> docMark(@RequestBody Map<String, Object> b) {
        long id = 0, lawId = 0;
        try { id = Long.parseLong(s(b.get("id"))); } catch (Exception ignore) { }
        try { lawId = Long.parseLong(s(b.getOrDefault("lawId", "0"))); } catch (Exception ignore) { }
        if (id <= 0) throw new BizException("参数错误");
        String lawName = "";
        if (lawId > 0) {
            List<Map<String, Object>> lr = jdbc.queryForList(
                    "SELECT `law_name` FROM `law_catalog` WHERE `id`=? AND `deleted`=0", lawId);
            if (lr.isEmpty()) throw new BizException("关联的法规不存在");
            lawName = s(lr.get(0).get("law_name"));
        }
        int n = jdbc.update("UPDATE `law_document` SET `law_id`=?,`law_name`=?,`ref_note`=? WHERE `id`=? AND `deleted`=0",
                lawId, lawName, s(b.get("refNote")), id);
        if (n == 0) throw new BizException("原文不存在");
        return Result.ok(Map.of("ok", true, "lawName", lawName));
    }

    /** 删除原文（仅服务商/超管，软删除；MinIO 对象保留以便追溯） */
    @DeleteMapping("/doc/{id}")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> docRemove(@PathVariable long id) {
        int n = jdbc.update("UPDATE `law_document` SET `deleted`=1 WHERE `id`=? AND `deleted`=0", id);
        if (n == 0) throw new BizException("原文不存在");
        return Result.ok(Map.of("ok", true));
    }

    // ----------------------------------------------------------------------------------
    //  工具方法
    // ----------------------------------------------------------------------------------

    /** 提取正文：PDF 用 PDFBox，DOCX 读 word/document.xml，TXT 直接解码；扫描件返回空串 */
    private String extractText(byte[] data, String ext) {
        try {
            if ("pdf".equals(ext)) {
                try (PDDocument doc = PDDocument.load(data)) {
                    return clean(new PDFTextStripper().getText(doc));
                }
            }
            if ("docx".equals(ext)) {
                try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if ("word/document.xml".equals(e.getName())) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                            String xml = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                            String t = xml.replace("</w:p>", "\n").replace("</w:tr>", "\n")
                                    .replaceAll("<[^>]+>", "")
                                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                    .replace("&quot;", "\"").replace("&apos;", "'");
                            return clean(t);
                        }
                    }
                }
            }
            if ("txt".equals(ext)) {
                return clean(new String(data, StandardCharsets.UTF_8));
            }
        } catch (Exception ignore) { /* 扫描件 / 损坏文件：正文留空，不影响原件预览下载 */ }
        return "";
    }

    private static String clean(String t) {
        if (t == null) return "";
        t = t.replace('\u0000', ' ').replace('\uFEFF', ' ');
        t = t.replaceAll("[ \\t\\x0B\\f]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    private static String contentTypeOf(String ext, String fallback) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "txt" -> "text/plain; charset=UTF-8";
            case "rar" -> "application/x-rar-compressed";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> (fallback == null || fallback.isBlank()) ? "application/octet-stream" : fallback;
        };
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    /** 渲染法规清单 PDF 的 HTML（按分类分组，含名称/文号/层级/地区/业务/施行日期/备注） */
    private String buildHtml(List<Map<String, Object>> rows) {
        // 按分类分组，保持稳定顺序
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String cat = s(r.get("category"));
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:40px;color:#222}")
                .append("h1{text-align:center;font-size:22px;letter-spacing:2px;margin:0 0 4px}")
                .append(".sub{text-align:center;font-size:12px;color:#666;margin-bottom:20px}")
                .append("h2{font-size:15px;border-left:4px solid #1a5bb0;padding-left:10px;margin:22px 0 10px}")
                .append("table{width:100%;border-collapse:collapse;font-size:11px}")
                .append("th,td{border:1px solid #888;padding:6px 8px;text-align:left;vertical-align:top}")
                .append("th{background:#eef3fb;font-weight:600}")
                .append(".no{width:34px;text-align:center}.name{font-weight:600}")
                .append(".lv{width:46px;text-align:center}.biz{width:46px;text-align:center}.date{width:82px}")
                .append(".ft{font-size:10px;color:#555}")
                .append("</style></head><body>");
        h.append("<h1>适用安全生产法律法规标准清单</h1>")
                .append("<div class=\"sub\">共 ").append(rows.size()).append(" 部 · 依据服务商整理版初始模板 · 生成时间 ")
                .append(LocalDateTime.now().toString().replace("T", " ").substring(0, 16)).append("</div>");
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            h.append("<h2>").append(esc(e.getKey())).append("（").append(e.getValue().size()).append("）</h2>");
            h.append("<table><thead><tr><th class=\"no\">序号</th><th>法规/标准名称</th><th>文号/版本</th>")
                    .append("<th class=\"lv\">层级</th><th>适用地区</th><th class=\"biz\">业务</th>")
                    .append("<th class=\"date\">施行日期</th><th>备注</th></tr></thead><tbody>");
            int i = 0;
            for (Map<String, Object> r : e.getValue()) {
                i++;
                String biz = switch (s(r.get("biz_type"))) {
                    case "danger" -> "危货";
                    case "cargo" -> "普货";
                    default -> "通用";
                };
                String isApp = "conditional".equals(s(r.get("is_applicable"))) ? "（有条件）" : "";
                h.append("<tr><td class=\"no\">").append(i).append("</td>")
                        .append("<td class=\"name\">").append(esc(s(r.get("law_name")))).append("</td>")
                        .append("<td>").append(esc(s(r.get("law_no")))).append("</td>")
                        .append("<td class=\"lv\">").append(esc(s(r.get("apply_level")))).append("</td>")
                        .append("<td>").append(esc(s(r.get("region")))).append("</td>")
                        .append("<td class=\"biz\">").append(biz).append(isApp).append("</td>")
                        .append("<td class=\"date\">").append(esc(s(r.get("effective_date")))).append("</td>")
                        .append("<td>").append(esc(s(r.get("remark")))).append("</td>")
                        .append("</tr>");
            }
            h.append("</tbody></table>");
        }
        h.append("</body></html>");
        return h.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @SuppressWarnings("unchecked")
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
}
