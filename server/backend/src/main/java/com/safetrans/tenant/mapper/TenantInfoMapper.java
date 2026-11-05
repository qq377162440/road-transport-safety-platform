package com.safetrans.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.safetrans.tenant.entity.TenantInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantInfoMapper extends BaseMapper<TenantInfo> {
}
