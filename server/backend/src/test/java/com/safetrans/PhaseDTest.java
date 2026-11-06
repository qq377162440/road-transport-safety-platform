package com.safetrans;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D 阶段（三期 v1.2）单元测试：迁移隔离、表数量、字典种子与模块元数据。
 *
 * <p>最关键的一条：V8/V9 绝不能重复创建 V1~V7 已建过的表，
 * 否则生产库 Flyway 会直接失败；也不能改动 V1~V7 的脚本内容（校验和由发布清单记录）。
 */
class PhaseDTest {

    private static final Pattern CREATE = Pattern.compile("CREATE TABLE `(\\w+)`");

    private static String read(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Set<String> tables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CREATE.matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Set<String> publishedTables() throws Exception {
        Set<String> all = new HashSet<>();
        for (String v : List.of("V1__init.sql", "V2__seed.sql", "V3__phase1.sql", "V4__phase2.sql",
                "V5__archive_catalog.sql", "V6__archive_dict_seed.sql", "V7__onboarding.sql")) {
            all.addAll(tables(read("db/migration/" + v)));
        }
        return all;
    }

    @Test
    void v8ShouldOnlyCreatePhase3TablesFromSchema() throws Exception {
        String v8 = read("db/migration/V8__phase3.sql");
        Set<String> created = tables(v8);
        assertEquals(10, created.size(), "V8 应建 D1/D2/D3 的 10 张表");
        assertTrue(created.containsAll(Set.of("monitor_ledger", "org_outsource", "budget_plan",
                "budget_expense", "budget_category", "risk_point_dict", "risk_list",
                "risk_card", "risk_manual", "org_post_content")));
        for (String t : created) {
            assertFalse(publishedTables().contains(t), "V8 不得重复建表：" + t);
        }
    }

    @Test
    void v9ShouldOnlyCreateD4D5Tables() throws Exception {
        String v9 = read("db/migration/V9__phase3_ext.sql");
        Set<String> created = tables(v9);
        assertEquals(6, created.size(), "V9 应建 D4/D5 的 6 张表");
        assertTrue(created.containsAll(Set.of("danger_special_ledger", "danger_waybill",
                "sys_module_config", "tenant_module", "sso_config", "service_billing")));
        for (String t : created) {
            assertFalse(publishedTables().contains(t), "V9 不得重复建表：" + t);
        }
    }

    @Test
    void v8AndV9ShouldNotOverlap() throws Exception {
        Set<String> a = tables(read("db/migration/V8__phase3.sql"));
        Set<String> b = tables(read("db/migration/V9__phase3_ext.sql"));
        assertTrue(Collections.disjoint(a, b), "V8 与 V9 不应出现同名表");
    }

    @Test
    void v9SeedsShouldMatchDesignSpec() throws Exception {
        String v9 = read("db/migration/V9__phase3_ext.sql");
        // 财资〔2022〕136 号 11 类费用（17 号设计 §3.2）
        assertEquals(11, countRows(v9, "INSERT INTO `budget_category`"), "安全费用类别应为 11 类");
        // 通用风险点库：五类 × 四色
        assertTrue(countRows(v9, "INSERT INTO `risk_point_dict`") >= 15, "风险点库应覆盖五类场景");
        assertTrue(v9.contains("'红'") && v9.contains("'橙'") && v9.contains("'黄'") && v9.contains("'蓝'"),
                "风险点库应覆盖四色等级");
        // 模块字典与 modules.json 对齐
        assertEquals(18, countRows(v9, "INSERT INTO `sys_module_config`"), "平台模块字典应为 18 个");
        // D4 六项专项台账归档子项 + D3 风险辨识静态项
        assertTrue(v9.contains("危险货物电子运单") && v9.contains("岗位风险告知卡")
                && v9.contains("无重大事故隐患的声明"), "档案盒二级目录应补充 D 阶段成果物");
        // 种子 DELETE 使用固定 id 区间，保证迁移可重复执行不产生重复数据
        assertTrue(v9.contains("DELETE FROM `budget_category` WHERE `id` BETWEEN 6001 AND 6011"));
    }

    private static int countRows(String sql, String insertPrefix) {
        int at = sql.indexOf(insertPrefix);
        if (at < 0) return 0;
        int end = sql.indexOf(";", at);
        String block = sql.substring(at, end);
        return (int) block.lines().filter(l -> l.trim().startsWith("(")).count();
    }

    @Test
    void dModulesShouldHaveCorrectCatalogAndPhase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> mods = mapper.readValue(
                new ClassPathResource("biz/modules.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});
        Map<String, String> catalogs = new HashMap<>();
        Map<String, String> phases = new HashMap<>();
        for (Map<String, Object> m : mods) {
            catalogs.put(String.valueOf(m.get("code")), String.valueOf(m.get("catalog")));
            phases.put(String.valueOf(m.get("code")), String.valueOf(m.get("phase")));
        }
        assertEquals("BOX6", catalogs.get("monitor"), "D1 动态监控归盒六");
        assertEquals("BOX5", catalogs.get("budget"), "D2 安全生产费用归盒五");
        assertEquals("BOX8", catalogs.get("risk"), "D3 风险辨识归盒八（危货运行时改用盒九）");
        assertEquals("BOX6", catalogs.get("dangerspecial"), "D4 危货专项台账归盒六");
        assertEquals("", catalogs.get("platform"), "D5 平台配置不产生企业档案盒资料");
        assertEquals("D4", phases.get("dangerspecial"));
        assertEquals("D5", phases.get("platform"));
    }
}
