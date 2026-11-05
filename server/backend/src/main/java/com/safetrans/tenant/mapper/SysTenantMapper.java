package com.safetrans.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.safetrans.tenant.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
