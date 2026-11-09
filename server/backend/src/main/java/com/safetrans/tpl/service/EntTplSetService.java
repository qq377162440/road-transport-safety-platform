package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.web.BizException;
import com.safetrans.tpl.dto.ApplyTplSetReq;
import com.safetrans.tpl.dto.SaveAsReq;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.entity.TplSet;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.mapper.EntTplSetMapper;
import com.safetrans.tpl.mapper.TplSetMapper;
import com.safetrans.tpl.mapper.TplTemplateMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业范本套服务：服务商套 → 应用为当前套 → 定制 → 另存为新套 → 切换/删除。
 *
 * <p>V17 起「范本套」是真正的内容容器：每套在 tpl_template 中拥有独立副本
 * （tenant_id=企业、group_id=ent_tpl_set.id）。切换当前应用套后，
 * 台账范本管理看到的内容、以及电子档案柜生成时使用的范本，随之整体切换。
 */
@Service
public class EntTplSetService {

    private final EntTplSetMapper entMapper;
    private final TplSetMapper setMapper;
    private final TplTemplateMapper templateMapper;
    private final TplSetScope scope;
    private final JdbcTemplate jdbc;

    public EntTplSetService(EntTplSetMapper entMapper, TplSetMapper setMapper,
                            TplTemplateMapper templateMapper, TplSetScope scope, JdbcTemplate jdbc) {
        this.entMapper = entMapper;
        this.setMapper = setMapper;
        this.templateMapper = templateMapper;
        this.scope = scope;
        this.jdbc = jdbc;
    }

    private Long tid() {
        Long t = scope.tid();
        if (t == null) throw new BizException("未识别当前企业");
        return t;
    }

    /** 当前应用范本套（is_current=1） */
    public EntTplSet current() {
        EntTplSet cur = scope.current();
        if (cur != null) decorate(cur);
        return cur;
    }

    /** 我的范本列表（含当前应用套） */
    public List<EntTplSet> mySets() {
        List<EntTplSet> list = entMapper.selectList(new LambdaQueryWrapper<EntTplSet>()
                .eq(EntTplSet::getTenantId, tid())
                .orderByDesc(EntTplSet::getIsCurrent)
                .orderByDesc(EntTplSet::getCreatedAt));
        list.forEach(this::decorate);
        return list;
    }

    /** 服务商全局范本套（只读基线，默认套排最前） */
    public List<TplSet> providerSets() {
        return setMapper.selectList(new LambdaQueryWrapper<TplSet>()
                .eq(TplSet::getTenantId, 0L)
                .eq(TplSet::getStatus, 1)
                .orderByDesc(TplSet::getIsDefault)
                .orderByAsc(TplSet::getSortNo)
                .orderByAsc(TplSet::getId));
    }

