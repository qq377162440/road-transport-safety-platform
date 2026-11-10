package com.safetrans.mini.controller;

import com.safetrans.common.web.Result;
import com.safetrans.mini.service.MiniService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 小程序端（运管管）正式版接口。
 *
 * <p>只要求登录，不限定后台角色——从业人员用 H5 带 token 直接使用。
 * 所有数据来自 / 写回 PC 端同一批业务表，实现双向互通：
 * <ul>
 *   <li>PC 下发：check_task / meeting / training_plan / sign_task / warn_record → 小程序可见</li>
 *   <li>小程序上报：check_record / danger_record / vehicle_repair /
 *       meeting_attendee / sign_task / tpl_signature → PC 台账可见</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mini")
public class MiniController {

    private final MiniService service;

    public MiniController(MiniService service) {
        this.service = service;
    }

    private long pid(long personId) {
        return personId > 0 ? personId : service.defaultPersonId();
    }

    private long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    // ---- 身份 / 首页 ----
    @GetMapping("/bootstrap")
    public Result<Map<String, Object>> bootstrap(
            @RequestParam(defaultValue = "0") long personId,
            @RequestParam(defaultValue = "worker") String role) {
        return Result.ok(service.bootstrap(pid(personId), role));
    }

    @GetMapping("/home")
    public Result<Map<String, Object>> home(
            @RequestParam(defaultValue = "0") long personId,
            @RequestParam(defaultValue = "worker") String role) {
        return Result.ok(service.home(pid(personId), role));
    }

    @GetMapping("/mgr/overview")
    public Result<Map<String, Object>> managerOverview() {
        return Result.ok(service.managerOverview());
    }

    // ---- 一日三检 ----
    @GetMapping("/check/today")
    public Result<Map<String, Object>> checkToday(@RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.checkToday(pid(personId)));
    }

    @GetMapping("/check/items")
    public Result<List<Map<String, Object>>> checkItems(
            @RequestParam(defaultValue = "out") String phase) {
        return Result.ok(service.checkItems(phase));
    }

    @GetMapping("/check/records")
    public Result<List<Map<String, Object>>> checkRecords(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.checkRecords(pid(personId)));
    }

    // ---- 会议签到 ----
    @GetMapping("/meeting/list")
    public Result<List<Map<String, Object>>> meetings(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.meetings(pid(personId)));
    }

    @PostMapping("/meeting/{id}/sign")
    public Result<Map<String, Object>> signMeeting(
            @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        long personId = pid(num(b.get("personId")));
        return Result.ok(service.signMeeting(id, personId, b.get("signImage") == null ? "" : String.valueOf(b.get("signImage"))));
    }

    // ---- 隐患 / 维修 ----
    @GetMapping("/danger/list")
    public Result<List<Map<String, Object>>> dangers(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.dangers(pid(personId)));
    }

    @PostMapping("/danger/report")
    public Result<Map<String, Object>> reportDanger(@RequestBody Map<String, Object> body) {
        return Result.ok(service.reportDanger(body));
    }

    @GetMapping("/repair/list")
    public Result<List<Map<String, Object>>> repairs(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.repairs(pid(personId)));
    }

    @PostMapping("/repair/report")
    public Result<Map<String, Object>> reportRepair(@RequestBody Map<String, Object> body) {
        return Result.ok(service.reportRepair(body));
    }

    // ---- 文件签署 ----
    @GetMapping("/sign/list")
    public Result<List<Map<String, Object>>> signTasks(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.signTasks(pid(personId)));
    }

    @PostMapping("/sign/{id}")
    public Result<Map<String, Object>> signTask(
            @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        long personId = pid(num(b.get("personId")));
        return Result.ok(service.signTask(id, personId,
                b.get("signImage") == null ? "" : String.valueOf(b.get("signImage")),
                b.get("personName") == null ? "从业人员" : String.valueOf(b.get("personName"))));
    }

    // ---- 统一签到入口（会议签到 + 演练签到，司机只看到一个"签到"）----
    @GetMapping("/checkin/tasks")
    public Result<List<Map<String, Object>>> checkinTasks(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.checkinTasks(pid(personId)));
    }

    @PostMapping("/checkin/sign")
    public Result<Map<String, Object>> checkinSign(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        long personId = pid(num(b.get("personId")));
        String bizModule = b.get("bizModule") == null ? "" : String.valueOf(b.get("bizModule"));
        long bizId = num(b.get("bizId"));
        if (bizModule.isEmpty() || bizId == 0L) return Result.ok(Map.of("ok", false, "msg", "缺少签到对象"));
        return Result.ok(service.unifiedCheckin(bizModule, bizId, personId,
                b.get("signImage") == null ? "data:image/png;base64,MINI_SIGN" : String.valueOf(b.get("signImage"))));
    }

    // ---- 公告 / 通知 ----
    @GetMapping("/notice/list")
    public Result<List<Map<String, Object>>> notices(
            @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.notices(pid(personId)));
    }

    @PostMapping("/notice/{id}/read")
    public Result<Map<String, Object>> readNotice(
            @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        long personId = pid(num(b.get("personId")));
        return Result.ok(service.readNotice(id, personId));
    }

    // ---- 配置化只读列表：risk / budget / credit / checks / dangers / repairs ----
    @GetMapping("/view/{type}")
    public Result<List<Map<String, Object>>> view(
            @PathVariable String type, @RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.view(type, pid(personId)));
    }

    // ---- 我的 ----
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(defaultValue = "0") long personId) {
        return Result.ok(service.mine(pid(personId)));
    }
}
