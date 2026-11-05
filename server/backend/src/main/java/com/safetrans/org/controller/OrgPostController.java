package com.safetrans.org.controller;

import com.safetrans.common.web.Result;
import com.safetrans.org.entity.OrgDict;
import com.safetrans.org.entity.OrgPost;
import com.safetrans.org.service.OrgPostService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 组织架构接口。
 */
@RestController
@RequestMapping("/api/org")
public class OrgPostController {

    private final OrgPostService postService;

    public OrgPostController(OrgPostService postService) {
        this.postService = postService;
    }

    /** 全部岗位（岗位信息管理列表） */
    @GetMapping("/posts")
    public Result<List<OrgPost>> posts() {
        return Result.ok(postService.listAll());
    }

    /** 启用岗位（架构图/责任制） */
    @GetMapping("/posts/enabled")
    public Result<List<OrgPost>> enabled() {
        return Result.ok(postService.enabledPosts());
    }

    /** 12 职能字典 */
    @GetMapping("/dicts")
    public Result<List<OrgDict>> dicts() {
        return Result.ok(postService.dicts());
    }

    /** 岗位改名（触发待修订） */
    @PutMapping("/post/{id}/rename")
    public Result<OrgPost> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(postService.rename(id, body.get("name")));
    }

    /** 禁用/恢复岗位 */
    @PutMapping("/post/{id}/toggle")
    public Result<OrgPost> toggle(@PathVariable Long id) {
        return Result.ok(postService.toggle(id));
    }

    /** 更新岗位目标/职责/考核标准 */
    @PutMapping("/post/{id}/content")
    public Result<OrgPost> updateContent(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(postService.updateContent(id, body.get("goals"), body.get("duties"), body.get("assess")));
    }

    /** 责任制结构化数据（预览/PDF） */
    @GetMapping("/responsibility")
    public Result<List<Map<String, Object>>> responsibility() {
        return Result.ok(postService.responsibilityRows());
    }
}