    /** 概览：服务商套 / 我的套 / 当前套 / 每套范本份数，一次返回供前端渲染切换器 */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (TplSet s : providerSets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("bizType", s.getBizType());
            m.put("region", s.getRegion());
            m.put("isDefault", s.getIsDefault());
            m.put("desc", s.getDesc());
            m.put("templateCount", countTemplates(0L, s.getId()));
            providers.add(m);
        }
        out.put("providers", providers);
        List<Map<String, Object>> mine = new ArrayList<>();
        for (EntTplSet e : mySets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(e.getId()));
            m.put("name", e.getName());
            m.put("sourceSetId", e.getSourceSetId());
            m.put("sourceName", e.getSourceName());
            m.put("isCurrent", e.getIsCurrent());
            m.put("customized", e.getCustomized());
            m.put("templateCount", e.getTemplateCount());
            m.put("appliedAt", e.getAppliedAt() == null ? null : e.getAppliedAt().toString());
            m.put("savedAt", e.getSavedAt() == null ? null : e.getSavedAt().toString());
            mine.add(m);
        }
        out.put("mine", mine);
        EntTplSet cur = current();
        if (cur == null) {
            out.put("current", null);
        } else {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", String.valueOf(cur.getId()));
            cm.put("name", cur.getName());
            cm.put("templateCount", cur.getTemplateCount() == null ? 0 : cur.getTemplateCount());
            out.put("current", cm);
        }
        return out;
    }

    private Integer countTemplates(long tenantId, long groupId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `tpl_template` WHERE `tenant_id`=? AND `group_id`=? AND `status`=1 AND `deleted`=0",
                Long.class, tenantId, groupId);
        return n == null ? 0 : n.intValue();
    }

    private void decorate(EntTplSet e) {
        e.setTemplateCount(countTemplates(e.getTenantId(), e.getId()));
        if (e.getSourceSetId() != null) {
            TplSet src = setMapper.selectById(e.getSourceSetId());
            e.setSourceName(src == null ? "服务商范本套" : src.getName());
        }
    }

    /** 从服务商范本套实例化为企业「当前应用范本套」（深拷贝全部范本内容） */
    @Transactional
    public EntTplSet apply(ApplyTplSetReq req) {
        if (req.getSourceSetId() == null) throw new BizException("请选择来源范本套");
        TplSet src = setMapper.selectById(req.getSourceSetId());
        if (src == null || !Long.valueOf(0L).equals(src.getTenantId())) {
            throw new BizException("来源范本套不存在");
        }
        List<TplTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, 0L)
                .eq(TplTemplate::getGroupId, src.getId())
                .eq(TplTemplate::getStatus, 1)
                .orderByAsc(TplTemplate::getBoxNo)
                .orderByAsc(TplTemplate::getId));
        if (rows.isEmpty()) {
            // 兜底：服务商套暂无内容时用全局默认套，保证应用后可用
            rows = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                    .eq(TplTemplate::getTenantId, 0L)
                    .eq(TplTemplate::getGroupId, TplSetScope.GLOBAL_SET_ID)
                    .eq(TplTemplate::getStatus, 1));
        }
        String name = StringUtils.hasText(req.getName()) ? req.getName() : src.getName();
        return copyInto(name, src.getId(), src.getBizType(), src.getRegion(), rows, false);
    }

    /** 另存为：把当前应用套（含企业定制）整体复制成一套新的「我的范本」，并设为当前 */
    @Transactional
    public EntTplSet saveAs(SaveAsReq req) {
        EntTplSet cur = scope.current();
        if (cur == null) throw new BizException("尚未应用范本，请先选择范本套");
        String newName = StringUtils.hasText(req.getName()) ? req.getName() : cur.getName() + "（副本）";
        List<TplTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, tid())
                .eq(TplTemplate::getGroupId, cur.getId())
                .eq(TplTemplate::getStatus, 1));
        return copyInto(newName, cur.getSourceSetId(), cur.getBizType(), cur.getRegion(), rows, true);
    }

    /** 切换到某个「我的范本」为当前应用套 */
    @Transactional
    public EntTplSet setCurrent(Long id) {
        EntTplSet target = entMapper.selectById(id);
        if (target == null || !target.getTenantId().equals(tid())) {
            throw new BizException("范本套不存在");
        }
        if (target.getIsCurrent() != null && target.getIsCurrent() == 1) {
            decorate(target);
            return target;
        }
        clearCurrent();
        target.setIsCurrent(1);
        entMapper.updateById(target);
        decorate(target);
        return target;
    }

    /** 删除非当前应用套（连同其范本副本） */
    @Transactional
    public void delete(Long id) {
        EntTplSet target = entMapper.selectById(id);
        if (target == null || !target.getTenantId().equals(tid())) throw new BizException("范本套不存在");
        if (target.getIsCurrent() != null && target.getIsCurrent() == 1) {
            throw new BizException("当前应用范本套不可删除，请先切换到其他套");
        }
        templateMapper.delete(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getTenantId, tid())
                .eq(TplTemplate::getGroupId, id));
        entMapper.deleteById(id);
    }

    /**
     * 建立一套新的企业范本套：写 ent_tpl_set → 拿到新 group_id → 深拷贝范本行 → 设为当前。
     */
    private EntTplSet copyInto(String name, Long sourceSetId, String bizType, String region,
                               List<TplTemplate> rows, boolean customized) {
        Long t = tid();
        clearCurrent();

        EntTplSet ent = new EntTplSet();
        ent.setTenantId(t);
        ent.setSourceSetId(sourceSetId);
        ent.setName(name);
        ent.setBizType(bizType);
        ent.setRegion(region);
        ent.setIsCurrent(1);
        ent.setCustomized(customized ? 1 : 0);
        ent.setAppliedAt(LocalDate.now());
        if (customized) ent.setSavedAt(LocalDate.now());
        entMapper.insert(ent);
        Long groupId = ent.getId();

        for (TplTemplate src : rows) {
            TplTemplate row = new TplTemplate();
            row.setTenantId(t);
            row.setGroupId(groupId);
            // 基线指针：优先继承来源全局范本 id，保证「恢复内置」可回到服务商原版
            row.setSourceId(src.getSourceId() != null ? src.getSourceId() : src.getId());
            row.setCode(src.getCode());
            row.setName(src.getName());
            row.setCategory(src.getCategory());
            row.setArchiveCode(src.getArchiveCode());
            row.setBoxNo(src.getBoxNo());
            row.setArchiveItem(src.getArchiveItem());
            row.setCatalogId(src.getCatalogId());
            row.setVersionLabel(src.getVersionLabel());
            row.setPostScope(src.getPostScope());
            row.setContent(src.getContent());
            row.setNeedSeal(src.getNeedSeal());
            row.setVersion(src.getVersion());
            row.setStatus(1);
            row.setCreatedBy(0L);
            row.setDeleted(0);
            templateMapper.insert(row);
        }
        ent.setTemplateCount(rows.size());
        TplSet srcSet = sourceSetId == null ? null : setMapper.selectById(sourceSetId);
        ent.setSourceName(srcSet == null ? "服务商范本套" : srcSet.getName());
        return ent;
    }

    /** 清除当前应用范本套标记 */
    private void clearCurrent() {
        EntTplSet cur = scope.current();
        if (cur != null) {
            cur.setIsCurrent(0);
            entMapper.updateById(cur);
        }
    }
}
