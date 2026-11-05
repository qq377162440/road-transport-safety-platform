package com.safetrans.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.org.entity.OrgDict;
import com.safetrans.org.entity.OrgPost;
import com.safetrans.org.mapper.OrgDictMapper;
import com.safetrans.org.mapper.OrgPostMapper;
import com.safetrans.tpl.service.TplRevisionLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 岗位服务：岗位树、改名、禁用、责任制拼装。
 * 职能（func_key）企业侧不可改；强制岗位（gm/safe/mon）不可禁用。
 */
@Service
public class OrgPostService {

    private final OrgPostMapper postMapper;
    private final OrgDictMapper dictMapper;
    private final TplRevisionLogService revisionService;

    public OrgPostService(OrgPostMapper postMapper, OrgDictMapper dictMapper,
                          TplRevisionLogService revisionService) {
        this.postMapper = postMapper;
        this.dictMapper = dictMapper;
        this.revisionService = revisionService;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        if (t == null) throw new BizException("未识别当前企业");
        return t;
    }

    /** 全部岗位（含禁用，用于岗位信息管理列表） */
    public List<OrgPost> listAll() {
        return postMapper.selectList(new LambdaQueryWrapper<OrgPost>()
                .eq(OrgPost::getTenantId, tid())
                .orderByAsc(OrgPost::getFuncKey));
    }

    /** 启用中的岗位（责任制/架构图用），按 func_key 稳定排序 */
    public List<OrgPost> enabledPosts() {
        return postMapper.selectList(new LambdaQueryWrapper<OrgPost>()
                .eq(OrgPost::getTenantId, tid())
                .eq(OrgPost::getEnabled, 1)
                .orderByAsc(OrgPost::getFuncKey));
    }

    /** 12 职能字典 */
    public List<OrgDict> dicts() {
        return dictMapper.selectList(new LambdaQueryWrapper<OrgDict>()
                .eq(OrgDict::getDictType, "post")
                .orderByAsc(OrgDict::getSort));
    }

    /**
     * 岗位改名：更新 post_name；若涉及制度联动，登记待修订。
     * func_key 不可改（企业侧）。
     */
    @Transactional
    public OrgPost rename(Long postId, String newName) {
        OrgPost post = postMapper.selectById(postId);
        if (post == null || !post.getTenantId().equals(tid())) {
            throw new BizException("岗位不存在");
        }
        String oldName = post.getPostName();
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            throw new BizException("岗位名称不能为空");
        }
        if (name.equals(oldName)) {
            return post;
        }
        post.setPostName(name);
        postMapper.updateById(post);

        // 登记待修订（岗位名称关联责任制正文）
        revisionService.mark(tid(), "org",
                "岗位改名「" + oldName + "」→「" + name + "」，已联动安全生产责任制等制度内容", null);
        return post;
    }

    /**
     * 禁用/恢复岗位。强制岗位（required=1）不可禁用。
     */
    @Transactional
    public OrgPost toggle(Long postId) {
        OrgPost post = postMapper.selectById(postId);
        if (post == null || !post.getTenantId().equals(tid())) {
            throw new BizException("岗位不存在");
        }
        if (post.getRequired() != null && post.getRequired() == 1 && post.getEnabled() == 1) {
            throw new BizException("总经理、安全员、监控员为强制岗位，不可禁用");
        }
        int target = (post.getEnabled() != null && post.getEnabled() == 1) ? 0 : 1;
        post.setEnabled(target);
        postMapper.updateById(post);

        revisionService.mark(tid(), "org",
                target == 0 ? "禁用岗位「" + post.getPostName() + "」，制度内容中已剔除该岗位"
                        : "恢复岗位「" + post.getPostName() + "」，制度内容中已纳入该岗位", null);
        return post;
    }

    /** 更新岗位目标/职责/考核标准（联动责任制），也登记待修订 */
    @Transactional
    public OrgPost updateContent(Long postId, String goals, String duties, String assessStd) {
        OrgPost post = postMapper.selectById(postId);
        if (post == null || !post.getTenantId().equals(tid())) {
            throw new BizException("岗位不存在");
        }
        post.setGoals(goals);
        post.setDuties(duties);
        post.setAssessStd(assessStd);
        postMapper.updateById(post);
        revisionService.mark(tid(), "org", "调整岗位「" + post.getPostName() + "」目标/职责/考核标准，制度内容已联动", null);
        return post;
    }

    /** 责任制结构化数据（供预览/PDF），只含启用岗位，禁用剔除 */
    public List<Map<String, Object>> responsibilityRows() {
        Map<String, OrgDict> dictByCode = dicts().stream()
                .collect(Collectors.toMap(OrgDict::getDictCode, Function.identity()));
        return enabledPosts().stream().map(p -> {
            OrgDict d = dictByCode.get(p.getFuncKey());
            return Map.<String, Object>of(
                    "funcKey", p.getFuncKey(),
                    "name", p.getPostName(),
                    "label", d != null ? d.getDictName() : "",
                    "goals", p.getGoals() == null ? "" : p.getGoals(),
                    "duties", p.getDuties() == null ? "" : p.getDuties(),
                    "assess", p.getAssessStd() == null ? "" : p.getAssessStd()
            );
        }).collect(Collectors.toList());
    }
}
