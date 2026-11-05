package com.safetrans.common.storage;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通用文件上传服务（A2）。所有附件统一走 MinIO，并落 biz_file 供业务模块关联。
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {

    /** 单文件上限 20MB，扩展名白名单 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final List<String> ALLOW_EXT = List.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "txt", "zip", "mp3", "mp4");

    private final StorageService storage;
    private final JdbcTemplate jdbc;

    public FileController(StorageService storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) String bizModule,
                                              @RequestParam(required = false) Long bizId) {
        if (file.isEmpty()) throw ErrorCode.PARAM_INVALID.ex("文件为空");
        if (file.getSize() > MAX_SIZE) throw ErrorCode.PARAM_INVALID.ex("文件超过 20MB 限制");
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ext.isBlank() && !ALLOW_EXT.contains(ext)) throw ErrorCode.PARAM_INVALID.ex("不支持的文件类型：" + ext);

        Long tid = TenantContextHolder.tenantId();
        if (tid == null) tid = 0L;
        String key = tid + "/" + LocalDate.now().toString().replace("-", "")
                + "/" + IdUtil.fastSimpleUUID() + "_" + original.replace("/", "_");
        try {
            storage.upload(key, file.getBytes(), file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        } catch (Exception e) {
            throw ErrorCode.STORAGE_ERROR.ex(e.getMessage());
        }

        long id = IdUtil.getSnowflakeNextId();
        Long uid = TenantContextHolder.userId();
        jdbc.update("INSERT INTO `biz_file` " +
                        "(`id`,`tenant_id`,`biz_module`,`biz_id`,`file_name`,`object_key`,`file_size`,`content_type`,`uploader`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tid, bizModule, bizId, original, key, file.getSize(), file.getContentType(),
                uid == null ? 0L : uid, LocalDateTime.now(), uid == null ? 0L : uid);
        return Result.ok(Map.of(
                "id", id, "fileName", original, "objectKey", key,
                "size", file.getSize(), "url", "/api/file/" + id + "/download"));
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String bizModule,
                                                  @RequestParam(required = false) Long bizId) {
        String sql = "SELECT * FROM `biz_file` WHERE `deleted`=0";
        if (bizModule != null && !bizModule.isBlank()) sql += " AND `biz_module` = '" + bizModule.replace("'", "") + "'";
        if (bizId != null) sql += " AND `biz_id` = " + bizId;
        sql += " ORDER BY `id` DESC";
        return Result.ok(jdbc.queryForList(sql));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable long id, HttpServletResponse resp) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `biz_file` WHERE `id`=? AND `deleted`=0", id);
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex();
        Map<String, Object> f = rows.get(0);
        byte[] data = storage.download(String.valueOf(f.get("object_key")));
        String name = String.valueOf(f.get("file_name"));
        try {
            resp.setContentType(String.valueOf(f.get("content_type") == null ? "application/octet-stream" : f.get("content_type")));
            resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
            resp.setContentLength(data.length);
            resp.getOutputStream().write(data);
        } catch (Exception e) {
            throw ErrorCode.STORAGE_ERROR.ex(e.getMessage());
        }
    }
}
