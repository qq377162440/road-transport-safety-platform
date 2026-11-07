package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.SealService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 印章管理接口 —— 原型《09》6.2「两种印章来源」：
 * ① 客户上传真实印章图（优先）；② 读取公司名自动生成圆形红章（兜底）。
 * 所有台账预览 / PDF 统一由 TplRenderEngine 在落款处盖当前生效的章。
 */
@RestController
@RequestMapping("/api/tpl/seal")
@RequireRole({"enterprise", "provider", "admin"})
public class SealController {

    private final SealService sealService;

    public SealController(SealService sealService) {
        this.sealService = sealService;
    }

    /** 当前生效印章（含 dataUri，管理页可直接预览） */
    @GetMapping
    public Result<SealService.SealInfo> current() {
        return Result.ok(sealService.current());
    }

    /** 上传真实印章图（透明底 PNG，前端转 data URI 传入） */
    @PostMapping("/upload")
    public Result<SealService.SealInfo> upload(@RequestBody Map<String, Object> body) {
        Object image = body.get("image");
        sealService.upload(image == null ? "" : String.valueOf(image));
        return Result.ok(sealService.current());
    }

    /** 恢复系统自动生成的圆形红章 */
    @DeleteMapping
    public Result<SealService.SealInfo> reset() {
        sealService.reset();
        return Result.ok(sealService.current());
    }
}
