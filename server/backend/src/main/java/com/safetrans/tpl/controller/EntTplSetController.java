package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.dto.ApplyTplSetReq;
import com.safetrans.tpl.dto.SaveAsReq;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.service.EntTplSetService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 企业范本接口（当前应用范本 + 我的范本）。
 */
@RestController
@RequestMapping("/api/tpl/ent")
@RequireRole({"enterprise", "provider", "admin"})
public class EntTplSetController {

    private final EntTplSetService entService;

    public EntTplSetController(EntTplSetService entService) {
        this.entService = entService;
    }

    /** 当前应用范本 */
    @GetMapping("/current")
    public Result<EntTplSet> current() {
        return Result.ok(entService.current());
    }

    /** 我的范本列表 */
    @GetMapping("/mine")
    public Result<List<EntTplSet>> mine() {
        return Result.ok(entService.mySets());
    }

    /** 切换器概览：服务商范本套 + 我的范本 + 当前应用套（含每套范本份数） */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(entService.overview());
    }

    /** 从服务商套应用为新的当前应用范本套（深拷贝整套范本）。仅服务商 / 超管（增值服务）。 */
    @PostMapping("/apply")
    @RequireRole({"provider", "admin"})
    public Result<EntTplSet> apply(@RequestBody ApplyTplSetReq req) {
        return Result.ok(entService.apply(req));
    }

    /** 另存为新的范本套（深拷贝当前套含定制内容，并设为当前）。仅服务商 / 超管。 */
    @PostMapping("/save-as")
    @RequireRole({"provider", "admin"})
    public Result<EntTplSet> saveAs(@RequestBody SaveAsReq req) {
        return Result.ok(entService.saveAs(req));
    }

    /** 切换当前应用范本套。仅服务商 / 超管。 */
    @PutMapping("/{id}/current")
    @RequireRole({"provider", "admin"})
    public Result<EntTplSet> setCurrent(@PathVariable Long id) {
        return Result.ok(entService.setCurrent(id));
    }

    /** 删除非当前应用范本套。仅服务商 / 超管。 */
    @DeleteMapping("/{id}")
    @RequireRole({"provider", "admin"})
    public Result<Void> delete(@PathVariable Long id) {
        entService.delete(id);
        return Result.ok();
    }
}
