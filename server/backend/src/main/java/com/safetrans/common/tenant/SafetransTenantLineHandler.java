package com.safetrans.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * 租户行级隔离处理器。
 * 规则：
 *  - 全局无 tenant_id 的表（字典/菜单/范本套等）不注入租户条件；
 *  - 平台账号（服务商/超管）默认不注入（跨租户），业务表按需用 @InterceptorIgnore 或手动 tenant_id 过滤；
 *  - 普通企业账号注入 tenant_id = 当前租户。
 */
public class SafetransTenantLineHandler implements TenantLineHandler {

    /** 无 tenant_id 的全局表：不追加租户条件 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "org_dict",          // 12 系统职能字典（平台统一）
            "archive_catalog",   // 档案盒目录（平台统一）
            "tpl_set",           // 服务商全局范本套（tenant_id=0）
            "tpl_template",      // 套内范本文件
            "tpl_field_dict",    // 字段字典
            "sys_menu",          // 菜单
            "sys_role",          // 角色
            "sys_user_role",     // 用户-角色关联（用 user_id 定位，非租户维度）
            "sys_user",          // 用户账号：username 全局唯一，登录/鉴权需跨租户查询
            "sys_tenant"         // 租户主表本身：无 tenant_id 字段，非租户隔离对象
    );

    @Override
    public Expression getTenantId() {
        Long tid = TenantContextHolder.tenantId();
        return new LongValue(tid == null ? 0L : tid);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 去掉可能带反引号的表名
        String t = tableName.replace("`", "").toLowerCase();
        if (IGNORE_TABLES.contains(t)) {
            return true;
        }
        // 平台账号：未选目标企业时跨租户不注入；已通过 X-Target-Tenant 选定企业时按该企业隔离，
        // 保证服务商代企业生成台账时读到的范本套、岗位人名、印章、归档项都属于该企业。
        if (TenantContextHolder.isPlatform()) {
            Long curTenant = TenantContextHolder.tenantId();
            return curTenant == null || curTenant == 0L;
        }
        return false;
    }
}
