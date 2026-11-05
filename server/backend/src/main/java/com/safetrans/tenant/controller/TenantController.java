package com.safetrans.tenant.controller;

import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import com.safetrans.tenant.entity.SysTenant;
import com.safetrans.tenant.entity.TenantInfo;
import com.safetrans.tenant.service.TenantService;
import org.springframework.web.bind.annotation.*;

/**
 * 企业信息接口。
 */
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /** 当前企业名称（PDF 页眉 / 架构图公司节点） */
    @GetMapping("/name")
    public Result<String> name() {
        return Result.ok(tenantService.currentTenantName());
    }

    @GetMapping("/info")
    public Result<TenantInfo> info() {
        return Result.ok(tenantService.current());
    }

    @PutMapping("/info")
    public Result<TenantInfo> update(@RequestBody TenantInfo patch) {
        return Result.ok(tenantService.update(patch));
    }
}
