package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.PageResult;
import com.safetrans.common.web.Result;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 元数据驱动的通用业务引擎（A4/A5 + B/C 阶段共用）。
 *
 * <p>106 张表逐字建在 Flyway V3/V4，字段元数据来自 database/schema.sql，
 * 由 tools/gen_biz_meta.py 生成到 classpath:biz/modules.json。
 * 所有 SQL 的表名/列名均来自白名单并加反引号，杜绝 SQL 注入与保留字问题。
 *
 * <p>租户隔离与 MyBatis 插件保持一致：企业账号注入 tenant_id，平台账号跨租户；
 * 无 tenant_id 字段的全局表不注入。
 *
 * <p>铁律 6：任何产生记录的业务模块保存时自动登记 archive_item（幂等）。
 */
@RestController
@RequestMapping("/api/biz")
public class BizController {

    private final JdbcTemplate jdbc;
    private final ArchiveRegistrar registrar;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<ModuleMeta> modules = new ArrayList<>();
    private final Map<String, TableMeta> tableIndex = new HashMap<>();
    private final Map<String, ModuleMeta> moduleByTable = new HashMap<>();

    public BizController(JdbcTemplate jdbc, ArchiveRegistrar registrar) {
        this.jdbc = jdbc;
        this.registrar = registrar;
    }

    @PostConstruct
    public void loadMeta() throws Exception {
        ClassPathResource res = new ClassPathResource("biz/modules.json");
        List<ModuleMeta> list = mapper.readValue(res.getInputStream(), new TypeReference<List<ModuleMeta>>() {});
        for (ModuleMeta m : list) {
            modules.add(m);
            for (TableMeta t : m.getTables()) {
                tableIndex.put(t.getName(), t);
                moduleByTable.put(t.getName(), m);
            }
        }
    }

    // ------------------------------------------------------------------
    // 元数据 / 菜单（A5：菜单由配置驱动）
    // ------------------------------------------------------------------

    @GetMapping("/meta")
    public Result<List<ModuleMeta>> meta() {
        return Result.ok(modules);
    }

    /** 供专用闭环控制器读取模块清单（菜单/归档归属统计） */
    public List<ModuleMeta> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public boolean hasTable(String table) {
        return tableIndex.containsKey(table);
    }

    @GetMapping("/{table}/fields")
    public Result<TableMeta> fields(@PathVariable String table) {
        return Result.ok(require(table));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @GetMapping("/{table}")
    public Result<PageResult<Map<String, Object>>> list(
            @PathVariable String table,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam Map<String, String> params) {
        TableMeta t = require(table);
        long pn = Math.max(pageNo, 1), ps = Math.min(Math.max(pageSize, 1), 200);

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (t.hasField("deleted")) where.append(" AND `deleted` = 0 ");
        Long tid = TenantContextHolder.tenantId();
        if (t.hasField("tenant_id") && tid != null && !TenantContextHolder.isPlatform()) {
            where.append(" AND `tenant_id` = ? ");
            args.add(tid);
        }
        // 精确字段过滤（仅白名单字段生效）
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey();
            if (k.equals("pageNo") || k.equals("pageSize") || k.equals("keyword")) continue;
            if (!t.hasField(k) || e.getValue() == null || e.getValue().isEmpty()) continue;
            where.append(" AND `").append(k).append("` = ? ");
            args.add(e.getValue());
        }
        // 关键字模糊：取前 3 个文本字段
        if (keyword != null && !keyword.isBlank()) {
            List<String> textCols = t.textFields();
            if (!textCols.isEmpty()) {
                where.append(" AND (");
                for (int i = 0; i < textCols.size(); i++) {
                    if (i > 0) where.append(" OR ");
                    where.append("`").append(textCols.get(i)).append("` LIKE ? ");
                    args.add("%" + keyword + "%");
                }
                where.append(") ");
            }
        }

        String tbl = q(table);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tbl + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(ps);
        pageArgs.add((pn - 1) * ps);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + tbl + where + " ORDER BY `id` DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return Result.ok(PageResult.of(total == null ? 0 : total, pn, ps, rows));
    }

