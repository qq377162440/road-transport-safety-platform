package com.safetrans.phase3;

import com.safetrans.biz.ArchiveRegistrar;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * D5 平台侧能力（超管/服务商运营台）。
 *
 * <p>四块：企业开通总览、功能模块配置与按租户授权、SSO 配置登记、
 * 增值服务按账期计费。平台账号跨租户，企业只能读 /my-modules 查询自己的授权。
 *
 * <p>诚实边界：SSO 只做**配置登记与脱敏存储**，未接入真实 IdP 的登录跳转；
 * 模块开关对「三期 D 模块」的前端菜单生效（A/B/C 基础模块始终可用），
 * 试运行期（企业尚无开通记录）默认全量开放，避免存量租户上线即失能。
 * 平台配置类操作不产生企业档案盒资料，故 catalog 为空、不登记归档项。
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController extends PhaseDSupport {

    public PlatformController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                               PdfService pdfService, StorageService storage) {
        super(jdbc, registrar, pdfService, storage);
    }

    // ==================================================================
    // 功能模块字典与配置
    // ==================================================================
    @GetMapping("/modules")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> modules() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `id`,`module_code`,`module_name`,`phase`,`group_name`,`is_base`,`monthly_fee`," +
                        "`sort_no`,`enabled` FROM `sys_module_config` WHERE `deleted`=0 ORDER BY `sort_no`");
        long valueAdded = rows.stream().filter(r -> "0".equals(String.valueOf(r.get("is_base")))).count();
        return Result.ok(Map.of("total", rows.size(), "valueAdded", valueAdded, "list", rows));
    }

    @PutMapping("/modules/{code}")
    @RequireRole({"admin"})
    @OperLog(module = "platform", action = "config_module")
    public Result<Map<String, Object>> configModule(@PathVariable String code,
                                                    @RequestBody Map<String, Object> b) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `id` FROM `sys_module_config` WHERE `module_code`=? AND `deleted`=0", code);
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex("模块 " + code);
        long id = ((Number) rows.get(0).get("id")).longValue();
        Integer enabled = b.containsKey("enabled") ? integer(b, "enabled", 1) : null;
        BigDecimal fee = b.containsKey("monthly_fee") ? dec(b, "monthly_fee", BigDecimal.ZERO) : null;
        Integer isBase = b.containsKey("is_base") ? integer(b, "is_base", 1) : null;
        if (enabled != null) jdbc.update("UPDATE `sys_module_config` SET `enabled`=? WHERE `id`=?", enabled, id);
        if (fee != null) jdbc.update("UPDATE `sys_module_config` SET `monthly_fee`=? WHERE `id`=?", fee, id);
        if (isBase != null) jdbc.update("UPDATE `sys_module_config` SET `is_base`=? WHERE `id`=?", isBase, id);
        return Result.ok(Map.of("moduleCode", code, "enabled", enabled == null ? "unchanged" : enabled,
                "monthlyFee", fee == null ? "unchanged" : fee));
    }

    // ==================================================================
    // 企业开通总览与按租户模块授权
    // ==================================================================
    @GetMapping("/tenants")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> tenants() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.`id`,t.`name`,t.`credit_code`,t.`status`,t.`expire_time`,i.`biz_type`, " +
                        "(SELECT COUNT(*) FROM `tenant_module` m WHERE m.`tenant_id`=t.`id` " +
                        " AND m.`enabled`=1 AND m.`deleted`=0) AS module_count " +
                        "FROM `sys_tenant` t LEFT JOIN `tenant_info` i ON i.`tenant_id`=t.`id` AND i.`deleted`=0 " +
                        "WHERE t.`deleted`=0 ORDER BY t.`id`");
        return Result.ok(Map.of("total", rows.size(), "list", rows));
    }

    @GetMapping("/tenant/{tenantId}/modules")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> tenantModules(@PathVariable long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.`module_code`,c.`module_name`,c.`phase`,m.`enabled`,m.`expire_at`,m.`opened_at` " +
                        "FROM `tenant_module` m LEFT JOIN `sys_module_config` c ON c.`module_code`=m.`module_code` " +
                        "WHERE m.`tenant_id`=? AND m.`deleted`=0 ORDER BY c.`sort_no`", tenantId);
        Integer all = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `sys_module_config` WHERE `deleted`=0", Integer.class);
        return Result.ok(Map.of("tenantId", tenantId, "configured", rows.size(),
                "platformModules", all == null ? 0 : all, "list", rows));
    }

    @PostMapping("/tenant/{tenantId}/modules")
    @RequireRole({"admin", "provider"})
    @OperLog(module = "platform", action = "grant_modules")
    public Result<Map<String, Object>> grantModules(@PathVariable long tenantId,
                                                    @RequestBody Map<String, Object> b) {
        requirePlatform();
        List<Object> items = list(b, "modules");
        if (items.isEmpty()) throw ErrorCode.FIELD_REQUIRED.ex("modules（模块授权列表）");
        int opened = 0, closed = 0;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) raw;
            String code = str(item, "code");
            if (code == null || code.isBlank()) throw ErrorCode.FIELD_REQUIRED.ex("modules[].code");
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `sys_module_config` WHERE `module_code`=? AND `deleted`=0",
                    Integer.class, code);
            if (exists == null || exists == 0) throw ErrorCode.PARAM_INVALID.ex("未知模块：" + code);
            int enabled = integer(item, "enabled", 1);
            String expire = str(item, "expire_at", null);
            List<Map<String, Object>> old = jdbc.queryForList(
                    "SELECT `id` FROM `tenant_module` WHERE `tenant_id`=? AND `module_code`=? AND `deleted`=0",
                    tenantId, code);
            if (old.isEmpty()) {
                Map<String, Object> r = base();
                r.put("tenant_id", tenantId);
                r.put("module_code", code);
                r.put("enabled", enabled);
                r.put("expire_at", expire);
                r.put("opened_by", uid());
                r.put("opened_at", LocalDateTime.now());
                insert("tenant_module", r);
            } else {
                jdbc.update("UPDATE `tenant_module` SET `enabled`=?, `expire_at`=? WHERE `id`=?",
                        enabled, expire, old.get(0).get("id"));
            }
            if (enabled == 1) opened++;
            else closed++;
        }
        return Result.ok(Map.of("tenantId", tenantId, "opened", opened, "closed", closed));
    }

    /**
     * 服务商 / 超管代企业生成台账时可选的目标企业清单。
     * 前端选中后用 X-Target-Tenant 请求头带上，AuthInterceptor 会把租户上下文切到该企业，
     * 使成品使用目标企业的范本套、岗位人名与公司章，并归档到目标企业的电子档案柜。
     */
    @GetMapping("/target-tenants")
    @RequireRole({"provider", "admin"})
    public Result<List<Map<String, Object>>> targetTenants() {
        return Result.ok(jdbc.queryForList(
                "SELECT `id`,`name` FROM `sys_tenant` WHERE `deleted`=0 AND `status`=1 ORDER BY `id`"));
    }

    /** 企业侧查询自己的可用模块（基础模块恒可用；增值模块需授权且未过期）。 */
    @GetMapping("/my-modules")
    public Result<Map<String, Object>> myModules() {
        List<Map<String, Object>> all = jdbc.queryForList(
                "SELECT `module_code`,`is_base`,`enabled` FROM `sys_module_config` " +
                        "WHERE `deleted`=0 AND `enabled`=1 ORDER BY `sort_no`");
        Integer configured = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `tenant_module` WHERE `tenant_id`=? AND `deleted`=0", Integer.class, tid());
        boolean trialAllOpen = configured == null || configured == 0;
        Map<String, Object> opened = new HashMap<>();
        if (!trialAllOpen) {
            for (Map<String, Object> m : jdbc.queryForList(
                    "SELECT `module_code`,`enabled`,`expire_at` FROM `tenant_module` " +
                            "WHERE `tenant_id`=? AND `deleted`=0", tid())) {
                opened.put(String.valueOf(m.get("module_code")), m);
            }
        }
        List<String> enabled = new ArrayList<>(), disabled = new ArrayList<>();
        List<String> base = new ArrayList<>(), valueAdded = new ArrayList<>();
        for (Map<String, Object> m : all) {
            String code = String.valueOf(m.get("module_code"));
            boolean isBase = "1".equals(String.valueOf(m.get("is_base")));
            (isBase ? base : valueAdded).add(code);
            boolean ok;
            if (trialAllOpen || isBase) {
                ok = true;
            } else {
                Object rec = opened.get(code);
                if (rec == null) {
                    ok = false;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) rec;
                    Object exp = mm.get("expire_at");
                    ok = "1".equals(String.valueOf(mm.get("enabled")))
                            && (exp == null || String.valueOf(exp).compareTo(LocalDate.now().toString()) >= 0);
                }
            }
            (ok ? enabled : disabled).add(code);
        }
        return Result.ok(Map.of("tenantId", tid(), "trialAllOpen", trialAllOpen,
                "enabled", enabled, "disabled", disabled, "base", base, "valueAdded", valueAdded));
    }

    // ==================================================================
    // SSO 配置登记（密钥脱敏，不回显明文）
    // ==================================================================
    @GetMapping("/tenant/{tenantId}/sso")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> sso(@PathVariable long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `id`,`tenant_id`,`protocol`,`idp_url`,`client_id`,`enabled`,`remark` " +
                        "FROM `sso_config` WHERE `tenant_id`=? AND `deleted`=0", tenantId);
        Map<String, Object> out = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
        out.put("clientSecretSet", rows.isEmpty() ? false
                : hasSecret(tenantId));
        out.put("note", "仅登记配置，未接入真实 IdP 登录跳转");
        return Result.ok(out);
    }

    private boolean hasSecret(long tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `sso_config` WHERE `tenant_id`=? AND `deleted`=0 " +
                        "AND `client_secret` IS NOT NULL AND `client_secret`<>''", Integer.class, tenantId);
        return n != null && n > 0;
    }

    @PutMapping("/tenant/{tenantId}/sso")
    @RequireRole({"admin", "provider"})
    @OperLog(module = "platform", action = "config_sso")
    public Result<Map<String, Object>> saveSso(@PathVariable long tenantId,
                                              @RequestBody Map<String, Object> b) {
        String protocol = str(b, "protocol", "OIDC");
        if (!Set.of("OIDC", "SAML2", "CAS").contains(protocol)) {
            throw ErrorCode.PARAM_INVALID.ex("protocol 必须为 OIDC/SAML2/CAS");
        }
        String idp = str(b, "idp_url", "");
        String clientId = str(b, "client_id", "");
        String secret = str(b, "client_secret", null);
        int enabled = integer(b, "enabled", 0);
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT `id` FROM `sso_config` WHERE `tenant_id`=? AND `deleted`=0", tenantId);
        long id;
        if (old.isEmpty()) {
            Map<String, Object> r = base();
            r.put("tenant_id", tenantId);
            r.put("protocol", protocol);
            r.put("idp_url", idp);
            r.put("client_id", clientId);
            r.put("client_secret", secret);
            r.put("enabled", enabled);
            r.put("remark", "配置登记，客户端密钥接口不回显");
            id = insert("sso_config", r);
        } else {
            id = ((Number) old.get(0).get("id")).longValue();
            if (secret == null) {
                jdbc.update("UPDATE `sso_config` SET `protocol`=?,`idp_url`=?,`client_id`=?,`enabled`=? WHERE `id`=?",
                        protocol, idp, clientId, enabled, id);
            } else {
                jdbc.update("UPDATE `sso_config` SET `protocol`=?,`idp_url`=?,`client_id`=?," +
                                "`client_secret`=?,`enabled`=? WHERE `id`=?",
                        protocol, idp, clientId, secret, enabled, id);
            }
        }
        return Result.ok(Map.of("id", id, "tenantId", tenantId, "protocol", protocol,
                "enabled", enabled, "clientSecretSet", secret != null || hasSecret(tenantId)));
    }

    // ==================================================================
    // 增值服务计费（按账期，幂等）
    // ==================================================================
    @PostMapping("/billing")
    @RequireRole({"admin", "provider"})
    @OperLog(module = "platform", action = "generate_bills")
    public Result<Map<String, Object>> generateBills(@RequestBody Map<String, Object> b) {
        String period = str(b, "period", LocalDate.now().toString().substring(0, 7));
        long onlyTenant = lng(b, "tenantId", 0);
        String sql = "SELECT m.`tenant_id`,m.`module_code`,c.`monthly_fee` FROM `tenant_module` m " +
                "JOIN `sys_module_config` c ON c.`module_code`=m.`module_code` AND c.`deleted`=0 AND c.`enabled`=1 " +
                "WHERE m.`deleted`=0 AND m.`enabled`=1 AND c.`is_base`=0 AND c.`monthly_fee`>0 ";
        List<Map<String, Object>> targets = onlyTenant > 0
                ? jdbc.queryForList(sql + "AND m.`tenant_id`=?", onlyTenant)
                : jdbc.queryForList(sql);
        int generated = 0, skipped = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> t : targets) {
            long tenantId = ((Number) t.get("tenant_id")).longValue();
            String code = String.valueOf(t.get("module_code"));
            BigDecimal fee = (BigDecimal) t.get("monthly_fee");
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `service_billing` WHERE `tenant_id`=? AND `module_code`=? " +
                            "AND `period`=? AND `deleted`=0", Integer.class, tenantId, code, period);
            if (dup != null && dup > 0) {
                skipped++;
                continue;
            }
            Map<String, Object> r = base();
            r.put("tenant_id", tenantId);
            r.put("module_code", code);
            r.put("period", period);
            r.put("amount", fee);
            r.put("status", "unpaid");
            r.put("bill_no", "BILL-" + period.replace("-", "") + "-" + code.toUpperCase());
            insert("service_billing", r);
            total = total.add(fee);
            generated++;
        }
        return Result.ok(Map.of("period", period, "generated", generated, "skipped", skipped,
                "amount", total));
    }

    @GetMapping("/billing")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> bills(@RequestParam(required = false) String period) {
        String sql = "SELECT b.*,t.`name` AS tenant_name FROM `service_billing` b " +
                "LEFT JOIN `sys_tenant` t ON t.`id`=b.`tenant_id` WHERE b.`deleted`=0 ";
        List<Map<String, Object>> rows = period == null || period.isBlank()
                ? jdbc.queryForList(sql + "ORDER BY b.`period` DESC,b.`id` DESC LIMIT 300")
                : jdbc.queryForList(sql + "AND b.`period`=? ORDER BY b.`id` DESC", period);
        BigDecimal unpaid = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal a = (BigDecimal) r.getOrDefault("amount", BigDecimal.ZERO);
            if ("paid".equals(String.valueOf(r.get("status")))) paid = paid.add(a);
            else if ("unpaid".equals(String.valueOf(r.get("status")))) unpaid = unpaid.add(a);
        }
        return Result.ok(Map.of("total", rows.size(), "unpaidAmount", unpaid, "paidAmount", paid, "list", rows));
    }

    @PutMapping("/billing/{id}/settle")
    @RequireRole({"admin", "provider"})
    @OperLog(module = "platform", action = "settle_bill")
    public Result<Map<String, Object>> settle(@PathVariable long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `service_billing` WHERE `id`=? AND `deleted`=0", Integer.class, id);
        if (n == null || n == 0) throw ErrorCode.BIZ_NOT_FOUND.ex("账单 #" + id);
        jdbc.update("UPDATE `service_billing` SET `status`='paid',`paid_at`=? WHERE `id`=?",
                LocalDateTime.now(), id);
        return Result.ok(Map.of("id", id, "status", "paid"));
    }

    @GetMapping("/summary")
    @RequireRole({"provider", "admin"})
    public Result<Map<String, Object>> summary() {
        Long tenants = jdbc.queryForObject("SELECT COUNT(*) FROM `sys_tenant` WHERE `deleted`=0", Long.class);
        Long grants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `tenant_module` WHERE `deleted`=0 AND `enabled`=1", Long.class);
        BigDecimal unpaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(`amount`),0) FROM `service_billing` WHERE `deleted`=0 AND `status`='unpaid'",
                BigDecimal.class);
        BigDecimal paid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(`amount`),0) FROM `service_billing` WHERE `deleted`=0 AND `status`='paid'",
                BigDecimal.class);
        return Result.ok(Map.of("tenants", tenants == null ? 0 : tenants,
                "moduleGrants", grants == null ? 0 : grants,
                "unpaidAmount", unpaid == null ? BigDecimal.ZERO : unpaid,
                "paidAmount", paid == null ? BigDecimal.ZERO : paid,
                "ssoIntegration", "配置登记（未接入 IdP 登录跳转）"));
    }
}
