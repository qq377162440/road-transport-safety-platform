package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.entity.TplRevisionLog;
import com.safetrans.tpl.service.TplRevisionLogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 待修订记录接口（范本页提示条 + 三按钮）。
 */
@RestController
@RequestMapping("/api/tpl/revision")
@RequireRole({"enterprise", "provider", "admin"})
public class TplRevisionLogController {

    private final TplRevisionLogService revisionService;

    public TplRevisionLogController(TplRevisionLogService revisionService) {
        this.revisionService = revisionService;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        return t == null ? 0L : t;
    }

    /** 待处理修订记录（提示条） */
    @GetMapping("/pending")
    public Result<List<TplRevisionLog>> pending() {
        return Result.ok(revisionService.pending(tid()));
    }

    /** 全部修订记录 */
    @GetMapping
    public Result<List<TplRevisionLog>> all() {
        return Result.ok(revisionService.all(tid()));
    }

    /** 处理修订：1保存到当前 / 2另存为 / 3稍后 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null || status < 1 || status > 3) {
            return Result.fail("无效状态");
        }
        revisionService.updateStatus(id, status);
        return Result.ok();
    }
}
