package com.safetrans;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2 单元测试：V7 开户引导迁移的完整性。
 * 校验四张表都有主键、六阶段检查项字典覆盖阶段 0~5 且每项编码唯一。
 */
class OnboardingMetaTest {

    private String migration() throws Exception {
        return new String(new ClassPathResource("db/migration/V7__onboarding.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void allFourTablesShouldHavePrimaryKeyAndTenantIsolation() throws Exception {
        String sql = migration();
        for (String table : new String[]{"onboarding_checklist", "sys_onboarding",
                "onboarding_item", "sys_trial_feedback"}) {
            assertTrue(sql.contains("CREATE TABLE `" + table + "`"), "缺少表 " + table);
            int start = sql.indexOf("CREATE TABLE `" + table + "`");
            int end = sql.indexOf("ENGINE=InnoDB", start);
            assertTrue(end > start, table + " 缺少 ENGINE 声明");
            String ddl = sql.substring(start, end);
            assertTrue(ddl.contains("PRIMARY KEY"), table + " 缺少主键");
        }
        assertTrue(sql.contains("UNIQUE KEY `uk_tenant` (`tenant_id`)"),
                "sys_onboarding 必须每租户唯一");
        assertTrue(sql.contains("UNIQUE KEY `uk_tenant_item`"),
                "onboarding_item 必须（租户,检查项）唯一，避免重复勾选");
    }

    @Test
    void checklistShouldCoverAllSixStagesWithUniqueCodes() throws Exception {
        String sql = migration();
        // 检查项种子形如 (8001,0,'S0_CONTRACT','...')
        Matcher m = Pattern.compile("\\(\\s*(\\d{4}),(\\d),'([A-Z0-9_]+)'").matcher(sql);
        Set<String> codes = new HashSet<>();
        Set<Integer> stages = new HashSet<>();
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(codes.add(m.group(3)), "检查项编码重复：" + m.group(3));
            stages.add(Integer.parseInt(m.group(2)));
        }
        assertTrue(n >= 20, "六阶段检查项应不少于 20 项，实际 " + n);
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), stages, "必须覆盖需求 §十一 阶段 0~5");
        assertTrue(sql.contains("S4_CABINET"), "必须含归档就位自检（验收标准 2）");
        assertTrue(sql.contains("S5_RENEW"), "必须含年审续签（阶段 5）");
    }
}