    @GetMapping("/{table}/{id}")
    public Result<Map<String, Object>> detail(@PathVariable String table, @PathVariable long id) {
        TableMeta t = require(table);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + q(table) + " WHERE `id` = ?" + tenantCond(t), argsOf(t, id));
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex();
        return Result.ok(rows.get(0));
    }

    // ------------------------------------------------------------------
    // 写入（自动记审计日志 + 自动归档登记）
    // ------------------------------------------------------------------

    @PostMapping("/{table}")
    @OperLog(module = "biz", action = "create")
    public Result<Map<String, Object>> create(@PathVariable String table, @RequestBody Map<String, Object> body) {
        TableMeta t = require(table);
        Map<String, Object> row = filter(t, body, true);
        long id = IdUtil.getSnowflakeNextId();
        row.put("id", id);
        Long tid = TenantContextHolder.tenantId();
        if (t.hasField("tenant_id")) row.put("tenant_id", tid == null ? 0L : tid);
        if (t.hasField("created_by")) row.put("created_by", TenantContextHolder.userId() == null ? 0L : TenantContextHolder.userId());
        if (t.hasField("created_at")) row.put("created_at", LocalDateTime.now());
        if (t.hasField("deleted")) row.put("deleted", 0);
        if (t.hasField("updated_at")) row.put("updated_at", LocalDateTime.now());

        String cols = row.keySet().stream().map(c -> q(c)).reduce((a, b) -> a + "," + b).orElse("");
        String marks = String.join(",", Collections.nCopies(row.size(), "?"));
        jdbc.update("INSERT INTO " + q(table) + " (" + cols + ") VALUES (" + marks + ")", row.values().toArray());
        registerArchive(table, id, titleOf(t, body));
        return Result.ok(row);
    }

    @PutMapping("/{table}/{id}")
    @OperLog(module = "biz", action = "update")
    public Result<Map<String, Object>> update(@PathVariable String table, @PathVariable long id,
                                              @RequestBody Map<String, Object> body) {
        TableMeta t = require(table);
        Map<String, Object> row = filter(t, body, false);
        if (t.hasField("updated_at")) row.put("updated_at", LocalDateTime.now());
        if (row.isEmpty()) throw ErrorCode.PARAM_INVALID.ex("无可更新字段");

        String sets = row.keySet().stream().map(c -> q(c) + " = ?").reduce((a, b) -> a + "," + b).orElse("");
        List<Object> args = new ArrayList<>(row.values());
        args.add(id);
        args.addAll(tenantArgs(t));
        int n = jdbc.update("UPDATE " + q(table) + " SET " + sets + " WHERE `id` = ?" + tenantCond(t), args.toArray());
        if (n == 0) throw ErrorCode.BIZ_NOT_FOUND.ex();
        registerArchive(table, id, titleOf(t, body));
        row.put("id", id);
        return Result.ok(row);
    }

    @DeleteMapping("/{table}/{id}")
    @OperLog(module = "biz", action = "delete")
    public Result<Void> remove(@PathVariable String table, @PathVariable long id) {
        TableMeta t = require(table);
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(tenantArgs(t));
        String sql = t.hasField("deleted")
                ? "UPDATE " + q(table) + " SET `deleted` = 1 WHERE `id` = ?" + tenantCond(t)
                : "DELETE FROM " + q(table) + " WHERE `id` = ?" + tenantCond(t);
        int n = jdbc.update(sql, args.toArray());
        if (n == 0) throw ErrorCode.BIZ_NOT_FOUND.ex();
        // 同步撤下归档项
        jdbc.update("UPDATE `archive_item` SET `deleted` = 1, `status` = 0 WHERE `biz_module` = ? AND `biz_id` = ?",
                moduleByTable.get(table).getCode(), id);
        return Result.ok();
    }

    // ------------------------------------------------------------------
    // 归档归属（铁律 6）—— 幂等登记，供 B7 电子档案柜全景/缺项提示消费
    // ------------------------------------------------------------------

    private void registerArchive(String table, Long bizId, String title) {
        ModuleMeta m = moduleByTable.get(table);
        if (m == null) return;
        registrar.register(m.getCode(), m.getCatalog(), bizId, title == null ? m.getTitle() : title);
    }

    private String titleOf(TableMeta t, Map<String, Object> body) {
        for (String k : List.of("title", "name", "plan_name", "person_name", "plate_no", "meeting_no")) {
            Object v = body.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return t.getTitle();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private TableMeta require(String table) {
        TableMeta t = tableIndex.get(table);
        if (t == null) throw ErrorCode.TABLE_ILLEGAL.ex(table);
        return t;
    }

    /** 只保留元数据白名单内的可写字段；必填校验；布尔/空串归一 */
    private Map<String, Object> filter(TableMeta t, Map<String, Object> body, boolean creating) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (FieldMeta f : t.getFields()) {
            if ("hidden".equals(f.getControl())) continue;
            if (creating && f.isRequired() && !body.containsKey(f.getName())) {
                // 有 DEFAULT 的必填字段允许缺省（required 已在生成器按 NOT NULL 且无 DEFAULT 判定）
                throw ErrorCode.FIELD_REQUIRED.ex(f.getLabel());
            }
            if (!body.containsKey(f.getName())) continue;
            Object v = body.get(f.getName());
            if (v instanceof Boolean b) v = b ? 1 : 0;
            if (v instanceof String s && s.isBlank()) v = null;
            if (v != null) row.put(f.getName(), v);
        }
        return row;
    }

    private String tenantCond(TableMeta t) {
        Long tid = TenantContextHolder.tenantId();
        if (t.hasField("tenant_id") && tid != null && !TenantContextHolder.isPlatform()) {
            return " AND `tenant_id` = ?";
        }
        return "";
    }

    private List<Object> tenantArgs(TableMeta t) {
        Long tid = TenantContextHolder.tenantId();
        if (t.hasField("tenant_id") && tid != null && !TenantContextHolder.isPlatform()) {
            return List.of(tid);
        }
        return List.of();
    }

    private Object[] argsOf(TableMeta t, Object... head) {
        List<Object> args = new ArrayList<>(Arrays.asList(head));
        args.addAll(tenantArgs(t));
        return args.toArray();
    }

    private static String q(String ident) {
        return "`" + ident.replace("`", "") + "`";
    }

    // ------------------------------------------------------------------
    // 元数据结构（Jackson 反序列化 classpath:biz/modules.json）
    // ------------------------------------------------------------------

    @Data
    public static class ModuleMeta {
        private String code;
        private String title;
        private String phase;
        private String group;
        private String main;
        private String catalog;
        private List<TableMeta> tables = new ArrayList<>();
    }

    @Data
    public static class TableMeta {
        private String name;
        private String title;
        private List<FieldMeta> fields = new ArrayList<>();

        public boolean hasField(String n) {
            return fields.stream().anyMatch(f -> f.getName().equals(n));
        }

        /** 模糊搜索用的文本字段（最多 3 个，排除系统字段） */
        public List<String> textFields() {
            List<String> out = new ArrayList<>();
            for (FieldMeta f : fields) {
                if (f.getType().startsWith("VARCHAR") || f.getType().startsWith("CHAR")) {
                    if (!f.getName().endsWith("_id") && !"hidden".equals(f.getControl())) out.add(f.getName());
                    if (out.size() >= 3) break;
                }
            }
            return out;
        }
    }

    @Data
    public static class FieldMeta {
        private String name;
        private String type;
        private String label;
        private boolean required;
        private String control;
        private String javaType;
    }
}
