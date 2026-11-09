package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.mapper.EntTplSetMapper;
import org.springframework.stereotype.Service;

/**
 * 当前应用范本套作用域。
 *
 * <p>企业（tenant_id&gt;0）在台账范本管理中选定的「当前应用范本套」决定：
 * <ul>
 *   <li>台账范本管理页看到哪一套范本内容；</li>
 *   <li>电子档案柜点击「生成」时用哪一套范本渲染并归档。</li>
 * </ul>
 * 范本内容落在 tpl_template：tenant_id=企业、group_id=ent_tpl_set.id。
 * 若企业尚未创建应用套（或平台/服务商账号），返回 null，保持全局默认套的旧行为。
 */
@Service
public class TplSetScope {

    /** 全局默认服务商范本套 id */
    public static final long GLOBAL_SET_ID = 4001L;

    private final EntTplSetMapper entMapper;

    public TplSetScope(EntTplSetMapper entMapper) {
        this.entMapper = entMapper;
    }

    /** 当前企业 id（平台/服务商账号返回 null） */
    public Long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? null : t;
    }

    /** 当前应用范本套实体 */
    public EntTplSet current() {
        Long t = tid();
        if (t == null) return null;
        return entMapper.selectOne(new LambdaQueryWrapper<EntTplSet>()
                .eq(EntTplSet::getTenantId, t)
                .eq(EntTplSet::getIsCurrent, 1)
                .last("limit 1"));
    }

    /** 当前应用范本套的内容作用域 group_id；无则 null（走全局默认套） */
    public Long currentGroupId() {
        EntTplSet cur = current();
        return cur == null ? null : cur.getId();
    }
}
