package com.safetrans.archive.controller;

import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.service.ArchiveService;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 归档接口（盒二安全生产责任制）。
 */
@RestController
@RequestMapping("/api/archive")
@RequireRole({"enterprise", "provider", "admin"})
public class ArchiveController {

    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /** 责任制归档项列表 */
    @GetMapping("/responsibility/items")
    public Result<List<ArchiveItem>> items() {
        return Result.ok(archiveService.listResponsibilityItems());
    }

    /** 登记 + 生成责任制 PDF（一步完成） */
    @PostMapping("/responsibility/generate")
    public Result<ArchivePdf> generate() {
        return Result.ok(archiveService.registerAndGenerate());
    }

    /** 下载 PDF */
    @GetMapping("/pdf/{pdfId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long pdfId) {
        ArchivePdf pdf = archiveService.pdfMeta(pdfId);
        byte[] bytes = archiveService.download(pdfId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + pdf.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
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
