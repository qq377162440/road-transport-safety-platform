package com.safetrans;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A3 单元测试：业务元数据完整性（84 表 / 13 模块），保证通用引擎白名单可信。
 */
class BizMetaTest {

    @Test
    void modulesJsonShouldBeLoadableAndComplete() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> modules = mapper.readValue(
                new ClassPathResource("biz/modules.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(13, modules.size(), "模块数量应为 13");

        int tables = 0;
        for (Map<String, Object> m : modules) {
            assertNotNull(m.get("code"));
            assertNotNull(m.get("phase"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ts = (List<Map<String, Object>>) m.get("tables");
            assertFalse(ts.isEmpty(), "模块 " + m.get("code") + " 不应无表");
            for (Map<String, Object> t : ts) {
                tables++;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fs = (List<Map<String, Object>>) t.get("fields");
                assertFalse(fs.isEmpty(), "表 " + t.get("name") + " 不应无字段");
                boolean hasId = fs.stream().anyMatch(f -> "id".equals(f.get("name")));
                assertTrue(hasId, "表 " + t.get("name") + " 必须有主键 id");
            }
        }
        assertEquals(83, tables, "暴露通用 CRUD 的表应为 83（sys_role_menu 为无主键关联表，仅建表不暴露）");
    }

    @Test
    void allPhasesShouldBeCovered() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> modules = mapper.readValue(
                new ClassPathResource("biz/modules.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});
        List<String> phases = modules.stream().map(m -> String.valueOf(m.get("phase"))).distinct().toList();
        assertTrue(phases.contains("A2"));
        assertTrue(phases.contains("B1"));
        assertTrue(phases.contains("B8"));
        assertTrue(phases.contains("C6"));
    }
}
