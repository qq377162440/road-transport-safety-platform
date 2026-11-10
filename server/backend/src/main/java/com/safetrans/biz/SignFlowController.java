package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
 * C6 自定义文件签署 · 完整流程闭环（设计 21 §7.12 / 详细设计 09 §7.12）。
 *
 * <p>地方性/区域性管理要求模板各异（限行区域登记、剧毒品通行证、长时间停车报告等），
 * 系统不硬编码穷举：服务商上传当地模板、自定义名称、归属档案盒，在模板上拖拽放置
 * 盖章/签名锚点；生成时复用印章引擎（企业章/自动红章）与签字库（历史签字/线上签/打印手签），
 * 合成带盖章+签名的自定义文件并归档到指定档案盒。
 */
@RestController
@RequestMapping("/api/flow/sign")
public class SignFlowController {

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;
    private final SealService sealService;

    public SignFlowController(JdbcTemplate jdbc, PdfService pdfService, StorageService storage, SealService sealService) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
        this.sealService = sealService;
    }

    private static final Map<String, String> TASK_STATUS = new LinkedHashMap<>();
    static { TASK_STATUS.put("draft", "草稿"); TASK_STATUS.put("pending", "待签署"); TASK_STATUS.put("signed", "已签署"); }

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private long l(Object o) { if (o == null) return 0L; try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; } }
    private String esc(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private List<Map<String, Object>> q(String sql, Object... a) { return jdbc.queryForList(sql, a); }
    private Map<String, Object> one(String sql, Object... a) {
        List<Map<String, Object>> r = q(sql, a);
        return r.isEmpty() ? Collections.emptyMap() : r.get(0);
    }
    private void exec(String sql, Object... a) { jdbc.update(sql, a); }
    private String company() { try { return sealService.companyName(); } catch (Exception e) { return "本企业"; } }

    private void log(String bizType, long bizId, String action, String from, String to, String detail) {
        try {
            exec("INSERT INTO `sign_flow_log` (`id`,`tenant_id`,`biz_type`,`biz_id`,`action`," +
                            "`from_status`,`to_status`,`detail`,`operator`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), bizType, bizId, action, from, to,
                    detail == null ? "" : (detail.length() > 490 ? detail.substring(0, 490) : detail),
                    operator(), LocalDateTime.now(), TenantContextHolder.userId());
        } catch (Exception ignore) { }
    }
    private String operator() { TenantContextHolder.Context c = TenantContextHolder.get(); return c == null ? "" : s(c.getUsername()); }

    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>" +
                "body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:30px;color:#222;font-size:12px}" +
                "h1{text-align:center;font-size:19px;letter-spacing:2px;margin:0 0 8px}" +
                ".doc-area{position:relative;width:794px;min-height:1123px;margin:10px auto;border:1px solid #ccc;padding:20px;background:#fff}" +
                ".head{text-align:center;font-size:14px;font-weight:700;margin-bottom:10px}" +
                ".anchor{position:absolute}" +
                ".seal-box img{max-height:90px}" +
                ".sign-box,.blank-box{border:1px dashed #c0392b;color:#c0392b;padding:4px 10px;border-radius:4px;font-size:12px;background:#fff7f7}" +
                ".blank-box{border-style:dashed;background:#fff}" +
                ".foot{position:absolute;bottom:14px;left:20px;right:20px;font-size:10px;color:#777;border-top:1px solid #ddd;padding-top:6px}" +
                "</style></head><body>" + body + "</body></html>";
    }
    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    // ==================================================================
    // 1. 概览 + 可选档案盒
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company());
        out.put("taskStatus", TASK_STATUS);
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (Map<String, Object> b : q("SELECT `code`,`name` FROM `archive_catalog` WHERE `level`=1 ORDER BY `code`"))
            boxes.add(Map.of("code", s(b.get("code")), "name", s(b.get("name"))));
        out.put("boxes", boxes);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tasks", count("SELECT COUNT(*) FROM `sign_task` WHERE `tenant_id`=? AND `deleted`=0", tid()));
        stats.put("pending", count("SELECT COUNT(*) FROM `sign_task` WHERE `tenant_id`=? AND `status`='pending' AND `deleted`=0", tid()));
        stats.put("signed", count("SELECT COUNT(*) FROM `sign_task` WHERE `tenant_id`=? AND `status`='signed' AND `deleted`=0", tid()));
        stats.put("archived", count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='sign' AND `deleted`=0", tid()));
        stats.put("boxes", boxes.size());
        out.put("stats", stats);
        out.put("tasks", tasks());
        return Result.ok(out);
    }

    private long count(String sql, Object... a) { Long n = jdbc.queryForObject(sql, Long.class, a); return n == null ? 0L : n; }

    private List<Map<String, Object>> tasks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT * FROM `sign_task` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC LIMIT 50", tid())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("id", String.valueOf(r.get("id")));
            m.put("statusName", TASK_STATUS.getOrDefault(s(r.get("status")), s(r.get("status"))));
            m.put("archived", count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='sign' AND `biz_id`=? AND `deleted`=0", tid(), l(r.get("id"))) > 0);
            m.put("anchorCount", anchorCount(s(r.get("anchors"))));
            m.put("boxName", boxName(s(r.get("archive_box"))));
            m.put("pdfId", pdfIdOf(l(r.get("id"))));
            out.add(m);
        }
        return out;
    }

    /** 签署结果 PDF 的 archive_pdf.id，供前端「查看成品」直接下载 */
    private String pdfIdOf(long taskId) {
        Map<String, Object> p = one("SELECT `id` FROM `archive_pdf` WHERE `tenant_id`=? AND `item_id`=? " +
                "AND `template_code`='SIGN_RESULT' AND `deleted`=0 ORDER BY `gen_time` DESC LIMIT 1", tid(), taskId);
        return p.isEmpty() ? "" : String.valueOf(p.get("id"));
    }

    private int anchorCount(String anchors) {
        if (anchors == null || anchors.isBlank()) return 0;
        try { return new JSONArray(anchors).size(); } catch (Exception e) { return 0; }
    }

    // ==================================================================
    // 2. 创建签署任务（上传模板 + 自定义名称 + 归属档案盒）
    // ==================================================================
    @PostMapping("/task")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> b) {
        String title = s(b.get("title"));
        if (title.isBlank()) return Result.ok(fail("请填写文件名称"));
        String box = s(b.get("archiveBox"));
        if (box.isBlank()) return Result.ok(fail("请选择归属档案盒"));
        Map<String, Object> cat = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        if (cat.isEmpty()) return Result.ok(fail("档案盒不存在：" + box));
        long id = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `sign_task` (`id`,`tenant_id`,`title`,`source_pdf`,`archive_box`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,0)",
                id, tid(), title, s(b.get("sourcePdf")), box, "draft", LocalDateTime.now(), TenantContextHolder.userId());
        log("sign", id, "create", "", "draft", "创建自定义签署任务：" + title + " → " + box);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("taskId", String.valueOf(id)); out.put("status", "draft");
        return Result.ok(out);
    }

    @GetMapping("/task/list")
    public Result<List<Map<String, Object>>> listApi() { return Result.ok(tasks()); }

    @GetMapping("/task/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `sign_task` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("任务不存在"));
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("id", String.valueOf(m.get("id")));
        out.put("statusName", TASK_STATUS.getOrDefault(s(m.get("status")), s(m.get("status"))));
        out.put("boxName", boxName(s(m.get("archive_box"))));
        out.put("anchorCount", anchorCount(s(m.get("anchors"))));
        out.put("archived", count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='sign' AND `biz_id`=? AND `deleted`=0", tid(), id) > 0);
        return Result.ok(out);
    }

    // ==================================================================
    // 3. 放置签署模块（盖章锚点 / 签名锚点）
    // ==================================================================
    @PutMapping("/task/{id}/anchors")
    public Result<Map<String, Object>> setAnchors(@PathVariable long id, @RequestBody Map<String, Object> b) {
        Map<String, Object> m = one("SELECT * FROM `sign_task` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("任务不存在"));
        if ("signed".equals(s(m.get("status")))) return Result.ok(fail("已签署的任务不可再调整锚点"));

        // 前端传来的是 ArrayList<LinkedHashMap>，Map/List.toString() 生成的是 Java 集合字符串
        // （形如 [{type=seal, x=520}]）而非合法 JSON，必须走 JSON 序列化；
        // 若前端直接传 JSON 字符串则原样使用。解析一次并规范化后入库，保证 sign() 可稳定读取。
        Object raw = b.get("anchors");
        String anchors = raw == null ? "[]"
                : raw instanceof String ? (String) raw : JSONUtil.toJsonStr(raw);
        JSONArray arr;
        try { arr = new JSONArray(anchors); }
        catch (Exception e) { return Result.ok(fail("锚点数据格式错误")); }
        int sealN = 0, signN = 0;
        for (Object o : arr) {
            JSONObject a = (JSONObject) o;
            if ("seal".equals(a.getStr("type"))) sealN++; else signN++;
        }
        exec("UPDATE `sign_task` SET `anchors`=?,`status`='pending',`updated_at`=? WHERE `id`=?",
                arr.toString(), LocalDateTime.now(), id);
        log("sign", id, "anchors", s(m.get("status")), "pending",
                "放置签署模块：共 " + arr.size() + " 个（盖章 " + sealN + " / 签名 " + signN + "）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("anchorCount", arr.size());
        out.put("sealCount", sealN); out.put("signCount", signN); out.put("status", "pending");
        return Result.ok(out);
    }

    // ==================================================================
    // 4. 一键签署（合成结果 PDF：盖章 + 签名）
    // ==================================================================
    @PostMapping("/task/{id}/sign")
    public Result<Map<String, Object>> sign(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `sign_task` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("任务不存在"));
        if (s(m.get("anchors")).isBlank() || "[]".equals(s(m.get("anchors"))))
            return Result.ok(fail("请先放置盖章/签名锚点"));

        String title = s(m.get("title"));
        String boxName = boxName(s(m.get("archive_box")));
        StringBuilder doc = new StringBuilder();
        doc.append("<div class='doc-area'>");
        doc.append("<div class='head'>").append(esc(company())).append(" · ").append(esc(title)).append("</div>");
        doc.append("<p style='text-align:center;color:#555'>（自定义文件 · 服务商上传地方模板 + 盖章/签名锚点合成）</p>");

        JSONArray arr;
        try { arr = new JSONArray(s(m.get("anchors"))); }
        catch (Exception e) { return Result.ok(fail("锚点数据解析失败")); }

        int sealN = 0, signN = 0, handN = 0;
        for (Object o : arr) {
            JSONObject a = (JSONObject) o;
            String type = a.getStr("type");
            int x = a.getInt("x", 80), y = a.getInt("y", 900);
            double scale = a.getDouble("scale", 1.0);
            String style = "left:" + x + "px;top:" + y + "px;transform:scale(" + scale + ")";
            String sealType = a.getStr("sealType");
            String signType = a.getStr("signType");
            String post = a.getStr("post");
            if (post == null || post.isBlank()) post = "负责人";
            if ("seal".equals(type)) {
                sealN++;
                if ("blank".equals(sealType)) {
                    // 打印手盖：留红色虚线盖章位，供线下打印后加盖实体章
                    handN++;
                    doc.append("<div class='anchor blank-box' style='").append(style)
                            .append("'>（此处加盖企业公章 · 打印后手盖）</div>");
                } else {
                    String sealHtml = sealService.sealHtml();
                    doc.append("<div class='anchor seal-box' style='").append(style).append("'>")
                            .append(sealHtml != null && !sealHtml.isBlank() ? sealHtml
                                    : "<div class='sign-box'>企业公章</div>").append("</div>");
                }
            } else { // sign：history 历史签字 / online 线上签 / blank 打印手签
                signN++;
                String signImg = "blank".equals(signType) ? "" : defaultSign(post);
                if (signImg != null && !signImg.isBlank()) {
                    doc.append("<div class='anchor' style='").append(style).append("'><img src='")
                            .append(signImg).append("' style='max-height:50px'/></div>");
                } else {
                    handN++;
                    doc.append("<div class='anchor sign-box' style='").append(style).append("'>")
                            .append("blank".equals(signType) ? "签字（打印后手写）：" + esc(post)
                                    : "（" + esc(post) + " 待签字）").append("</div>");
                }
            }
        }
        doc.append("<div class='foot'>本自定义文件由系统按锚点合成盖章/签名 · 生成时间：").append(LocalDateTime.now()).append(" · 归档盒：").append(esc(boxName)).append("</div>");
        doc.append("</div>");

        byte[] pdf = pdfService.render(page(title, doc.toString()));
        String key = tid() + "/sign/result_" + id + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        exec("UPDATE `sign_task` SET `result_pdf`=?,`status`='signed',`signer`=?,`signed_at`=? WHERE `id`=?", key, operator(), LocalDateTime.now(), id);
        log("sign", id, "sign", "pending", "signed", "一键签署合成：盖章" + sealN + "处 / 签名" + signN
                + "处 / 打印手写位" + handN + "处，结果PDF=" + key);
        long pdfId = saveArchivePdf(id, title, key, pdf.length, "SIGN_RESULT");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("status", "signed"); out.put("statusName", "已签署");
        out.put("sealCount", sealN); out.put("signCount", signN); out.put("handCount", handN);
        out.put("pdfId", String.valueOf(pdfId)); out.put("resultPdf", key);
        return Result.ok(out);
    }

    /** 查该岗位默认签字（历史签字优先），无则返回空（前端展示待签占位） */
    private String defaultSign(String post) {
        Map<String, Object> sig = one("SELECT `sign_image` FROM `tpl_signature` WHERE `tenant_id`=? AND `post`=? AND `is_default`=1 AND `deleted`=0 LIMIT 1", tid(), post);
        if (!sig.isEmpty() && !s(sig.get("sign_image")).isBlank()) return s(sig.get("sign_image"));
        Map<String, Object> any = one("SELECT `sign_image` FROM `tpl_signature` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        return any.isEmpty() ? "" : s(any.get("sign_image"));
    }

    private String boxName(String code) {
        Map<String, Object> c = one("SELECT `name` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", code);
        return c.isEmpty() ? code : s(c.get("name"));
    }

    private long saveArchivePdf(long taskId, String title, String key, int size, String tpl) {
        // 清理旧结果 PDF
        for (Map<String, Object> old : q("SELECT `id` FROM `archive_pdf` WHERE `item_id`=? AND `tenant_id`=? AND `template_code`=? AND `deleted`=0", taskId, tid(), tpl))
            exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `id`=?", l(old.get("id")));
        long pid = IdUtil.getSnowflakeNextId();
        exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                        "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,1,?,NULL,?,?,?,0)",
                pid, tid(), taskId, (short) LocalDate.now().getYear(), title + ".pdf", key, size,
                tpl, LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        return pid;
    }

    // ==================================================================
    // 5. 归档到指定档案盒
    // ==================================================================
    @PostMapping("/task/{id}/archive")
    public Result<Map<String, Object>> archive(@PathVariable long id) {
        Map<String, Object> m = one("SELECT * FROM `sign_task` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (m.isEmpty()) return Result.ok(fail("任务不存在"));
        if (!"signed".equals(s(m.get("status")))) return Result.ok(fail("请先完成签署"));
        String box = s(m.get("archive_box"));
        Map<String, Object> cat = one("SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", box);
        long catalogId = l(cat.get("id"));
        if (catalogId == 0L) return Result.ok(fail("未找到归档目录：" + box));

        String title = (isDanger() ? "危货·" : "") + company() + "·" + s(m.get("title"));
        Map<String, Object> item = one("SELECT `id` FROM `archive_item` WHERE `tenant_id`=? AND `biz_module`='sign' AND `biz_id`=? AND `deleted`=0 LIMIT 1", tid(), id);
        long itemId;
        if (item.isEmpty()) {
            itemId = IdUtil.getSnowflakeNextId();
            exec("INSERT INTO `archive_item` (`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`biz_module`,`biz_id`," +
                            "`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,2,1,'sign',?,?,?,?,1,?,?,0)",
                    // biz_id 必须是 taskId：查询用的也是 taskId，否则重复归档会不断新建 archive_item
                    itemId, tid(), catalogId, id, title, LocalDate.now(), LocalDate.now().getYear(), LocalDateTime.now(), TenantContextHolder.userId());
        } else {
            itemId = l(item.get("id"));
            exec("UPDATE `archive_item` SET `catalog_id`=?,`title`=? WHERE `id`=?", catalogId, title, itemId);
        }
        String key = s(m.get("result_pdf"));
        // 同步结果 PDF 到档案柜
        if (!key.isBlank()) {
            exec("UPDATE `archive_pdf` SET `deleted`=1 WHERE `item_id`=? AND `tenant_id`=? AND `template_code`='SIGN_BOX'", itemId, tid());
            long pid = IdUtil.getSnowflakeNextId();
            int size = 0;
            try { size = storage.download(key).length; } catch (Exception ignore) { }
            exec("INSERT INTO `archive_pdf` (`id`,`tenant_id`,`item_id`,`archive_year`,`file_name`,`file_path`,`file_size`," +
                            "`page_count`,`template_code`,`qr_content`,`gen_time`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,1,'SIGN_BOX',NULL,?,?,?,0)",
                    pid, tid(), itemId, (short) LocalDate.now().getYear(), title + ".pdf", key, size,
                    LocalDateTime.now(), LocalDateTime.now(), TenantContextHolder.userId());
        }
        exec("UPDATE `sign_task` SET `updated_at`=? WHERE `id`=?", LocalDateTime.now(), id);
        log("sign", id, "archive", "signed", "archived", "归档到 " + box + "（" + boxName(box) + "）");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true); res.put("box", box); res.put("boxName", boxName(box));
        res.put("catalogId", String.valueOf(catalogId)); res.put("archived", true);
        res.put("itemId", String.valueOf(itemId)); res.put("size", storageLen(key));
        return Result.ok(res);
    }

    private int storageLen(String key) {
        if (key.isBlank()) return 0;
        try { return storage.download(key).length; } catch (Exception e) { return 0; }
    }
    private boolean isDanger() {
        return count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `biz_type`='danger' AND `deleted`=0", tid()) > 0;
    }

    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> posts = new ArrayList<>();
        for (Map<String, Object> r : q("SELECT `post_name` FROM `org_post` WHERE `tenant_id`=? AND `deleted`=0 AND `enabled`=1", tid()))
            posts.add(Map.of("post", s(r.get("post_name"))));
        out.put("posts", posts);
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (Map<String, Object> b : q("SELECT `code`,`name` FROM `archive_catalog` WHERE `level`=1 ORDER BY `code`"))
            boxes.add(Map.of("code", s(b.get("code")), "name", s(b.get("name"))));
        out.put("boxes", boxes);
        return Result.ok(out);
    }

    private Map<String, Object> fail(String msg) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m; }
}
