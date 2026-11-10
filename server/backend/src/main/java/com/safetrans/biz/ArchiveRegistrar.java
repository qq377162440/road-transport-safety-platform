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
        register(moduleCode, catalog, bizId, title, tid == null ? 0L : tid);
    }

    /**
     * 显式指定租户归档（E2）：开户引导中服务商代新企业录入人车档案时，
     * 当前登录上下文是平台账号（tenant_id=0），必须指定目标租户，
     * 否则归档项会错误地挂到平台名下，破坏铁律 6 的租户隔离。
     */
    public void register(String moduleCode, String catalog, long bizId, String title, long tenantId) {
        if (catalog == null || catalog.isBlank()) return;
        Long tid = tenantId;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `archive_item` WHERE `biz_module`=? AND `biz_id`=? AND `tenant_id`=? AND `deleted`=0",
                Integer.class, moduleCode, bizId, tid);
        if (cnt != null && cnt > 0) return;

        Long catalogId = 0L;
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `archive_catalog` WHERE `code`=? AND `level`=1 LIMIT 1", Long.class, catalog);
        if (ids.isEmpty()) {
            ids = jdbc.queryForList(
                    "SELECT `id` FROM `archive_catalog` WHERE `code` LIKE ? AND `level`=1 LIMIT 1",
                    Long.class, "%_" + catalog);
        }
        if (!ids.isEmpty()) catalogId = ids.get(0);

        Long uid = TenantContextHolder.userId();
        jdbc.update("INSERT INTO `archive_item` " +
                        "(`id`,`tenant_id`,`catalog_id`,`data_type`,`gen_mode`,`biz_module`,`biz_id`,`title`,`occur_date`,`archive_year`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,2,1,?,?,?,?,?,0,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid, catalogId, moduleCode, bizId,
                title == null ? moduleCode : title, LocalDate.now(), LocalDate.now().getYear(),
                LocalDateTime.now(), uid == null ? 0L : uid);
    }
}
