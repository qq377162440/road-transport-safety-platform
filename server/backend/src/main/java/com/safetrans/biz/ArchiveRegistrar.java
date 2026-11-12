package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 归档登记器（铁律 6）：任何产生记录/台账的模块保存时必须调用，幂等。
 * 供通用业务引擎与专用业务闭环共用，B7 电子档案柜的全景/缺项提示直接消费这些归档项。
 */
@Component
public class ArchiveRegistrar {

    private final JdbcTemplate jdbc;

    public ArchiveRegistrar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void register(String moduleCode, String catalog, long bizId, String title) {
        Long tid = TenantContextHolder.tenantId();
        register(moduleCode, catalog, bizId, title, tid == null ? 0L : tid, 0);
    }

    /**
     * 显式指定租户归档（E2）：开户引导中服务商代新企业录入人车档案时，
     * 当前登录上下文是平台账号（tenant_id=0），必须指定目标租户，
     * 否则归档项会错误地挂到平台名下，破坏铁律 6 的租户隔离。
     */
    public void register(String moduleCode, String catalog, long bizId, String title, long tenantId) {
        register(moduleCode, catalog, bizId, title, tenantId, 0);
    }

    /**
     * 带来源归档（用户 2026-11-12 需求三）：source 标识归档来源
     * 1=档案柜手动上传 2=B3 流程产出 3=服务商强制生成。
     */
    public void register(String moduleCode, String catalog, long bizId, String title, int source) {
        Long tid = TenantContextHolder.tenantId();
        register(moduleCode, catalog, bizId, title, tid == null ? 0L : tid, source);
    }

    /** 带来源 + 显式 catalog_id 归档（盒二=50002）：直接落库 catalog_id，不走 archive_catalog 树解析 */
    public void register(String moduleCode, long catalogId, long bizId, String title, int source) {
        Long tid = TenantContextHolder.tenantId();
        doRegister(moduleCode, catalogId, bizId, title, tid == null ? 0L : tid, source);
    }

    /**
     * 带来源 + 显式 catalog_id 归档（用户 2026-11-12 盒二修复）：
     * 档案柜盒视图按 {@code BOX_CATALOG_BASE + boxNo}（=50000+盒号）过滤 archive_item.catalog_id，
     * 而 archive_catalog 树的 code（如 GENERAL_BOX2=4017）是另一套 id，二者不可混用。
     * 因此盒二登记必须传 50002，才能被盒二柜视图（listUploadItems）正确捞回并展示来源徽标。
     */
    public void register(String moduleCode, long catalogId, long bizId, String title, long tenantId, int source) {
        doRegister(moduleCode, catalogId, bizId, title, tenantId, source);
    }

    public void register(String moduleCode, String catalog, long bizId, String title, long tenantId, int source) {
        if (catalog == null || catalog.isBlank()) return;
        Long catalogId = resolveCatalogId(catalog);
        doRegister(moduleCode, catalogId, bizId, title, tenantId, source);
    }

    private Long resolveCatalogId(String catalog) {
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", Long.class, catalog);
        if (ids.isEmpty()) {
            ids = jdbc.queryForList(
                    "SELECT `id` FROM `archive_catalog` WHERE `code` LIKE ? AND `level`=1 LIMIT 1",
                    Long.class, "%_" + catalog);
        }
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    /**
     * 核心幂等登记：已存在同 (biz_module,biz_id,tenant) 则不重复插入，仅补登 source（仅当原=0）。
     * catalogId 直接落库，调用方需保证与档案柜盒视图的过滤口径一致（盒二=50002）。
     */
    private void doRegister(String moduleCode, Long catalogId, long bizId, String title, long tenantId, int source) {
        Long tid = tenantId;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `archive_item` WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                Integer.class, moduleCode, bizId, tid);
        if (cnt != null && cnt > 0) {
            // 已存在：补登 source（幂等，不重复插入，但来源信息可后置补全）
            if (source > 0) jdbc.update(
                    "UPDATE `archive_item` SET `source`=? WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0 AND `source`=0",
                    source, moduleCode, bizId, tid);
            return;
        }

        Long uid = TenantContextHolder.userId();
        jdbc.update("INSERT INTO `archive_item` " +
                        "(`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`source`,`biz_module`,`biz_id`,`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,2,1,?,?,?,?,?,?,0,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid, catalogId, source, moduleCode, bizId,
                title == null ? moduleCode : title, LocalDate.now(), LocalDate.now().getYear(),
                LocalDateTime.now(), uid == null ? 0L : uid);
    }
}
