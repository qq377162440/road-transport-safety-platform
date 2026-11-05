package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.ResponsibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 安全生产责任制接口。
 */
@RestController
@RequestMapping("/api/tpl/responsibility")
@RequireRole({"enterprise", "provider", "admin"})
public class ResponsibilityController {

    private final ResponsibilityService responsibilityService;

    public ResponsibilityController(ResponsibilityService responsibilityService) {
        this.responsibilityService = responsibilityService;
    }

    /** 责任制结构化数据（启用岗位 + 目标/职责/考核） */
    @GetMapping
    public Result<List<Map<String, Object>>> build() {
        return Result.ok(responsibilityService.build());
    }

    /** 责任制完整正文 HTML（供预览 / PDF） */
    @GetMapping("/preview")
    public Result<String> preview() {
        return Result.ok(responsibilityService.previewHtml());
    }
}
