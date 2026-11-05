package com.safetrans.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.entity.SysTenant;
import com.safetrans.tenant.entity.TenantInfo;
import com.safetrans.tenant.mapper.SysTenantMapper;
import com.safetrans.tenant.mapper.TenantInfoMapper;
import org.springframework.stereotype.Service;

/**
 * 企业信息服务：返回企业名称（PDF 页眉）+ 主体信息，支持更新。
 */
@Service
public class TenantService {

    private final SysTenantMapper tenantMapper;
    private final TenantInfoMapper infoMapper;

    public TenantService(SysTenantMapper tenantMapper, TenantInfoMapper infoMapper) {
        this.tenantMapper = tenantMapper;
        this.infoMapper = infoMapper;
    }

    /** 当前租户企业名称（PDF 页眉用） */
    public String currentTenantName() {
        Long tid = TenantContextHolder.tenantId();
        if (tid == null) {
            throw new BizException("未识别当前企业");
        }
        SysTenant t = tenantMapper.selectById(tid);
        return t != null ? t.getName() : "";
    }

    /** 当前租户主体信息（tenant_info 单条） */
    public TenantInfo current() {
        Long tid = TenantContextHolder.tenantId();
        if (tid == null) {
            throw new BizException("未识别当前企业");
        }
        TenantInfo info = infoMapper.selectOne(new LambdaQueryWrapper<TenantInfo>()
                .eq(TenantInfo::getTenantId, tid)
                .last("LIMIT 1"));
        return info;
    }

    /** 更新主体信息（只允许更新本租户，tenant_id 由上下文保证） */
    public TenantInfo update(TenantInfo patch) {
        Long tid = TenantContextHolder.tenantId();
        if (tid == null) {
            throw new BizException("未识别当前企业");
        }
        TenantInfo info = current();
        if (info == null) {
            // 不存在则新建
            info = new TenantInfo();
            info.setTenantId(tid);
        }
        // 只拷贝非空字段（简单实现：全量覆盖除 id/tenantId/审计外字段）
        copyNonNull(patch, info);
        info.setTenantId(tid);
        if (info.getId() == null) {
            infoMapper.insert(info);
        } else {
            infoMapper.updateById(info);
        }
        return info;
    }

    private void copyNonNull(TenantInfo from, TenantInfo to) {
        if (from.getCreditCode() != null) to.setCreditCode(from.getCreditCode());
        if (from.getBizType() != null) to.setBizType(from.getBizType());
        if (from.getBizTermType() != null) to.setBizTermType(from.getBizTermType());
        if (from.getBizTermEnd() != null) to.setBizTermEnd(from.getBizTermEnd());
        if (from.getLegalPerson() != null) to.setLegalPerson(from.getLegalPerson());
        if (from.getSafetyCharge() != null) to.setSafetyCharge(from.getSafetyCharge());
        if (from.getChargePhone() != null) to.setChargePhone(from.getChargePhone());
        if (from.getRegion() != null) to.setRegion(from.getRegion());
        if (from.getAddress() != null) to.setAddress(from.getAddress());
        if (from.getIndustry() != null) to.setIndustry(from.getIndustry());
        if (from.getInvestment() != null) to.setInvestment(from.getInvestment());
        if (from.getRegCapital() != null) to.setRegCapital(from.getRegCapital());
        if (from.getBizScope() != null) to.setBizScope(from.getBizScope());
        if (from.getVehicleScale() != null) to.setVehicleScale(from.getVehicleScale());
        if (from.getMainRoute() != null) to.setMainRoute(from.getMainRoute());
        if (from.getMainCargo() != null) to.setMainCargo(from.getMainCargo());
        if (from.getParkLocation() != null) to.setParkLocation(from.getParkLocation());
        if (from.getParkArea() != null) to.setParkArea(from.getParkArea());
        if (from.getSafetyStaff() != null) to.setSafetyStaff(from.getSafetyStaff());
        if (from.getEmployeeCount() != null) to.setEmployeeCount(from.getEmployeeCount());
        if (from.getWebsite() != null) to.setWebsite(from.getWebsite());
        if (from.getZipcode() != null) to.setZipcode(from.getZipcode());
        if (from.getMonitorOutsource() != null) to.setMonitorOutsource(from.getMonitorOutsource());
    }
}
