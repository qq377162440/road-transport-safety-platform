package com.safetrans.archive.service;

import com.safetrans.common.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盒十一「从业人员一人一档 + 一车一档」档案浏览服务（用户 2026-11-11 需求 ②）。
 *
 * <p>与人员基础信息 / 车辆档案同源：
 * <ul>
 *   <li>从业人员（person_type=employee：驾驶员 / 押运员等）逐人列出其一人一档 14 项
 *       （person_archive_item，带来源字典 person_archive_dict 的名称）与证照（person_doc）；</li>
 *   <li>车辆逐辆列出其一车一档 13 项（vehicle_archive_item，项名与 VehicleFlowController.ITEMS 同源）
 *       与车辆证照（vehicle_doc）；</li>
 *   <li>每项带 file_url（MinIO 对象名），前端按名点击即可 /api/archive/file?path= 下载预览。</li>
 * </ul>
 */
@Service
public class CrewArchiveService {

    /** 一车一档 13 项（与 VehicleFlowController.ITEMS / MockArchiveService.VEHICLE_ITEMS 同源） */
    private static final String[][] VEHICLE_ITEMS = {
            {"C01", "车辆基础信息登记表"}, {"C02", "道路运输证"}, {"C03", "行驶证"},
            {"C04", "检验报告"}, {"C05", "机动车登记证（绿本）"}, {"C06", "随车设备清单"},
            {"C07", "保险（交强险/商业险）"}, {"C08", "车辆技术档案"},
            {"C09", "智能视频动态监控/GPS 安装证明"}, {"C10", "车辆等级评定登记表"},
            {"C11", "车辆维护和维修登记表"}, {"C12", "车辆主要部件更换登记表"},
            {"C13", "车辆行驶里程登记表"},
    };

    private final JdbcTemplate jdbc;

