package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * B2 车辆基础信息 · 一车一档完整闭环（详细设计 10）。
 *
 * <p>与通用 CRUD 引擎的分工：/api/biz/vehicle* 负责主数据与各子表录入，本控制器负责
 * 13 项归档目录的状态流转、完整度实时计算、证件/保险/设备/等级/维修子表兜底判定、
 * 车挂关联、人车绑定、停运过滤、PDF 成品归档与三表联合到期预警。
 *
 * <p>铁律 6：每个归档项产生成品时按 "vehicle_{itemCode}" 登记一条 archive_item，
 * 幂等且有独立 biz_module，保证电子档案柜盒一能逐项看到。
 *
 * <p>相比 B1：齐全判定除状态表外还实时读取真实子表（同源数据自动点亮归档项），
 * 并补齐 B2 特有的车挂关联、人车绑定、停运过滤、危货差异校验。
 */
@RestController
@RequestMapping("/api/flow/vehicle")
public class VehicleFlowController {

    private final JdbcTemplate jdbc;
    private final ArchiveRegistrar registrar;
    private final PdfService pdfService;
    private final StorageService storage;

    public VehicleFlowController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                                 PdfService pdfService, StorageService storage) {
        this.jdbc = jdbc;
        this.registrar = registrar;
        this.pdfService = pdfService;
        this.storage = storage;
    }

    /** 13 项归档目录（设计 10 §2.1）：code, name, group, source, optional, checkType */
    // group: 静态生成区 auto / 证件上传区 upload / 动态台账区 ledger
    // checkType: 齐全判定兜底数据源（子表名），none=只认状态表
    private static final String[][] ITEMS = {
            {"C01", "车辆基础信息登记表", "静态生成区", "auto", "0", "vehicle_master"},
            {"C02", "道路运输证", "证件上传区", "upload", "0", "doc:ROAD_TRANSPORT,V02,V01"},
            {"C03", "行驶证", "证件上传区", "upload", "0", "doc:DRIVING_LIC,V03,V04"},
            {"C04", "检验报告（普货安技/危货各类）", "证件上传区", "upload", "0", "doc:INSPECT,V11,V12"},
            {"C05", "机动车登记证（绿本）", "证件上传区", "upload", "0", "doc:REGISTER,V05"},
            {"C06", "随车设备清单", "静态生成区", "auto", "0", "vehicle_device"},
            {"C07", "保险（交强险/商业险·危货含承运人责任险）", "证件上传区", "upload", "0", "vehicle_insurance"},
            {"C08", "车辆技术档案", "静态生成区", "auto", "0", "vehicle_repair"},
            {"C09", "智能视频动态监控/GPS 安装证明", "证件上传区", "upload", "0", "doc:GPS,V13"},
            {"C10", "车辆等级评定登记表", "静态生成区", "auto", "0", "vehicle_grade"},
            {"C11", "车辆维护和维修登记表", "动态台账区", "ledger", "0", "vehicle_repair"},
            {"C12", "车辆主要部件更换登记表", "动态台账区", "ledger", "0", "none"},
            {"C13", "车辆行驶里程登记表", "动态台账区", "ledger", "0", "none"},
    };

    private static final String[] GROUPS = {"静态生成区", "证件上传区", "动态台账区"};

    /** 模块间联动：车辆作为这些模块的下拉数据源（表/列名用于统计引用数） */
    private static final List<String[]> REFS = List.of(
            new String[]{"check", "一日三检任务", "check_task", "vehicle_id"},
            new String[]{"monitor", "动态监控违规台账", "monitor_violation", "vehicle_id"},
            new String[]{"dangerspecial", "危货电子运单", "danger_waybill", "vehicle_id"},
            new String[]{"accident", "事故与违法登记", "accident_record", "vehicle_id"},
            new String[]{"danger", "隐患排查记录", "danger_record", "vehicle_id"},
            new String[]{"vehicle", "人车绑定", "vehicle_driver", "vehicle_id"});

    private long tid() { Long t = TenantContextHolder.tenantId(); return t == null ? 0L : t; }
    private long uid() { Long u = TenantContextHolder.userId(); return u == null ? 0L : u; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private int count(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /** 主表必填字段齐全判定（C01） */
    private boolean masterComplete(Map<String, Object> v) {
        for (String k : new String[]{"plate_no", "vin", "brand_model", "owner", "reg_date"}) {
            if (s(v.get(k)).isBlank()) return false;
        }
        return true;
    }

    /** 某车 13 项归档状态（状态表 + 真实子表兜底）+ 完整度实时计算 */
    private Map<String, Object> compute(long vehicleId, Map<String, Object> vehicle) {
        String bizType = s(vehicle.get("biz_type"));      // freight/danger/passenger
        boolean danger = "danger".equalsIgnoreCase(bizType);

        // 状态表：逐项 upsert 的手工/生成/上传状态
        Map<String, Map<String, Object>> state = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `item_code`,`status`,`file_url`,`expire_date`,`source_type` FROM `vehicle_archive_item` " +
                        "WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0", vehicleId, tid())) {
            state.put(s(r.get("item_code")), r);
        }

        // 真实子表快照（同源兜底）
        Set<String> docCodes = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `doc_code`,`status` FROM `vehicle_doc` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0",
                vehicleId, tid())) {
            if ("1".equals(s(r.get("status")))) docCodes.add(s(r.get("doc_code")).toUpperCase());
        }
        int deviceCnt = count("SELECT COUNT(*) FROM `vehicle_device` WHERE (`vehicle_id`=? OR `vehicle_id` IS NULL) AND `tenant_id`=? AND `deleted`=0", vehicleId, tid());
        int repairCnt = count("SELECT COUNT(*) FROM `vehicle_repair` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0", vehicleId, tid());
        int gradeCnt = count("SELECT COUNT(*) FROM `vehicle_grade` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0", vehicleId, tid());
        Set<String> insTypes = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `insurance_type`,`expire_date`,`status` FROM `vehicle_insurance` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0",
                vehicleId, tid())) {
            String exp = s(r.get("expire_date"));
            boolean valid = "1".equals(s(r.get("status"))) || s(r.get("status")).isBlank();
            if (valid && !isExpired(exp)) insTypes.add(s(r.get("insurance_type")).toUpperCase());
        }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String g : GROUPS) groups.put(g, new ArrayList<>());

        int required = 0, done = 0, expiredCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        for (String[] def : ITEMS) {
            String code = def[0], name = def[1], group = def[2], checkType = def[5];
            boolean optional = "1".equals(def[4]);
            boolean counts = !optional;

            // 1) 状态表判定
            Map<String, Object> st = state.get(code);
            int status = 0;
            String fileUrl = "", exp = "", sourceType = def[3];
            if (st != null) {
                status = "1".equals(s(st.get("status"))) ? 1 : "2".equals(s(st.get("status"))) ? 2 : 0;
                fileUrl = s(st.get("file_url"));
                exp = s(st.get("expire_date"));
                if (!s(st.get("source_type")).isBlank()) sourceType = s(st.get("source_type"));
            }

            // 2) 真实子表兜底（状态表未齐全时）
            boolean fromReal = false;
            String realNote = "";
            if (status != 1) {
                if ("vehicle_master".equals(checkType)) {
                    if (masterComplete(vehicle)) { status = 1; fromReal = true; realNote = "主表字段齐全"; }
                } else if (checkType.startsWith("doc:")) {
                    String[] codes = checkType.substring(4).split(",");
                    for (String c : codes) if (docCodes.contains(c)) { status = 1; fromReal = true; break; }
                    if (fromReal) realNote = "证件子表已上传";
                } else if ("vehicle_device".equals(checkType)) {
                    if (deviceCnt > 0) { status = 1; fromReal = true; realNote = "随车设备清单已维护"; }
                } else if ("vehicle_repair".equals(checkType)) {
                    if (repairCnt > 0) { status = 1; fromReal = true; realNote = "维修保养记录已产生"; }
                } else if ("vehicle_grade".equals(checkType)) {
                    if (gradeCnt > 0) { status = 1; fromReal = true; realNote = "等级评定已登记"; }
                } else if ("vehicle_insurance".equals(checkType)) {
                    boolean hasCompulsory = insTypes.stream().anyMatch(x -> x.contains("交强") || x.contains("JQX") || x.contains("强制"));
                    boolean hasCarrier = insTypes.stream().anyMatch(x -> x.contains("承运人") || x.contains("CARRIER"));
                    if (hasCompulsory && (!danger || hasCarrier)) {
                        status = 1; fromReal = true;
                        realNote = danger ? "交强险 + 承运人责任险齐全" : "交强险齐全";
                    } else if (danger && hasCompulsory && !hasCarrier) {
                        realNote = "危货还缺承运人责任险";
                    }
                }
            }

            // 3) 有效期过期判定（状态表带有效期时优先）
            boolean expired = false;
            Integer daysLeft = null;
            if (exp.length() >= 10) {
                try {
                    daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.substring(0, 10)));
                } catch (Exception ignore) { }
            }
            if (daysLeft != null && daysLeft < 0) { status = 2; expired = true; }

            // 危货差异提示（C04 各类检验报告可多份；C07 强制承运人责任险）
            if (danger && "C04".equals(code) && status != 1) realNote = "危货需各类检验报告（可多份）";
            if (danger && "C07".equals(code)) {
                // 无论是否齐全都给出校验口径：齐全显示结果，未齐全显示危货强制要求
                realNote = status == 1 ? "交强险 + 承运人责任险齐全" : "危货须交强险 + 承运人责任险";
            }

            if (counts) {
                required++;
                if (status == 1) done++;
                else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", code); m.put("itemName", name);
                    m.put("status", status); m.put("expired", expired);
                    missing.add(m);
                    if (expired) expiredCount++;
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemCode", code);
            item.put("itemName", name);
            item.put("group", group);
            item.put("sourceType", sourceType);
            item.put("optional", optional);
            item.put("counts", counts);
            item.put("status", status);          // 0缺 1齐 2已过期
            item.put("fileUrl", fileUrl);
            item.put("expireDate", exp);
            item.put("daysLeft", daysLeft);
            item.put("fromReal", fromReal);      // 由真实子表自动点亮
            item.put("realNote", realNote);
            item.put("danger", danger);
            items.add(item);
            groups.get(group).add(item);
        }

        int percent = required == 0 ? 0 : (int) Math.round(done * 100.0 / required);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("groups", groups);
        out.put("percent", percent);
        out.put("required", required);
        out.put("done", done);
        out.put("missing", missing);
        out.put("expired", expiredCount);
        out.put("danger", danger);
        return out;
    }

    private boolean isExpired(String exp) {
        if (exp == null || exp.length() < 10) return false;
        try { return LocalDate.parse(exp.substring(0, 10)).isBefore(LocalDate.now()); }
        catch (Exception e) { return false; }
    }

    private Map<String, Object> vehicleOrEmpty(long id) {
        try { return jdbc.queryForMap("SELECT * FROM `vehicle` WHERE `id`=? AND `deleted`=0", id); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    /** 归档项定义查找（固定，不随操作改变，避免跳组） */
    private static String[] itemDef(String code) {
        for (String[] d : ITEMS) if (d[0].equals(code)) return d;
        return null;
    }

    /** 归档项 upsert（状态机落库） */
    private void upsertItem(long vehicleId, String itemCode, String sourceType,
                            String fileUrl, String expireDate, Long refId, int status) {
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `vehicle_archive_item` WHERE `vehicle_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                Long.class, vehicleId, itemCode, tid());
        String exp = (expireDate == null || expireDate.isBlank()) ? null : expireDate;
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO `vehicle_archive_item` " +
                            "(`id`,`tenant_id`,`vehicle_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid(), vehicleId, itemCode, sourceType, refId,
                    fileUrl, exp, status, LocalDateTime.now(), uid());
        } else {
            jdbc.update("UPDATE `vehicle_archive_item` SET `source_type`=?, `ref_id`=?, `file_url`=?, " +
                            "`expire_date`=?, `status`=?, `updated_at`=? WHERE `id`=?",
                    sourceType, refId, fileUrl, exp, status, LocalDateTime.now(), ids.get(0));
        }
    }

    /** 证照/保险/等级三表 30 天内到期数量 */
    private int expiringCount(long vehicleId) {
        String d = LocalDate.now().plusDays(30).toString();
        int n = 0;
        n += count("SELECT COUNT(*) FROM `vehicle_doc` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 AND `expire_date` IS NOT NULL AND `expire_date`<=?", vehicleId, tid(), d);
        n += count("SELECT COUNT(*) FROM `vehicle_insurance` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 AND `expire_date` IS NOT NULL AND `expire_date`<=?", vehicleId, tid(), d);
        n += count("SELECT COUNT(*) FROM `vehicle_grade` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 AND `valid_until` IS NOT NULL AND `valid_until`<=?", vehicleId, tid(), d);
        return n;
    }

    // ==================================================================
    // 1. 全车归档概览（列表页「归档完整度」列 + 驾驶舱统计）
    // ==================================================================
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        List<Map<String, Object>> vehicles = jdbc.queryForList(
                "SELECT * FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid());
        List<Map<String, Object>> list = new ArrayList<>();
        int complete = 0, expiringTotal = 0, stopped = 0, trailer = 0;
        for (Map<String, Object> v : vehicles) {
            long vid = ((Number) v.get("id")).longValue();
            Map<String, Object> c = compute(vid, v);
            int expiring = expiringCount(vid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(vid));   // 雪花 ID 序列化为字符串，避免 JS 精度丢失
            for (String k : new String[]{"plate_no", "plate_color", "biz_type", "vehicle_type", "vehicle_class",
                    "is_trailer", "vehicle_no", "brand_model", "vin", "owner", "business_status", "reg_date"}) {
                row.put(k, v.get(k));
            }
            row.put("percent", c.get("percent"));
            row.put("missingCount", ((List<?>) c.get("missing")).size());
            row.put("expiring", expiring);
            row.put("danger", c.get("danger"));
            list.add(row);
            if ((Integer) c.get("percent") >= 100) complete++;
            expiringTotal += expiring;
            if ("stop".equals(s(v.get("business_status")))) stopped++;
            if ("1".equals(s(v.get("is_trailer")))) trailer++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("complete", complete);
        out.put("pending", list.size() - complete);
        out.put("expiring", expiringTotal);
        out.put("stopped", stopped);
        out.put("trailer", trailer);
        out.put("tractor", list.size() - trailer);
        return Result.ok(out);
    }

    // ==================================================================
    // 2. 一车一档详情（13 项三类分组 + 完整度 + 缺项 + 子表 + 车挂/绑定）
    // ==================================================================
    @GetMapping("/{id}/archive")
    public Result<Map<String, Object>> archive(@PathVariable long id) {
        Map<String, Object> vehicle = vehicleOrEmpty(id);
        if (vehicle.isEmpty()) return Result.ok(Map.of("ok", false, "msg", "车辆不存在"));
        Map<String, Object> c = compute(id, vehicle);
        Map<String, Object> out = new LinkedHashMap<>(c);
        out.put("ok", true);
        out.put("vehicle", vehicle);
        out.put("vehicleId", String.valueOf(id));
        out.put("expiring", expiringCount(id));

        // 证照 / 保险 / 设备 / 等级 / 维修 子表真实数据（抽屉 Tab 展示）
        out.put("docs", listSafe("SELECT `id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status` FROM `vehicle_doc` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `expire_date`", id));
        out.put("insurances", listSafe("SELECT `id`,`insurance_type`,`policy_no`,`file_url`,`expire_date`,`status` FROM `vehicle_insurance` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `expire_date`", id));
        out.put("devices", listSafe("SELECT `id`,`device_name`,`quantity` FROM `vehicle_device` WHERE (`vehicle_id`=? OR `vehicle_id` IS NULL) AND `tenant_id`=? AND `deleted`=0", id));
        out.put("grades", listSafe("SELECT `id`,`grade`,`grade_date`,`valid_until`,`file_url` FROM `vehicle_grade` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `grade_date`", id));
        out.put("repairs", listSafe("SELECT `id`,`repair_type`,`repair_date`,`mileage`,`repair_org`,`cost_amount`,`status` FROM `vehicle_repair` WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 ORDER BY `repair_date`", id));
        out.put("links", linksOf(id));
        out.put("binds", bindsOf(id));
        return Result.ok(out);
    }

    private List<Map<String, Object>> listSafe(String sql, long id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, id, tid());
            for (Map<String, Object> r : rows) {
                r.put("id", String.valueOf(r.get("id")));
                for (String k : new String[]{"expire_date", "valid_until"}) {
                    if (r.containsKey(k) && s(r.get(k)).length() >= 10) {
                        try {
                            int left = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(s(r.get(k)).substring(0, 10)));
                            r.put("daysLeft", left);
                            r.put("warning", left <= 30);
                        } catch (Exception ignore) { }
                    }
                }
            }
            return rows;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    // ==================================================================
    // 3. 逐项登记 / 状态流转（0缺 → 1齐 → 2已过期）
    // ==================================================================
    @PutMapping("/{id}/archive/{code}")
    public Result<Map<String, Object>> setItem(@PathVariable long id, @PathVariable String code,
                                               @RequestBody Map<String, Object> b) {
        String[] def = itemDef(code);
        if (def == null) return Result.ok(Map.of("ok", false, "msg", "未知归档项：" + code));
        String fileUrl = s(b.get("fileUrl"));
        String exp = s(b.get("expireDate"));
        String source = s(b.get("sourceType"));
        if (source.isBlank()) source = def[3];
        int status = 1;
        if (b.get("status") != null) {
            try { status = Integer.parseInt(String.valueOf(b.get("status"))); } catch (Exception ignore) { }
        }
        if (status == 1 && isExpired(exp)) status = 2;
        Long refId = null;
        if (b.get("refId") != null) {
            try { refId = Long.parseLong(String.valueOf(b.get("refId"))); } catch (Exception ignore) { }
        }
        upsertItem(id, code, source, fileUrl, exp, refId, status);
        if (status == 1) registrar.register("vehicle_" + code, "BOX1", id, def[1]);
        Map<String, Object> c = compute(id, vehicleOrEmpty(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", code);
        out.put("status", status);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    // ==================================================================
    // 4. 证件上传（vehicle_doc 子表 + 自动落对应归档项）
    // ==================================================================
    @PostMapping("/{id}/doc")
    public Result<Map<String, Object>> addDoc(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String itemCode = s(b.get("itemCode"));
        String[] def = itemDef(itemCode);
        if (def == null) return Result.ok(Map.of("ok", false, "msg", "未知归档项：" + itemCode));
        String docCode = s(b.get("docCode"));
        if (docCode.isBlank()) docCode = itemCode;
        String docName = s(b.get("docName"));
        if (docName.isBlank()) docName = def[1];
        String fileUrl = s(b.get("fileUrl"));
        String issue = s(b.get("issueDate"));
        String exp = s(b.get("expireDate"));
        int status = isExpired(exp) ? 2 : 1;
        jdbc.update("INSERT INTO `vehicle_doc` " +
                        "(`id`,`tenant_id`,`vehicle_id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, docCode, docName, fileUrl,
                issue.isBlank() ? null : issue, exp.isBlank() ? null : exp, status,
                LocalDateTime.now(), uid());
        upsertItem(id, itemCode, "upload", fileUrl, exp, null, status);
        if (status == 1) registrar.register("vehicle_" + itemCode, "BOX1", id, docName);
        Map<String, Object> c = compute(id, vehicleOrEmpty(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", itemCode);
        out.put("status", status);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    // ==================================================================
    // 5. 生成归档 PDF（成品 / 空白表降级）+ 登记到电子档案柜盒一
    // ==================================================================
    @PostMapping("/{id}/generate")
    public Result<Map<String, Object>> generate(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String itemCode = s(b.get("itemCode"));
        String[] def = itemDef(itemCode);
        if (def == null) return Result.ok(Map.of("ok", false, "msg", "未知归档项：" + itemCode));
        boolean blank = Boolean.parseBoolean(String.valueOf(b.getOrDefault("blank", false)));
        Map<String, Object> vehicle = vehicleOrEmpty(id);
        String itemName = def[1];
        String html = buildHtml(vehicle, itemName, itemCode, blank);
        byte[] pdf = pdfService.render(html);
        String key = tid() + "/vehicle/" + id + "_" + itemCode + "_" + System.currentTimeMillis() + ".pdf";
        storage.upload(key, pdf, "application/pdf");
        upsertItem(id, itemCode, blank ? "print" : "auto", key, null, null, 1);
        registrar.register("vehicle_" + itemCode, "BOX1", id, itemName + (blank ? "（空白表）" : ""));
        Map<String, Object> c = compute(id, vehicle);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemCode", itemCode);
        out.put("objectKey", key);
        out.put("size", pdf.length);
        out.put("blank", blank);
        out.put("percent", c.get("percent"));
        out.put("missing", c.get("missing"));
        return Result.ok(out);
    }

    /** 在线查看/下载归档 PDF（与 generate 同一模板，保证内容一致） */
    @GetMapping(value = "/{id}/archive/{code}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable long id, @PathVariable String code,
                                      @RequestParam(defaultValue = "false") boolean blank) {
        String[] def = itemDef(code);
        String itemName = def == null ? "一车一档归档表" : def[1];
        byte[] bytes = pdfService.render(buildHtml(vehicleOrEmpty(id), itemName, code, blank));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"vehicle_" + code + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    private String buildHtml(Map<String, Object> v, String itemName, String itemCode, boolean blank) {
        String plate = esc(s(v.get("plate_no")));
        String vin = esc(s(v.get("vin")));
        String model = esc(s(v.get("brand_model")));
        String owner = esc(s(v.get("owner")));
        String vtype = esc(s(v.get("vehicle_type")));
        String trailer = "1".equals(s(v.get("is_trailer"))) ? "挂车" : "整车";
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:'WenQuanYi Zen Hei',sans-serif;padding:36px;color:#222}")
                .append("h1{text-align:center;font-size:20px;letter-spacing:2px;margin-bottom:6px}")
                .append("table{width:100%;border-collapse:collapse;margin-top:18px}")
                .append("td,th{border:1px solid #666;padding:9px;font-size:13px}")
                .append(".n{background:#f5f5f5;text-align:center;width:110px}")
                .append(".tip{text-align:center;color:#999;font-size:12px}")
                .append(".foot{margin-top:26px;font-size:12px;color:#666}")
                .append("</style></head><body>");
        h.append("<h1>").append(esc(itemName)).append("</h1>");
        if (blank) {
            h.append("<p class='tip'>（空白表 · 本项无系统数据，请打印后手写补充装盒）</p>")
                    .append("<table><tr><td class='n'>车牌号</td><td></td><td class='n'>车辆类型</td><td></td></tr>")
                    .append("<tr><td class='n'>VIN</td><td></td><td class='n'>车 / 挂</td><td></td></tr>")
                    .append("<tr><td class='n'>登记日期</td><td></td><td class='n'>登记人</td><td></td></tr>")
                    .append("<tr><td class='n'>备注</td><td colspan='3'></td></tr></table>");
        } else {
            h.append("<table>")
                    .append("<tr><td class='n'>车牌号</td><td>").append(plate)
                    .append("</td><td class='n'>车辆类型</td><td>").append(vtype).append("</td></tr>")
                    .append("<tr><td class='n'>VIN 车架号</td><td>").append(vin)
                    .append("</td><td class='n'>车 / 挂</td><td>").append(trailer).append("</td></tr>")
                    .append("<tr><td class='n'>品牌型号</td><td>").append(model)
                    .append("</td><td class='n'>所有人</td><td>").append(owner).append("</td></tr>")
                    .append("<tr><td class='n'>归档项编码</td><td>").append(esc(itemCode))
                    .append("</td><td class='n'>生成日期</td><td>").append(LocalDate.now()).append("</td></tr>")
                    .append("<tr><td class='n'>企业确认</td><td colspan='3'>以上内容由系统按车辆档案与台账范本自动生成，经核对无误后归档。</td></tr>")
                    .append("</table>");
        }
        h.append("<p class='foot'>道路运输安全生产管理平台 · 一车一档（盒一） · ").append(LocalDate.now()).append("</p>")
                .append("</body></html>");
        return h.toString();
    }

    // ==================================================================
    // 6. 三表联合到期预警（证照 / 保险 / 等级，30/15/7 天）→ 驾驶舱 / 小程序消息
    // ==================================================================
    @GetMapping("/expiring")
    public Result<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> raw = new ArrayList<>();
        String d = LocalDate.now().plusDays(days).toString();
        // UNION 三表：统一成 (vehicle_id, doc_name, expire_date, source)
        try {
            String sql = "SELECT `vehicle_id`,`doc_name` AS doc_name,`expire_date` AS expire_date,'证照' AS source FROM `vehicle_doc` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `expire_date` IS NOT NULL AND `expire_date`<=? " +
                    "UNION ALL SELECT `vehicle_id`,`insurance_type`,`expire_date`,'保险' FROM `vehicle_insurance` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `expire_date` IS NOT NULL AND `expire_date`<=? " +
                    "UNION ALL SELECT `vehicle_id`,'车辆技术等级评定',`valid_until`,'等级评定' FROM `vehicle_grade` " +
                    "WHERE `tenant_id`=? AND `deleted`=0 AND `valid_until` IS NOT NULL AND `valid_until`<=? " +
                    "ORDER BY `expire_date`";
            raw = jdbc.queryForList(sql, tid(), d, tid(), d, tid(), d);
        } catch (Exception ignore) { }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            String plate = "";
            try { plate = s(jdbc.queryForMap("SELECT `plate_no` FROM `vehicle` WHERE `id`=?", vid).get("plate_no")); }
            catch (Exception ignore) { }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicleId", String.valueOf(vid));
            row.put("plate_no", plate);
            row.put("doc_name", s(r.get("doc_name")));
            row.put("source", s(r.get("source")));
            String exp = s(r.get("expire_date"));
            row.put("expire_date", exp);
            int left = 0;
            try { left = (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.substring(0, 10))); }
            catch (Exception ignore) { }
            row.put("daysLeft", left);
            row.put("level", left < 0 ? "expired" : left <= 7 ? "d7" : left <= 15 ? "d15" : "d30");
            list.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        return Result.ok(out);
    }

    // ==================================================================
    // 7. 车辆下拉选项（模块间联动：各模块选车均来自 vehicle 主表；停运可过滤）
    // ==================================================================
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options(@RequestParam(defaultValue = "false") boolean excludeStopped) {
        String sql = "SELECT `id`,`plate_no`,`vehicle_type`,`vehicle_class`,`is_trailer`,`business_status` FROM `vehicle` " +
                "WHERE `tenant_id`=? AND `deleted`=0" +
                (excludeStopped ? " AND `business_status`='normal'" : "") + " ORDER BY `id`";
        List<Map<String, Object>> list = jdbc.queryForList(sql, tid());
        for (Map<String, Object> r : list) r.put("id", String.valueOf(r.get("id")));
        return Result.ok(list);
    }

    // ==================================================================
    // 8. 车辆被哪些模块引用（模块间联动清单，容错：表/列不存在则计数 0）
    // ==================================================================
    @GetMapping("/{id}/references")
    public Result<List<Map<String, Object>>> references(@PathVariable long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : REFS) {
            int n = count("SELECT COUNT(*) FROM `" + r[2] + "` WHERE `" + r[3] + "`=? AND `deleted`=0", id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", r[0]);
            row.put("name", r[1]);
            row.put("count", n);
            out.add(row);
        }
        return Result.ok(out);
    }

    // ==================================================================
    // 9. 车挂关系（设计 10 §5.3 / §6.4 一挂一主唯一性）
    // ==================================================================
    private List<Map<String, Object>> linksOf(long vehicleId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT l.`id`,l.`tractor_id`,l.`trailer_id`,l.`start_date`,l.`end_date`," +
                            "t.`plate_no` AS tractor_plate, tr.`plate_no` AS trailer_plate " +
                            "FROM `vehicle_link` l LEFT JOIN `vehicle` t ON t.`id`=l.`tractor_id` " +
                            "LEFT JOIN `vehicle` tr ON tr.`id`=l.`trailer_id` " +
                            "WHERE (l.`tractor_id`=? OR l.`trailer_id`=?) AND l.`tenant_id`=? AND l.`deleted`=0 " +
                            "AND l.`end_date` IS NULL ORDER BY l.`id`", vehicleId, vehicleId, tid())) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                row.put("id", String.valueOf(r.get("id")));
                out.add(row);
            }
        } catch (Exception ignore) { }
        return out;
    }

    @PostMapping("/{id}/link")
    public Result<Map<String, Object>> addLink(@PathVariable long id, @RequestBody Map<String, Object> b) {
        long trailerId;
        long tractorId;
        try {
            trailerId = Long.parseLong(s(b.get("trailerId")));
            tractorId = b.get("tractorId") == null ? id : Long.parseLong(s(b.get("tractorId")));
        } catch (Exception e) { return Result.ok(Map.of("ok", false, "msg", "请选择挂车")); }
        // 一挂一主：同一挂车不允许两条重叠活跃记录
        int active = count("SELECT COUNT(*) FROM `vehicle_link` WHERE `trailer_id`=? AND `tenant_id`=? AND `deleted`=0 AND `end_date` IS NULL", trailerId, tid());
        if (active > 0) return Result.ok(Map.of("ok", false, "msg", "该挂车已有牵引车绑定，请先解除原关联（一挂一主）"));
        jdbc.update("INSERT INTO `vehicle_link` " +
                        "(`id`,`tenant_id`,`tractor_id`,`trailer_id`,`start_date`,`end_date`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,NULL,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), tractorId, trailerId, LocalDate.now().toString(),
                LocalDateTime.now(), uid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("links", linksOf(id));
        return Result.ok(out);
    }

    @DeleteMapping("/link/{linkId}")
    public Result<Map<String, Object>> removeLink(@PathVariable long linkId) {
        jdbc.update("UPDATE `vehicle_link` SET `end_date`=?, `updated_at`=? WHERE `id`=?",
                LocalDate.now().toString(), LocalDateTime.now(), linkId);
        return Result.ok(Map.of("ok", true));
    }

    // ==================================================================
    // 10. 人车绑定（设计 10 §5.4 / §6.5 驾驶员唯一、押运员危货）
    // ==================================================================
    private List<Map<String, Object>> bindsOf(long vehicleId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT b.`id`,b.`driver_id`,b.`escort_id`,b.`bind_date`,b.`unbind_date`," +
                            "p.`name` AS driver_name, e.`name` AS escort_name " +
                            "FROM `vehicle_driver` b LEFT JOIN `person` p ON p.`id`=b.`driver_id` " +
                            "LEFT JOIN `person` e ON e.`id`=b.`escort_id` " +
                            "WHERE b.`vehicle_id`=? AND b.`tenant_id`=? AND b.`deleted`=0 " +
                            "AND b.`unbind_date` IS NULL ORDER BY b.`id`", vehicleId, tid())) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                row.put("id", String.valueOf(r.get("id")));
                out.add(row);
            }
        } catch (Exception ignore) { }
        return out;
    }

    @PostMapping("/{id}/bind")
    public Result<Map<String, Object>> addBind(@PathVariable long id, @RequestBody Map<String, Object> b) {
        long driverId;
        try { driverId = Long.parseLong(s(b.get("driverId"))); }
        catch (Exception e) { return Result.ok(Map.of("ok", false, "msg", "请选择驾驶员")); }
        // 同一辆车当前活跃绑定驾驶员唯一：先解绑旧的
        jdbc.update("UPDATE `vehicle_driver` SET `unbind_date`=?, `updated_at`=? " +
                        "WHERE `vehicle_id`=? AND `tenant_id`=? AND `deleted`=0 AND `unbind_date` IS NULL",
                LocalDate.now().toString(), LocalDateTime.now(), id, tid());
        Long escortId = null;
        if (b.get("escortId") != null && !s(b.get("escortId")).isBlank()) {
            try { escortId = Long.parseLong(s(b.get("escortId"))); } catch (Exception ignore) { }
        }
        jdbc.update("INSERT INTO `vehicle_driver` " +
                        "(`id`,`tenant_id`,`vehicle_id`,`driver_id`,`escort_id`,`bind_date`,`unbind_date`,`created_at`,`created_by`,`deleted`) " +
                        "VALUES (?,?,?,?,?,?,NULL,?,?,0)",
                IdUtil.getSnowflakeNextId(), tid(), id, driverId, escortId, LocalDate.now().toString(),
                LocalDateTime.now(), uid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("binds", bindsOf(id));
        return Result.ok(out);
    }

    @DeleteMapping("/bind/{bindId}")
    public Result<Map<String, Object>> removeBind(@PathVariable long bindId) {
        jdbc.update("UPDATE `vehicle_driver` SET `unbind_date`=?, `updated_at`=? WHERE `id`=?",
                LocalDate.now().toString(), LocalDateTime.now(), bindId);
        return Result.ok(Map.of("ok", true));
    }

    // ==================================================================
    // 11. 停运设置（设计 10 §6.3：停运不进一日三检，但归档完整度仍计算）
    // ==================================================================
    @PutMapping("/{id}/status")
    public Result<Map<String, Object>> setStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String status = s(b.get("status"));
        if (!List.of("normal", "stop", "scrap").contains(status))
            return Result.ok(Map.of("ok", false, "msg", "状态须为 normal/stop/scrap"));
        jdbc.update("UPDATE `vehicle` SET `business_status`=?, `updated_at`=? WHERE `id`=? AND `tenant_id`=?",
                status, LocalDateTime.now(), id, tid());
        return Result.ok(Map.of("ok", true, "status", status));
    }

    // ==================================================================
    // 12. 小程序联动：驾驶员「我绑定的车辆」一车一档完整度（复用 MobileProfile）
    // ==================================================================
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(defaultValue = "0") long personId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        // 未显式指定人员时，与 MiniService.defaultPersonId() 一致：取名册第一人
        if (personId <= 0) {
            try {
                Long first = jdbc.queryForObject(
                        "SELECT `id` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` LIMIT 1",
                        Long.class, tid());
                personId = first == null ? 0L : first;
            } catch (Exception ignore) { }
        }
        try {
            List<Map<String, Object>> binds = jdbc.queryForList(
                    "SELECT `vehicle_id` FROM `vehicle_driver` WHERE `driver_id`=? AND `tenant_id`=? " +
                            "AND `deleted`=0 AND `unbind_date` IS NULL ORDER BY `id` DESC LIMIT 1", personId, tid());
            if (binds.isEmpty()) { out.put("msg", "未绑定车辆"); return Result.ok(out); }
            long vid = ((Number) binds.get(0).get("vehicle_id")).longValue();
            Map<String, Object> v = vehicleOrEmpty(vid);
            Map<String, Object> c = compute(vid, v);
            out.put("ok", true);
            out.put("vehicleId", String.valueOf(vid));
            out.put("plateNo", s(v.get("plate_no")));
            out.put("vehicleType", s(v.get("vehicle_type")));
            out.put("percent", c.get("percent"));
            out.put("required", c.get("required"));
            out.put("done", c.get("done"));
            out.put("missing", c.get("missing"));
            out.put("expiring", expiringCount(vid));
        } catch (Exception ignore) { }
        return Result.ok(out);
    }
}
