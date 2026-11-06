package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.PageResult;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * E3 种子客户试运行反馈闭环。
 *
 * <p>流程：企业/服务商提交 → 平台受理（processing）→ 解决（resolved）或驳回（rejected）
 * → 汇总统计（解决率 / 按模块与类型分布 / 未闭环清单）→ 试运行报告。
 *
 * <p>数据隔离：平台账号（provider/admin）看全部，企业账号只看本租户；
 * 处理接口仅平台账号可调用。反馈属于运营数据，不登记归档项（非业务成果物）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Set<String> TYPES = Set.of("bug", "suggestion", "praise", "question");
    private static final Set<String> STATUSES = Set.of("pending", "processing", "resolved", "rejected");

    private final JdbcTemplate jdbc;

    public FeedbackController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 提交反馈（自动携带当前登录者身份与租户） */
    @PostMapping
    @OperLog(module = "feedback", action = "create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String content = str(body.get("content"));
        if (content.isBlank()) throw ErrorCode.FIELD_REQUIRED.ex("反馈内容");
        if (content.length() > 1000) throw ErrorCode.PARAM_INVALID.ex("反馈内容不能超过 1000 字");
        String type = str(body.get("feedback_type"));
        if (type.isBlank()) type = "suggestion";
        if (!TYPES.contains(type)) throw ErrorCode.PARAM_INVALID.ex("feedback_type 非法");
        String module = str(body.get("module"));
        int severity = intVal(body.get("severity"), 2);
        if (severity < 1 || severity > 3) severity = 2;

        long id = IdUtil.getSnowflakeNextId();
        Long tid = TenantContextHolder.tenantId();
        Long uid = TenantContextHolder.userId();
        jdbc.update("INSERT INTO `sys_trial_feedback` " +
                        "(`id`,`tenant_id`,`user_id`,`user_type`,`module`,`feedback_type`,`severity`,`content`,`contact`,`status`,`created_at`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,'pending',?)",
                id, tid == null ? 0L : tid, uid == null ? 0L : uid, TenantContextHolder.userType(),
                module, type, severity, content, str(body.get("contact")), LocalDateTime.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "pending");
        out.put("feedbackType", type);
        out.put("createdAt", LocalDateTime.now().toString());
        return Result.ok(out);
    }

    /** 反馈列表：平台看全部，企业只看本租户；支持状态/类型/模块过滤与分页 */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String feedbackType,
            @RequestParam(required = false) String module) {
        long pn = Math.max(pageNo, 1), ps = Math.min(Math.max(pageSize, 1), 200);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE `deleted`=0 ");
        if (!TenantContextHolder.isPlatform()) {
            Long tid = TenantContextHolder.tenantId();
            where.append(" AND `tenant_id`=? ");
            args.add(tid == null ? 0L : tid);
        }
        if (status != null && !status.isBlank()) {
            if (!STATUSES.contains(status)) throw ErrorCode.PARAM_INVALID.ex("status 非法");
            where.append(" AND `status`=? ");
            args.add(status);
        }
        if (feedbackType != null && !feedbackType.isBlank()) {
            where.append(" AND `feedback_type`=? ");
            args.add(feedbackType);
        }
        if (module != null && !module.isBlank()) {
            where.append(" AND `module`=? ");
            args.add(module);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `sys_trial_feedback`" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(ps);
        pageArgs.add((pn - 1) * ps);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `sys_trial_feedback`" + where + " ORDER BY `id` DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Result.ok(PageResult.of(total == null ? 0 : total, pn, ps, rows));
    }

    /** 平台处理反馈：pending → processing → resolved / rejected，记录处理人与说明 */
    @PutMapping("/{id}")
    @OperLog(module = "feedback", action = "handle")
    public Result<Map<String, Object>> handle(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!TenantContextHolder.isPlatform()) throw ErrorCode.FORBIDDEN.ex("仅平台/服务商可处理反馈");
        String status = str(body.get("status"));
        if (!Set.of("processing", "resolved", "rejected").contains(status)) {
            throw ErrorCode.PARAM_INVALID.ex("status 必须为 processing / resolved / rejected");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `sys_trial_feedback` WHERE `id`=? AND `deleted`=0", id);
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex("反馈记录");
        String current = String.valueOf(rows.get(0).get("status"));
        if ("resolved".equals(current) || "rejected".equals(current)) {
            throw ErrorCode.BIZ_STATE_INVALID.ex("已闭环的反馈不可重复处理");
        }
        Long uid = TenantContextHolder.userId();
        jdbc.update("UPDATE `sys_trial_feedback` SET `status`=?,`handler_id`=?,`handle_note`=?,`handled_at`=? WHERE `id`=?",
                status, uid == null ? 0L : uid, str(body.get("handle_note")), LocalDateTime.now(), id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("from", current);
        out.put("to", status);
        return Result.ok(out);
    }

    /** 汇总统计：供试运行报告与看板消费 */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        List<Object> args = new ArrayList<>();
        String scope = " WHERE `deleted`=0 ";
        if (!TenantContextHolder.isPlatform()) {
            Long tid = TenantContextHolder.tenantId();
            scope += " AND `tenant_id`=? ";
            args.add(tid == null ? 0L : tid);
        }
        Object[] a = args.toArray();
        int total = count("SELECT COUNT(*) FROM `sys_trial_feedback`" + scope, a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("pending", "processing", "resolved", "rejected")) {
            byStatus.put(s, count("SELECT COUNT(*) FROM `sys_trial_feedback`" + scope + " AND `status`=?",
                    append(a, s)));
        }
        out.put("byStatus", byStatus);
        out.put("resolveRate", total == 0 ? 0 :
                Math.round((byStatus.get("resolved") * 100.0 / total) * 10) / 10.0);
        out.put("closed", byStatus.get("resolved") + byStatus.get("rejected"));
        out.put("open", byStatus.get("pending") + byStatus.get("processing"));

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (String t : List.of("bug", "suggestion", "praise", "question")) {
            byType.put(t, count("SELECT COUNT(*) FROM `sys_trial_feedback`" + scope + " AND `feedback_type`=?",
                    append(a, t)));
        }
        out.put("byType", byType);

        List<Map<String, Object>> byModule = jdbc.queryForList(
                "SELECT COALESCE(NULLIF(`module`,''),'未分类') AS module, COUNT(*) AS cnt " +
                        "FROM `sys_trial_feedback`" + scope + " GROUP BY `module` ORDER BY cnt DESC");
        out.put("byModule", byModule);
        out.put("generatedAt", LocalDateTime.now().toString());
        return Result.ok(out);
    }

    /** 未闭环清单（试运行报告需要逐项列出） */
    @GetMapping("/open")
    public Result<List<Map<String, Object>>> open() {
        if (!TenantContextHolder.isPlatform()) throw ErrorCode.FORBIDDEN.ex("仅平台可查看全量未闭环清单");
        return Result.ok(jdbc.queryForList(
                "SELECT `id`,`tenant_id`,`module`,`feedback_type`,`severity`,`content`,`status`,`created_at` " +
                        "FROM `sys_trial_feedback` WHERE `deleted`=0 AND `status` IN ('pending','processing') " +
                        "ORDER BY `severity`,`id`"));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Object[] append(Object[] base, Object extra) {
        Object[] out = Arrays.copyOf(base, base.length + 1);
        out[base.length] = extra;
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
