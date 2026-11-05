package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tpl.entity.EntTplSet;
import com.safetrans.tpl.entity.TplRevisionLog;
import com.safetrans.tpl.mapper.EntTplSetMapper;
import com.safetrans.tpl.mapper.TplRevisionLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 待修订记录服务：登记 + 查询 + 状态流转（保存到当前 / 另存为 / 稍后处理）。
 */
@Service
public class TplRevisionLogService {

    private final TplRevisionLogMapper revisionMapper;
    private final EntTplSetMapper entMapper;

    public TplRevisionLogService(TplRevisionLogMapper revisionMapper, EntTplSetMapper entMapper) {
        this.revisionMapper = revisionMapper;
        this.entMapper = entMapper;
    }

    /** 登记一条待修订（岗位改名/禁用等触发），自动关联当前应用范本 */
    public void mark(Long tenantId, String srcModule, String desc, Long linkedSetId) {
        Long linked = linkedSetId;
        if (linked == null) {
            // 自动关联当前应用范本（若存在）
            EntTplSet cur = entMapper.selectOne(new LambdaQueryWrapper<EntTplSet>()
                    .eq(EntTplSet::getTenantId, tenantId)
                    .eq(EntTplSet::getIsCurrent, 1));
            linked = cur != null ? cur.getId() : null;
        }
        TplRevisionLog log = new TplRevisionLog();
        log.setTenantId(tenantId);
        log.setSrcModule(srcModule);
        log.setDesc(desc);
        log.setStatus(0);
        log.setLinkedSetId(linked);
        revisionMapper.insert(log);
    }

    /** 当前租户待处理（status 0 或 3）的修订记录 */
    public List<TplRevisionLog> pending(Long tenantId) {
        return revisionMapper.selectList(new LambdaQueryWrapper<TplRevisionLog>()
                .eq(TplRevisionLog::getTenantId, tenantId)
                .in(TplRevisionLog::getStatus, 0, 3)
                .orderByDesc(TplRevisionLog::getCreatedAt));
    }

    /** 全部修订记录（含已处理），供列表页 */
    public List<TplRevisionLog> all(Long tenantId) {
        return revisionMapper.selectList(new LambdaQueryWrapper<TplRevisionLog>()
                .eq(TplRevisionLog::getTenantId, tenantId)
                .orderByDesc(TplRevisionLog::getCreatedAt));
    }

    /** 更新修订状态（1保存到当前/2另存为/3稍后） */
    public void updateStatus(Long id, int status) {
        TplRevisionLog log = revisionMapper.selectById(id);
        if (log == null) {
            throw new BizException("修订记录不存在");
        }
        Long tid = TenantContextHolder.tenantId();
        if (tid != null && !tid.equals(log.getTenantId())) {
            throw new BizException("无权操作该记录");
        }
        log.setStatus(status);
        revisionMapper.updateById(log);
    }
}
