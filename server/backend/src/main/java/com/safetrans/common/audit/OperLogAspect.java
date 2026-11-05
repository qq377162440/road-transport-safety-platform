package com.safetrans.common.audit;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safetrans.common.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 全量操作日志切面（A2）。新增/更新/删除类接口自动留痕，日志失败不影响主流程。
 */
@Slf4j
@Aspect
@Component
public class OperLogAspect {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public OperLogAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        Object result = pjp.proceed();
        try {
            Long tid = TenantContextHolder.tenantId();
            Long uid = TenantContextHolder.userId();
            String ip = clientIp();
            String after = safeJson(pjp.getArgs());
            long targetId = extractTargetId(pjp.getArgs());
            jdbc.update("INSERT INTO `sys_oper_log` " +
                            "(`id`,`tenant_id`,`user_id`,`module`,`action`,`target_id`,`before_data`,`after_data`,`ip`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid == null ? 0L : tid, uid == null ? 0L : uid,
                    operLog.module(), operLog.action(), targetId, null, after, ip,
                    LocalDateTime.now(), uid == null ? 0L : uid);
        } catch (Exception e) {
            log.warn("操作日志写入失败（不影响业务）: {}", e.getMessage());
        }
        return result;
    }

    private long extractTargetId(Object[] args) {
        for (Object a : args) {
            if (a instanceof Long l) return l;
            if (a instanceof String s && s.matches("\\d{10,}")) return Long.parseLong(s);
        }
        return 0L;
    }

    private String safeJson(Object[] args) {
        try {
            StringBuilder sb = new StringBuilder("[");
            for (Object a : args) {
                if (a == null || a instanceof HttpServletRequest) continue;
                String s = (a instanceof String) ? (String) a : mapper.writeValueAsString(a);
                if (sb.length() > 1) sb.append(",");
                sb.append("\"").append(s.replace("\"", "'").replace("\\", "")).append("\"");
                if (sb.length() > 1800) break;
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "";
            HttpServletRequest req = attrs.getRequest();
            String xf = req.getHeader("X-Forwarded-For");
            if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception e) {
            return "";
        }
    }
}
