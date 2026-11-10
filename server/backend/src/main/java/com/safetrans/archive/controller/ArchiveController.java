package com.safetrans.archive.controller;

import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.service.ArchiveService;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.TplLibraryService;
import com.safetrans.tpl.service.TplPersonResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 归档接口（盒二安全生产责任制）。
 */
@RestController
@RequestMapping("/api/archive")
@RequireRole({"enterprise", "provider", "admin"})
public class ArchiveController {

    private final ArchiveService archiveService;
    private final TplLibraryService tplLibraryService;

    public ArchiveController(ArchiveService archiveService, TplLibraryService tplLibraryService) {
        this.archiveService = archiveService;
        this.tplLibraryService = tplLibraryService;
    }

    /** 责任制归档项列表 */
    @GetMapping("/responsibility/items")
    public Result<List<ArchiveItem>> items() {
        return Result.ok(archiveService.listResponsibilityItems());
    }

    /** 登记 + 生成责任制 PDF（一步完成，旧链路保留兼容）。范本生成为服务商 / 超管增值服务。 */
    @PostMapping("/responsibility/generate")
    @RequireRole({"provider", "admin"})
    public Result<ArchivePdf> generate() {
        return Result.ok(archiveService.registerAndGenerate());
    }

    /**
     * 按「台账范本管理」当前应用范本生成/重新生成台账 PDF。
     * 改了范本后再次调用本接口，产出的 PDF 即为新范本内容。
     * 权限：仅服务商 / 超管（增值服务）；平台账号须用 X-Target-Tenant 指定目标企业，
     * 成品按目标企业的范本套 / 岗位人名 / 公司章渲染并归档到该企业的档案柜。
     */
    @PostMapping("/template/generate")
    @RequireRole({"provider", "admin"})
    public Result<ArchivePdf> generateByTemplate(@RequestBody Map<String, Object> body) {
        String code = body.get("code") == null ? "" : String.valueOf(body.get("code"));
        String itemName = body.get("itemName") == null ? "" : String.valueOf(body.get("itemName"));
        String post = body.get("post") == null ? "" : String.valueOf(body.get("post"));
        int boxNo = body.get("boxNo") == null ? 1 : Integer.parseInt(String.valueOf(body.get("boxNo")));
        int year = body.get("year") == null ? java.time.Year.now().getValue()
                : Integer.parseInt(String.valueOf(body.get("year")));
        return Result.ok(archiveService.generateByTemplate(code, itemName, boxNo, year, post));
    }

    /** 生成前检查：该范本在当前岗位下读不到哪些人名（前端据此提醒，而不是静默留白） */
    @GetMapping("/template/check")
    public Result<List<TplPersonResolver.Missing>> checkPersons(@RequestParam("code") String code,
                                                                @RequestParam(value = "post", required = false) String post) {
        return Result.ok(tplLibraryService.checkMissing(code, post));
    }

    /** 岗位人员名册（档案柜「补录人名」弹窗用） */
    @GetMapping("/template/roster")
    public Result<List<Map<String, Object>>> roster() {
        return Result.ok(tplLibraryService.roster());
    }

    /** 补录岗位人名：写入 org_member，之后生成台账即可自动读到 */
    @PostMapping("/template/assign")
    public Result<String> assign(@RequestBody Map<String, Object> body) {
        String post = body.get("post") == null ? "" : String.valueOf(body.get("post"));
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        return Result.ok(tplLibraryService.assignPerson(post, name));
    }

    /** 下载 PDF / 上传留存文件（图片、扫描件等）：按文件名后缀返回正确 Content-Type */
    @GetMapping("/pdf/{pdfId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long pdfId) {
        ArchivePdf pdf = archiveService.pdfMeta(pdfId);
        byte[] bytes = archiveService.download(pdfId);
        String fileName = pdf.getFileName() == null ? "download" : pdf.getFileName();
        // 中文原名不能直接放进 HTTP header（ISO-8859-1），会被容器丢弃导致前端拿不到文件名。
        // 用 RFC 5987 filename* 传 UTF-8 原名，同时保留纯 ASCII 的 filename 兜底。
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .contentType(resolveMediaType(fileName))
                .body(bytes);
    }

    /** 依据文件名后缀推断响应 Content-Type（上传留存可能是图片/扫描件，不能一律当 PDF） */
    private MediaType resolveMediaType(String fileName) {
        if (fileName == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.valueOf("image/bmp");
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** 上传留存：把证照/保单/台账扫描件等上传至档案盒（boxNo 为数字盒号，itemTitle 为归档项标题，year 为归档年度） */
    @PostMapping("/upload")
    public Result<ArchivePdf> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("boxNo") Integer boxNo,
                                    @RequestParam("itemTitle") String itemTitle,
                                    @RequestParam("year") Integer year) {
        return Result.ok(archiveService.uploadFile(file, boxNo, itemTitle, year));
    }

    /** 某盒某年度的上传留存归档项列表 */
    @GetMapping("/items")
    public Result<List<ArchiveItem>> items(@RequestParam("boxNo") Integer boxNo,
                                          @RequestParam("year") Integer year) {
        return Result.ok(archiveService.listUploadItems(boxNo, year));
    }
}
