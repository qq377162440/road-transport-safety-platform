package com.safetrans.training.controller;

import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.web.Result;
import com.safetrans.training.service.TrainingService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PC 端「下发数据」接口：课程库 + 试题管理 + 岗中月度计划 + 学习统计。
 * 对齐原型 pc/video_exam.html、pc/training.html、pc/train_stat.html。
 *
 * <p>服务商 / 超管 / 企业均可访问；课程库编辑仅服务商与超管在页面上开启，
 * 企业视角为只读（由前端按角色控制按钮，接口统一鉴权到已登录用户）。
 */
@RestController
@RequestMapping("/api/training")
@RequireRole({"enterprise", "provider", "admin"})
public class TrainingController {

    private final TrainingService service;

    public TrainingController(TrainingService service) {
        this.service = service;
    }

    // ===== 字典 / 视频库 =====
    @GetMapping("/dict")
    public Result<Map<String, Object>> dict() {
        return Result.ok(service.dict());
    }

    @GetMapping("/video/list")
    public Result<List<Map<String, Object>>> videos() {
        return Result.ok(service.videos());
    }

    // ===== 课程库 =====
    @GetMapping("/course/list")
    public Result<Map<String, Object>> courses(@RequestParam Map<String, Object> q) {
        return Result.ok(service.courses(q));
    }

    @GetMapping("/course/{id}")
    public Result<Map<String, Object>> course(@PathVariable long id) {
        return Result.ok(service.course(id));
    }

    @PostMapping("/course")
    public Result<Long> saveCourse(@RequestBody Map<String, Object> body) {
        return Result.ok(service.saveCourse(body));
    }

    @DeleteMapping("/course/{id}")
    public Result<Void> deleteCourse(@PathVariable long id) {
        service.deleteCourse(id);
        return Result.ok();
    }

    // ===== 试题管理 =====
    @GetMapping("/paper/list")
    public Result<List<Map<String, Object>>> papers() {
        return Result.ok(service.papers());
    }

    @GetMapping("/paper/{id}")
    public Result<Map<String, Object>> paper(@PathVariable long id) {
        return Result.ok(service.paper(id));
    }

    @PostMapping("/paper")
    public Result<Long> savePaper(@RequestBody Map<String, Object> body) {
        return Result.ok(service.savePaper(body));
    }

    @DeleteMapping("/paper/{id}")
    public Result<Void> deletePaper(@PathVariable long id) {
        service.deletePaper(id);
        return Result.ok();
    }

    @PostMapping("/paper/{id}/question")
    public Result<Long> addQuestion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        body.put("paperId", id);
        return Result.ok(service.addQuestion(body));
    }

    @DeleteMapping("/question/{id}")
    public Result<Void> deleteQuestion(@PathVariable long id) {
        service.deleteQuestion(id);
        return Result.ok();
    }

    // ===== 岗中月度计划 =====
    @GetMapping("/plan/list")
    public Result<List<Map<String, Object>>> plans() {
        return Result.ok(service.plans());
    }

    @GetMapping("/plan/{id}")
    public Result<Map<String, Object>> plan(@PathVariable long id) {
        return Result.ok(service.plan(id));
    }

    @PostMapping("/plan")
    public Result<Long> savePlan(@RequestBody Map<String, Object> body) {
        return Result.ok(service.savePlan(body));
    }

    @PostMapping("/plan/{id}/publish")
    public Result<Void> publish(@PathVariable long id) {
        service.publishPlan(id);
        return Result.ok();
    }

    @PutMapping("/plan/{id}/force-learn")
    public Result<Void> forceLearn(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int force = body.get("forceLearn") == null ? 0
                : Integer.parseInt(String.valueOf(body.get("forceLearn")));
        service.setForceLearn(id, force);
        return Result.ok();
    }

    @GetMapping("/person/list")
    public Result<List<Map<String, Object>>> persons() {
        return Result.ok(service.persons());
    }

    @PostMapping("/plan/{id}/assign")
    public Result<Integer> assign(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(service.assign(id, body.get("personIds")));
    }

    // ===== 学习情况统计 =====
    @GetMapping("/stat/list")
    public Result<List<Map<String, Object>>> stats(
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) String status) {
        return Result.ok(service.stats(planId, status));
    }

    @GetMapping("/learner/list")
    public Result<List<Map<String, Object>>> learners() {
        return Result.ok(service.learners());
    }
}
