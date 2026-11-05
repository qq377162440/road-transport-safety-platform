package com.safetrans.common.pdf;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.ResponsibilityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDF 生成测试接口（验证中文渲染 + 下载）。
 */
@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final PdfService pdfService;
    private final ResponsibilityService responsibilityService;

    public PdfController(PdfService pdfService, ResponsibilityService responsibilityService) {
        this.pdfService = pdfService;
        this.responsibilityService = responsibilityService;
    }

    /** 测试：把安全生产责任制渲染为 PDF 并直接下载 */
    @GetMapping("/test")
    @RequireRole({"enterprise", "provider", "admin"})
    public ResponseEntity<byte[]> test() {
        String html = responsibilityService.previewHtml();
        byte[] pdf = pdfService.render(html);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"responsibility.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
