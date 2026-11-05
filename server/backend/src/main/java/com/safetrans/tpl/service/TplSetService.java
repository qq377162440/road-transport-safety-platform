package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.web.BizException;
import com.safetrans.tpl.dto.TplSetCreateReq;
import com.safetrans.tpl.entity.TplSet;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.mapper.TplSetMapper;
import com.safetrans.tpl.mapper.TplTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 范本套服务（服务商侧）。范本套为全局（tenant_id=0），所有企业可调用。
 */
@Service
public class TplSetService {

    private final TplSetMapper setMapper;
    private final TplTemplateMapper templateMapper;

    public TplSetService(TplSetMapper setMapper, TplTemplateMapper templateMapper) {
        this.setMapper = setMapper;
        this.templateMapper = templateMapper;
    }

    /** 查全部范本套（按 sort_no 升序） */
    public List<TplSet> listAll() {
        return setMapper.selectList(new LambdaQueryWrapper<TplSet>()
                .eq(TplSet::getTenantId, 0L)
                .orderByAsc(TplSet::getSortNo)
                .orderByAsc(TplSet::getId));
    }

    /** 查单个范本套 */
    public TplSet getById(Long id) {
        TplSet set = setMapper.selectById(id);
        if (set == null) {
            throw new BizException("范本套不存在");
        }
        return set;
    }

    /** 查某个范本套内的文件清单 */
    public List<TplTemplate> files(Long setId) {
        return templateMapper.selectList(new LambdaQueryWrapper<TplTemplate>()
                .eq(TplTemplate::getGroupId, setId)
                .orderByAsc(TplTemplate::getId));
    }

    /** 新建范本套 */
    @Transactional
    public TplSet create(TplSetCreateReq req) {
        if (!StringUtils.hasText(req.getName())) {
            throw new BizException("范本套名称不能为空");
        }
        String bizType = StringUtils.hasText(req.getBizType()) ? req.getBizType().toUpperCase() : "GENERAL";
        String region = StringUtils.hasText(req.getRegion()) ? req.getRegion().toUpperCase() : "ALL";

        TplSet set = new TplSet();
        set.setTenantId(0L);
        set.setCode(StringUtils.hasText(req.getCode()) ? req.getCode() : autoCode(bizType));
        set.setName(req.getName());
        set.setBizType(bizType);
        set.setRegion(region);
        set.setIsDefault(req.getIsDefault() != null ? req.getIsDefault() : 0);
        set.setDesc(req.getDesc());
        set.setStatus(1);
        set.setSortNo(req.getSortNo() != null ? req.getSortNo() : 0);

        // 若设为默认，清除同 biz_type 下其他默认标记（每个运输类型仅一个默认）
        if (set.getIsDefault() == 1) {
            clearDefault(bizType, null);
        }

        setMapper.insert(set);
        return set;
    }

    /** 设为默认套（同 biz_type 唯一默认） */
    @Transactional
    public TplSet setDefault(Long id) {
        TplSet set = getById(id);
        clearDefault(set.getBizType(), id);
        set.setIsDefault(1);
        setMapper.updateById(set);
        return set;
    }

    /** 清除某运输类型下的默认标记（排除 excludeId） */
    private void clearDefault(String bizType, Long excludeId) {
        List<TplSet> list = setMapper.selectList(new LambdaQueryWrapper<TplSet>()
                .eq(TplSet::getTenantId, 0L)
                .eq(TplSet::getBizType, bizType)
                .eq(TplSet::getIsDefault, 1));
        for (TplSet s : list) {
            if (excludeId != null && excludeId.equals(s.getId())) {
                continue;
            }
            s.setIsDefault(0);
            setMapper.updateById(s);
        }
    }

    /** 自动生成范本套编码 */
    private String autoCode(String bizType) {
        return "TPL_" + bizType + "_" + System.currentTimeMillis();
    }
}
