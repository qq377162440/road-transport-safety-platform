package com.safetrans.tpl.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.dto.ApplyTplSetReq;
import com.safetrans.tpl.dto.SaveAsReq;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.service.EntTplSetService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 从服务商套实例化 */
    @PostMapping("/apply")
    public Result<EntTplSet> apply(@RequestBody ApplyTplSetReq req) {
        return Result.ok(entService.apply(req));
    }

    /** 另存为我的范本 */
    @PostMapping("/save-as")
    public Result<EntTplSet> saveAs(@RequestBody SaveAsReq req) {
        return Result.ok(entService.saveAs(req));
    }

    /** 切换当前应用范本 */
    @PutMapping("/{id}/current")
    public Result<EntTplSet> setCurrent(@PathVariable Long id) {
        return Result.ok(entService.setCurrent(id));
    }
}
