package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.dto.TplSetCreateReq;
import com.safetrans.tpl.entity.TplSet;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.service.TplSetService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 范本套接口（服务商侧）。
 */
@RestController
@RequestMapping("/api/tpl/set")
public class TplSetController {

    private final TplSetService setService;

    public TplSetController(TplSetService setService) {
        this.setService = setService;
    }

    /** 查全部范本套 */
    @GetMapping
    @RequireRole({"provider", "admin", "enterprise"})
    public Result<List<TplSet>> list() {
        return Result.ok(setService.listAll());
    }

    /** 查单个范本套 */
    @GetMapping("/{id}")
    @RequireRole({"provider", "admin", "enterprise"})
    public Result<TplSet> detail(@PathVariable Long id) {
        return Result.ok(setService.getById(id));
    }

    /** 查范本套内文件清单 */
    @GetMapping("/{id}/files")
    @RequireRole({"provider", "admin", "enterprise"})
    public Result<List<TplTemplate>> files(@PathVariable Long id) {
        return Result.ok(setService.files(id));
    }

    /** 新建范本套（仅服务商/超管） */
    @PostMapping
    @RequireRole({"provider", "admin"})
    public Result<TplSet> create(@RequestBody TplSetCreateReq req) {
        return Result.ok(setService.create(req));
    }

    /** 设为默认套（仅服务商/超管） */
    @PutMapping("/{id}/default")
    @RequireRole({"provider", "admin"})
    public Result<TplSet> setDefault(@PathVariable Long id) {
        return Result.ok(setService.setDefault(id));
    }
}