    public CrewArchiveService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    // ------------------------------------------------------------------
    // 从业人员一人一档总览
    // ------------------------------------------------------------------
    public Map<String, Object> employeeOverview() {
        long t = tid();
        List<Map<String, Object>> rows = new ArrayList<>();
        // 从业人员：驾驶员 / 押运员 / 装卸管理员等一线岗位
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT p.`id`, p.`name`, p.`telephone`, p.`id_card`, p.`person_type`, " +
                "GROUP_CONCAT(DISTINCT o.`post_name` ORDER BY o.`id` SEPARATOR '、') AS `post_name` " +
                "FROM `person` p LEFT JOIN `org_member` m " +
                "  ON m.`user_id`=p.`id` AND m.`tenant_id`=p.`tenant_id` AND m.`deleted`=0 " +
                "LEFT JOIN `org_post` o ON o.`id`=m.`post_id` AND o.`deleted`=0 AND o.`enabled`=1 " +
                "WHERE p.`tenant_id`=? AND p.`deleted`=0 AND p.`person_type`='employee' " +
                "GROUP BY p.`id`, p.`name`, p.`telephone`, p.`id_card`, p.`person_type` ORDER BY p.`id`", t)) {
            long pid = ((Number) p.get("id")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("personId", pid);
            row.put("name", s(p.get("name")));
            row.put("postName", s(p.get("post_name")));
            row.put("telephone", s(p.get("telephone")));
            row.put("idCard", s(p.get("id_card")));
            Map<String, Object> c = personItemsOf(pid, t);
            row.putAll(c);
            row.put("docs", personDocsOf(pid, t));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employees", rows);
        out.put("total", rows.size());
        return out;
    }

    /** 某从业人员的一人一档明细（person_archive_item + 来源字典名称） */
    private Map<String, Object> personItemsOf(long personId, long t) {
        List<Map<String, Object>> items = new ArrayList<>();
        int required = 0, done = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT d.`item_code`, d.`item_name`, d.`source_type` AS `dict_source`, " +
                "i.`status`, i.`file_url`, i.`expire_date`, i.`source_type` AS `actual_source` " +
                "FROM `person_archive_dict` d LEFT JOIN `person_archive_item` i " +
                "ON i.`item_code`=d.`item_code` AND i.`person_id`=? AND i.`tenant_id`=? AND i.`deleted`=0 " +
                "WHERE d.`deleted`=0 ORDER BY d.`sort_no`", personId, t)) {
            int status = "1".equals(s(r.get("status"))) ? 1 : "2".equals(s(r.get("status"))) ? 2 : 0;
            boolean optional = "1".equals(s(r.get("is_optional")));
            if (!optional) {
                required++;
                if (status == 1) done++;
            }
            String exp = s(r.get("expire_date"));
            if (exp.length() > 10) exp = exp.substring(0, 10);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("code", s(r.get("item_code")));
            it.put("name", s(r.get("item_name")));
            it.put("status", status);
            it.put("fileUrl", s(r.get("file_url")));
            it.put("expireDate", exp);
            items.add(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("required", required);
        out.put("done", done);
        out.put("percent", required == 0 ? 0 : (int) Math.round(done * 100.0 / required));
        return out;
    }

    /** 取从业人员姓名（合并档案册封面用） */
    public String personName(long personId) {
        try {
            return s(jdbc.queryForObject(
                    "SELECT `name` FROM `person` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                    String.class, personId, tid()));
        } catch (Exception e) {
            return "从业人员" + personId;
        }
    }

    /** 取车辆号牌（合并档案册封面用） */
    public String vehiclePlate(long vehicleId) {
        try {
            return s(jdbc.queryForObject(
                    "SELECT `plate_no` FROM `vehicle` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0",
                    String.class, vehicleId, tid()));
        } catch (Exception e) {
            return "车辆" + vehicleId;
        }
    }

    /**
     * 某从业人员一人一档的文件清单（归档项 + 证照，仅含已有 file_url 的），
     * 用于合并档案册 / 整盒打包下载。返回 [{name: 序号_名称.pdf, url: MinIO 对象名}, ...]。
     */
    public List<Map<String, String>> personFileList(long personId) {
        long t = tid();
        List<Map<String, Object>> items = (List<Map<String, Object>>) personItemsOf(personId, t).get("items");
        List<Map<String, Object>> docs = personDocsOf(personId, t);
        return fileList(items, docs);
    }

    /** 某车辆一车一档的文件清单（归档项 + 证照），同上 */
    public List<Map<String, String>> vehicleFileList(long vehicleId) {
        long t = tid();
        Map<String, String> vName = new LinkedHashMap<>();
        for (String[] it : VEHICLE_ITEMS) vName.put(it[0], it[1]);
        List<Map<String, Object>> items = vehicleItemsOf(vehicleId, t, vName);
        List<Map<String, Object>> docs = vehicleDocsOf(vehicleId, t);
        return fileList(items, docs);
    }

    /** 把「带 fileUrl 的归档项 / 证照」整理成有序文件名清单 */
    private List<Map<String, String>> fileList(List<Map<String, Object>> items, List<Map<String, Object>> docs) {
        List<Map<String, String>> out = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> it : items) {
            String url = s(it.get("fileUrl"));
            if (url.isEmpty()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", String.format("%02d_%s.pdf", idx++, s(it.get("name")).replaceAll("[\\\\/:*?\"<>|]", "_")));
            m.put("url", url);
            out.add(m);
        }
        for (Map<String, Object> d : docs) {
            String url = s(d.get("fileUrl"));
            if (url.isEmpty()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", String.format("%02d_%s.pdf", idx++, s(d.get("name")).replaceAll("[\\\\/:*?\"<>|]", "_")));
            m.put("url", url);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> personDocsOf(long personId, long t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT `doc_code`,`doc_name`,`issue_date`,`expire_date`,`file_url`,`status` FROM `person_doc` " +
                "WHERE `person_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `doc_code`", personId, t)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", s(row.get("doc_name")));
            d.put("issue", s(row.get("issue_date")));
            d.put("expire", s(row.get("expire_date")));
            d.put("fileUrl", s(row.get("file_url")));
            d.put("status", "1".equals(s(row.get("status"))) ? 1 : 0);
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 一车一档总览
    // ------------------------------------------------------------------
    public Map<String, Object> vehicleOverview() {
        long t = tid();
        Map<String, String> vName = new LinkedHashMap<>();
        for (String[] it : VEHICLE_ITEMS) vName.put(it[0], it[1]);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> v : jdbc.queryForList(
                "SELECT `id`,`plate_no`,`biz_type`,`vehicle_type`,`brand_model`,`owner` " +
                "FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t)) {
            long vid = ((Number) v.get("id")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicleId", vid);
            row.put("plateNo", s(v.get("plate_no")));
            row.put("bizType", s(v.get("biz_type")));
            row.put("vehicleType", s(v.get("vehicle_type")));
            row.put("brandModel", s(v.get("brand_model")));
            row.put("owner", s(v.get("owner")));
            row.put("items", vehicleItemsOf(vid, t, vName));
            row.put("docs", vehicleDocsOf(vid, t));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vehicles", rows);
        out.put("total", rows.size());
        return out;
    }

    /** 某车辆的一车一档 13 项（vehicle_archive_item，项名取自同源常量） */
    private List<Map<String, Object>> vehicleItemsOf(long vehicleId, long t, Map<String, String> vName) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `item_code`,`status`,`file_url`,`expire_date` FROM `vehicle_archive_item` " +
                "WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `item_code`", vehicleId, t)) {
            String code = s(r.get("item_code"));
            int status = "1".equals(s(r.get("status"))) ? 1 : "2".equals(s(r.get("status"))) ? 2 : 0;
            String exp = s(r.get("expire_date"));
            if (exp.length() > 10) exp = exp.substring(0, 10);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("code", code);
            it.put("name", vName.getOrDefault(code, code));
            it.put("status", status);
            it.put("fileUrl", s(r.get("file_url")));
            it.put("expireDate", exp);
            out.add(it);
        }
        return out;
    }

    private List<Map<String, Object>> vehicleDocsOf(long vehicleId, long t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT `doc_code`,`doc_name`,`issue_date`,`expire_date`,`file_url`,`status` FROM `vehicle_doc` " +
                "WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `doc_code`", vehicleId, t)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", s(row.get("doc_name")));
            d.put("issue", s(row.get("issue_date")));
            d.put("expire", s(row.get("expire_date")));
            d.put("fileUrl", s(row.get("file_url")));
            d.put("status", "1".equals(s(row.get("status"))) ? 1 : 0);
            out.add(d);
        }
        return out;
    }
}
