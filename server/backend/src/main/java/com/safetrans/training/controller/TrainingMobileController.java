package com.safetrans.training.controller;

import com.safetrans.common.web.Result;
import com.safetrans.training.service.TrainingService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 小程序端「看和做」接口：学习中心 → 在线看视频上报进度 → 在线考核自动判分。
 * 对齐原型 mini/study.html 与《14-培训教育》5.2 / 5.4 / 10.4。
 *
 * <p>只要求登录（不额外限定角色），便于从业人员在 H5 端带 token 直接学习。
 */
@RestController
@RequestMapping("/api/training/m")
public class TrainingMobileController {

    private final TrainingService service;

    public TrainingMobileController(TrainingService service) {
        this.service = service;
    }

    /** 可切换学员（演示态：逐人测试学习闭环） */
    @GetMapping("/learners")
    public Result<List<Map<String, Object>>> learners() {
        return Result.ok(service.learners());
    }

    /** 学习中心首页：本月进度 + 课程列表 + 考核状态 */
    @GetMapping("/home")
    public Result<Map<String, Object>> home(
            @RequestParam(defaultValue = "0") long personId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        LocalDateTime now = LocalDateTime.now();
        int y = year == null ? now.getYear() : year;
        int m = month == null ? now.getMonthValue() : month;
        if (personId == 0L) {
            List<Map<String, Object>> ls = service.learners();
            if (ls.isEmpty()) return Result.ok(service.mobileHome(0L, y, m));
            personId = ((Number) ls.get(0).get("user_id")).longValue();
        }
        return Result.ok(service.mobileHome(personId, y, m));
    }

    /** 上报观看进度（心跳），支持断点续播 */
    @PostMapping("/progress")
    public Result<Map<String, Object>> progress(@RequestBody Map<String, Object> body) {
        return Result.ok(service.reportProgress(body));
    }

    /** 获取考核试卷（不返回答案；学时开关开启时校验学满） */
    @GetMapping("/exam/{assignId}")
    public Result<Map<String, Object>> examPaper(@PathVariable long assignId) {
        return Result.ok(service.examPaper(assignId));
    }

    /** 交卷自动判分 */
    @PostMapping("/exam/{assignId}/submit")
    public Result<Map<String, Object>> submit(@PathVariable long assignId,
                                              @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = body.get("answers") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return Result.ok(service.submitExam(assignId, answers));
    }

    /** 最近一次考核结果 */
    @GetMapping("/exam/{assignId}/result")
    public Result<Map<String, Object>> result(@PathVariable long assignId) {
        return Result.ok(service.examResult(assignId));
    }
}
