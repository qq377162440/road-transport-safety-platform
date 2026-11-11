package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 法律法规清单模块：展示目前制度引用的法规列表。
 *
 * <p>tenant_id=0 为平台内置全局法规（所有企业可见），企业可另存自己补充的条目；
 * refs 为 JSON 数组，指向引用该法规的平台制度/台账范本/档案盒归档项，
 * 详情抽屉里逐条展示「哪些制度在用这部法规」。
 */
@RestController
@RequestMapping("/api/flow/law")
public class LawFlowController {

    private final JdbcTemplate jdbc;

    public LawFlowController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static final String SELECT_SQL =
            "SELECT `id`,`law_name`,`law_no`,`law_level`,`category`,`version_label`,`effective_date`," +
            "`status`,`apply_scope`,`summary`,`refs`,`sort_no` FROM `law_catalog` " +
            "WHERE `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?) ";

    /** 法规清单总览：分类统计 + 状态统计 + 列表（支持关键字/状态/分类筛选） */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String category) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (`law_name` LIKE ? OR `summary` LIKE ? OR `refs` LIKE ?) ");
            String k = "%" + keyword.trim() + "%";
            args.add(k); args.add(k); args.add(k);
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            sql.append(" AND `status`=? ");
            args.add(status);
        }
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            sql.append(" AND `category`=? ");
            args.add(category);
        }
        sql.append(" ORDER BY `sort_no`,`id` ");
        List<Map<String, Object>> raw = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        int active = 0, superseded = 0, repealed = 0, refCount = 0;
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            List<Map<String, String>> refs = parseRefs(s(r.get("refs")));
            row.put("refs", refs);
            row.put("refCount", refs.size());
            refCount += refs.size();
            list.add(row);
            byCategory.merge(s(r.get("category")), 1, Integer::sum);
            switch (s(r.get("status"))) {
                case "superseded" -> superseded++;
                case "repealed" -> repealed++;
                default -> active++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("active", active);
        out.put("superseded", superseded);
        out.put("repealed", repealed);
        out.put("refCount", refCount);
        out.put("categories", byCategory.keySet());
        return Result.ok(out);
    }

    /** 单部法规详情（含引用制度） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_SQL + " AND `id`=? ", tid(), id);
        if (rows.isEmpty()) throw new BizException("法规不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("refs", parseRefs(s(rows.get(0).get("refs"))));
        return Result.ok(row);
    }

    /** 新增 / 编辑法规（企业补充条目 tenant_id=当前租户；内置条目编辑时另存为企业版本） */
    @PostMapping("/save")
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> b) {
        long id = 0;
        try { id = Long.parseLong(String.valueOf(b.getOrDefault("id", "0"))); } catch (Exception ignore) { }
        String name = s(b.get("lawName"));
        if (name.isBlank()) throw new BizException("法规名称必填");
        List<String> refsName = new ArrayList<>();
        Object refsObj = b.get("refs");
        if (refsObj instanceof List<?> ls) {
            for (Object o : ls) refsName.add(s(o));
        } else if (refsObj != null && !s(refsObj).isBlank()) {
            for (String x : s(refsObj).split("[,，、;；\\n]")) if (!x.isBlank()) refsName.add(x.trim());
        }
        StringBuilder refsJson = new StringBuilder("[");
        for (int i = 0; i < refsName.size(); i++) {
            if (i > 0) refsJson.append(",");
            refsJson.append("{\"type\":\"doc\",\"name\":\"")
                    .append(refsName.get(i).replace("\"", "\\\"")).append("\"}");
        }
        refsJson.append("]");
        String st = s(b.getOrDefault("status", "active"));
        if (!List.of("active", "superseded", "repealed").contains(st)) st = "active";
        String scope = "danger".equals(b.get("applyScope")) ? "danger" : "all";
        if (id > 0) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `law_catalog` WHERE `id`=? AND `deleted`=0 AND (`tenant_id`=0 OR `tenant_id`=?)",
                    Integer.class, id, tid());
            if (n == null || n == 0) throw new BizException("法规不存在");
            jdbc.update("UPDATE `law_catalog` SET `law_name`=?,`law_no`=?,`law_level`=?,`category`=?," +
                            "`version_label`=?,`effective_date`=?,`status`=?,`apply_scope`=?,`summary`=?,`refs`=?,`updated_at`=NOW() " +
                            "WHERE `id`=?",
                    name, s(b.get("lawNo")), s(b.getOrDefault("lawLevel", "法律")), s(b.getOrDefault("category", "综合安全")),
                    s(b.get("versionLabel")), s(b.get("effectiveDate")), st, scope, s(b.get("summary")),
                    refsJson.toString(), id);
        } else {
            id = IdUtil.getSnowflakeNextId();
            jdbc.update("INSERT INTO `law_catalog` (`id`,`tenant_id`,`law_name`,`law_no`,`law_level`,`category`," +
                            "`version_label`,`effective_date`,`status`,`apply_scope`,`summary`,`refs`,`sort_no`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?," +
                            "(SELECT COALESCE(MAX(`sort_no`),0)+1 FROM (SELECT * FROM `law_catalog`) x),NOW(),?,0)",
                    id, tid(), name, s(b.get("lawNo")), s(b.getOrDefault("lawLevel", "法律")),
                    s(b.getOrDefault("category", "综合安全")), s(b.get("versionLabel")), s(b.get("effectiveDate")),
                    st, scope, s(b.get("summary")), refsJson.toString(), uid());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return Result.ok(out);
    }

    /** 删除（仅企业自有条目；内置条目 tenant_id=0 不允许删除） */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@PathVariable long id) {
        int n = jdbc.update("UPDATE `law_catalog` SET `deleted`=1 WHERE `id`=? AND `tenant_id`=? AND `deleted`=0", id, tid());
        if (n == 0) throw new BizException("内置法规不允许删除，可将其状态改为「已废止」");
        return Result.ok(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseRefs(String refs) {
        List<Map<String, String>> out = new ArrayList<>();
        if (refs == null || refs.isBlank()) return out;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Object> arr = om.readValue(refs, List.class);
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, String> r = new LinkedHashMap<>();
                    r.put("type", s(m.get("type")));
                    r.put("name", s(m.get("name")));
                    out.add(r);
                }
            }
        } catch (Exception ignore) { }
        return out;
    }
}
