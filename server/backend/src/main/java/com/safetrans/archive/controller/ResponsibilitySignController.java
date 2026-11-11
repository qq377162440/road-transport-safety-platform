package com.safetrans.archive.controller;

import com.safetrans.archive.service.ResponsibilitySignService;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.List;
import java.util.Map;

/**
 * 安全生产责任书「上下级双签」接口（用户 2026-11-11 需求 ②）。
 *
 * <p>上下级关系来自安全管理架构（org_post.parent_id + org_member），最顶层主要负责人
 * 与公司签署；只有上下级双方都签署后才生效并生成生效 PDF。
 */
@RestController
@RequestMapping("/api/archive/resp-sign")
@RequireRole({"enterprise", "provider", "admin"})
public class ResponsibilitySignController {

    private final ResponsibilitySignService service;

    public ResponsibilitySignController(ResponsibilitySignService service) {
        this.service = service;
    }

    private static int year(Integer y) { return y == null ? Year.now().getValue() : y; }

    /** 本年度签订总览（自动按当前架构同步关系）。 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(
            @RequestParam(value = "year", required = false) Integer year) {
        return Result.ok(service.overview(year(year)));
    }

    /**
     * 单方签署：body.side = sup（上级/公司）| sub（下级本人），body.signName 为签名人姓名。
     * 单方签署后状态为 partial（未生效）；双方签齐才 effective 并生成 PDF。
     */
    @PostMapping("/sign")
    public Result<Map<String, Object>> sign(@RequestBody Map<String, Object> body) {
        long id = Long.parseLong(String.valueOf(body.get("id")));
        String side = body.get("side") == null ? "" : String.valueOf(body.get("side"));
        String name = body.get("signName") == null ? "" : String.valueOf(body.get("signName"));
        return Result.ok(service.sign(id, side, name));
    }

    /** 手动同步架构关系（组织架构变更后自动调用，这里也提供显式入口）。 */
    @PostMapping("/sync")
    public Result<Map<String, Object>> sync(@RequestBody(required = false) Map<String, Object> body) {
        Integer y = body == null || body.get("year") == null ? null
                : Integer.parseInt(String.valueOf(body.get("year")));
        return Result.ok(service.onOrgChanged(y));
    }

    /** 某责任人的全部历史版本（含已作废的旧签署件）。 */
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(
            @RequestParam("subPersonId") long subPersonId,
            @RequestParam(value = "year", required = false) Integer year) {
        return Result.ok(service.history(subPersonId, year(year)));
    }
}
