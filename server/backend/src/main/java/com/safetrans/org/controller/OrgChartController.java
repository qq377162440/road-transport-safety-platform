package com.safetrans.org.controller;

import com.safetrans.common.web.Result;
import com.safetrans.org.service.OrgChartService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 安全管理架构图接口（按原型 org.html 正式版）。
 *
 * <p>读写全部落在 PC 端同一批业务表，PDF 与屏幕、电子档案柜归档件同源。
 */
@RestController
@RequestMapping("/api/org/chart")
public class OrgChartController {

    private final OrgChartService service;

    public OrgChartController(OrgChartService service) {
        this.service = service;
    }

    /** 架构图聚合数据：公司名、树、统计、人员名册、制度池 */
    @GetMapping
    public Result<Map<String, Object>> chart() {
        return Result.ok(service.chart());
    }

    /** 责任制联动预览（启用岗位动态生成，禁用 / 兼并岗位自动剔除） */
    @GetMapping("/responsibility")
    public Result<List<Map<String, Object>>> responsibility() {
        return Result.ok(service.responsibility());
    }

    /** 下载 A4 横向架构图 PDF（真实文件，不是浏览器「另存为 PDF」） */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf() {
        byte[] bytes = service.pdfBytes();
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("安全管理组织架构.pdf", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** 自包含 A4 横向打印页：与下载 PDF 同源，打开即调起打印对话框 */
    @GetMapping(value = "/print", produces = "text/html;charset=UTF-8")
    public String print() {
        String html = service.chartHtml();
        String bar = "<div style=\"max-width:1120px;margin:0 auto 8px;padding:8px 14px;background:#fff;"
                + "border-bottom:1px solid #E2E8F0;font:13px system-ui,sans-serif;color:#334155;"
                + "display:flex;align-items:center;gap:12px\">"
                + "<b>安全管理组织架构 · A4 横向打印版</b>"
                + "<span style=\"flex:1;color:#64748B;font-size:12px\">打印对话框中选择「另存为 PDF」即可保存 PDF 文件。</span>"
                + "<button onclick=\"window.print()\" style=\"border:0;background:#2563EB;color:#fff;"
                + "border-radius:7px;padding:7px 14px;font-size:13px;cursor:pointer\">打印 / 另存为 PDF</button></div>";
        String noPrint = "<style>@media print{.ocg-noprint{display:none!important}}</style>";
        String script = "<script>window.addEventListener('load',function(){"
                + "setTimeout(function(){try{window.focus();window.print();}catch(e){}},300);});</script>";
        return html.replace("<div class=\"hd\">",
                        "<div class=\"ocg-noprint\">" + bar + "</div>" + noPrint + "<div class=\"hd\">")
                .replace("</body>", script + "</body>");
    }

    /** 编辑岗位（名称 / 上级 / 兼并 / 权限 · 目标 · 职责 · 考核 / 关联制度） */
    @PutMapping("/post/{id}")
    public Result<String> updatePost(@PathVariable long id, @RequestBody Map<String, Object> body) {
        service.updatePost(id, body);
        return Result.ok("已保存并联动责任制");
    }

    /** 更换岗位人员（只从系统已录入人员名册选取） */
    @PutMapping("/post/{id}/person")
    public Result<String> assignPerson(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String personId = body.get("personId") == null ? "" : String.valueOf(body.get("personId"));
        service.assignPerson(id, personId);
        return Result.ok("岗位人员已更新");
    }

    /** 重置架构演示数据（恢复 V16 初始态） */
    @PostMapping("/reset")
    public Result<String> reset() {
        service.resetDemo();
        return Result.ok("架构演示数据已重置");
    }
}
