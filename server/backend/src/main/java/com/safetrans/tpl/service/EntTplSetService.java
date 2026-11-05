package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tpl.dto.ApplyTplSetReq;
import com.safetrans.tpl.dto.SaveAsReq;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.entity.TplSet;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.mapper.EntTplSetMapper;
import com.safetrans.tpl.mapper.TplSetMapper;
import com.safetrans.tpl.mapper.TplTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 企业范本服务：从服务商套实例化 → 当前应用范本 → 另存为「我的范本」→ 切换当前。
 */
@Service
public class EntTplSetService {

    private final EntTplSetMapper entMapper;
    private final TplSetMapper setMapper;
    private final TplTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public EntTplSetService(EntTplSetMapper entMapper, TplSetMapper setMapper,
                            TplTemplateMapper templateMapper, ObjectMapper objectMapper) {
        this.entMapper = entMapper;
        this.setMapper = setMapper;
        this.templateMapper = templateMapper;
        this.objectMapper = objectMapper;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        if (t == null) throw new BizException("未识别当前企业");
        return t;
    }

    /** 当前应用范本（is_current=1） */
    public EntTplSet current() {
        EntTplSet cur = entMapper.selectOne(new LambdaQueryWrapper<EntTplSet>()
                .eq(EntTplSet::getTenantId, tid())
                .eq(EntTplSet::getIsCurrent, 1));
        return cur;
    }

    /** 我的范本列表（不含当前应用，或含全部，按 created_at 倒序） */
    public List<EntTplSet> mySets() {
        return entMapper.selectList(new LambdaQueryWrapper<EntTplSet>()
                .eq(EntTplSet::getTenantId, tid())
                .orderByDesc(EntTplSet::getCreatedAt));
    }

    /** 从服务商范本套实例化为企业当前应用范本（深拷贝文件清单快照） */
    @Transactional
    public EntTplSet apply(ApplyTplSetReq req) {
        if (req.getSourceSetId() == null) {
            throw new BizException("请选择来源范本套");
        }
        TplSet src = setMapper.selectById(req.getSourceSetId());
        if (src == null) {
            throw new BizException("来源范本套不存在");
        }
        // 深拷贝文件清单为 JSON 快照
        List<TplTemplate> files = templateMapper.selectList(
                new LambdaQueryWrapper<TplTemplate>().eq(TplTemplate::getGroupId, src.getId()));
        String snapshot = toSnapshot(files);

        // 清除旧当前应用范本
        clearCurrent();

        EntTplSet ent = new EntTplSet();
        ent.setTenantId(tid());
        ent.setSourceSetId(src.getId());
        ent.setName(StringUtils.hasText(req.getName()) ? req.getName() : src.getName());
        ent.setBizType(src.getBizType());
        ent.setRegion(src.getRegion());
        ent.setIsCurrent(1);
        ent.setCustomized(0);
        ent.setFilesSnapshot(snapshot);
        ent.setAppliedAt(LocalDate.now());
        entMapper.insert(ent);
        return ent;
    }

    /** 另存为：把当前范本复制为一份新的「我的范本」并设为当前 */
    @Transactional
    public EntTplSet saveAs(SaveAsReq req) {
        EntTplSet cur = current();
        if (cur == null) {
            throw new BizException("尚未应用范本，请先选择范本套");
        }
        String newName = StringUtils.hasText(req.getName()) ? req.getName() : cur.getName() + "（副本）";

        clearCurrent();

        EntTplSet variant = new EntTplSet();
        variant.setTenantId(cur.getTenantId());
        variant.setSourceSetId(cur.getSourceSetId());
        variant.setName(newName);
        variant.setBizType(cur.getBizType());
        variant.setRegion(cur.getRegion());
        variant.setIsCurrent(1);
        variant.setCustomized(1);
        variant.setFilesSnapshot(cur.getFilesSnapshot());
        variant.setAppliedAt(cur.getAppliedAt());
        variant.setSavedAt(LocalDate.now());
        entMapper.insert(variant);
        return variant;
    }

    /** 切换到某个「我的范本」为当前 */
    @Transactional
    public EntTplSet setCurrent(Long id) {
        EntTplSet target = entMapper.selectById(id);
        if (target == null || !target.getTenantId().equals(tid())) {
            throw new BizException("范本不存在");
        }
        if (target.getIsCurrent() != null && target.getIsCurrent() == 1) {
            return target;
        }
        clearCurrent();
        target.setIsCurrent(1);
        entMapper.updateById(target);
        return target;
    }

    /** 清除当前应用范本标记 */
    private void clearCurrent() {
        EntTplSet cur = current();
        if (cur != null) {
            cur.setIsCurrent(0);
            entMapper.updateById(cur);
        }
    }

    /** 文件清单序列化为 JSON 快照 */
    private String toSnapshot(List<TplTemplate> files) {
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            // 兜底：仅保留关键字段
            List<Map<String, Object>> simple = files.stream().map(f -> Map.<String, Object>of(
                    "code", f.getCode() == null ? "" : f.getCode(),
                    "name", f.getName() == null ? "" : f.getName(),
                    "category", f.getCategory() == null ? "" : f.getCategory()
            )).toList();
            try {
                return objectMapper.writeValueAsString(simple);
            } catch (Exception ex) {
                return "[]";
            }
        }
    }
}
