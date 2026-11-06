package com.safetrans.phase3;

import cn.hutool.core.util.IdUtil;
import com.safetrans.biz.ArchiveRegistrar;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * D4 危货专项深化（20 号 §4.4 / 21 号 §5.1）。
 *
 * <p>六项专项台账统一由 danger_special_ledger 承载（ledger_type 区分），
 * 电子运单单独建表并强制校验押运员在岗、车辆资质查验、800 公里以上双押双驾；
 * 停车场管理复用 B2 既有 tenant_parking。全部归档「盒六·动态监控及交通违法管理」。
 *
 * <p>危货应急预案按用户确认排除；客运全部冻结，不实现出站检查/安全例检等客运台账。
 */
@RestController
@RequestMapping("/api/danger")
public class DangerSpecialController extends PhaseDSupport {

    private static final String CATALOG = "BOX6";
    private static final String ARCHIVE_TITLE = "盒六·危货专项台账与电子运单";
    /** 21 号设计 §5.1 六项强制台账 */
    private static final List<Map<String, String>> LEDGER_TYPES = List.of(
            Map.of("code", "waybill", "name", "危险货物电子运单", "freq", "每趟", "basis", "危险化学品安全法（2026.5.1）"),
            Map.of("code", "load_check", "name", "装卸查验记录", "freq", "每次装卸", "basis", "道路危险货物运输管理规定"),
            Map.of("code", "transport", "name", "危险品运输台账", "freq", "每日", "basis", "危货企业检查标准"),
            Map.of("code", "escort", "name", "押运员在岗记录", "freq", "每趟", "basis", "危货专项检查"),
            Map.of("code", "long_line", "name", "800公里以上线路台账", "freq", "超长线路", "basis", "双押双驾管控"),
            Map.of("code", "safety_card", "name", "安全卡（一书一签）", "freq", "每车", "basis", "危化品安全管理"));
    private static final Set<String> WAYBILL_FLOW = Set.of("draft", "transit", "completed", "archived");

    public DangerSpecialController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                                   PdfService pdfService, StorageService storage) {
        super(jdbc, registrar, pdfService, storage);
    }

    private static String typeName(String code) {
        return LEDGER_TYPES.stream().filter(t -> t.get("code").equals(code))
                .map(t -> t.get("name")).findFirst().orElse(code);
    }

    private static void requireLedgerType(String type) {
        if (LEDGER_TYPES.stream().noneMatch(t -> t.get("code").equals(type))) {
            throw ErrorCode.PARAM_INVALID.ex("ledger_type 必须为六项专项台账之一：" + type);
        }
    }

    // ==================================================================
    // 六项专项台账
    // ==================================================================
    @GetMapping("/ledger-types")
    public Result<Map<String, Object>> ledgerTypes() {
        return Result.ok(Map.of("total", LEDGER_TYPES.size(), "list", LEDGER_TYPES));
    }

    @PostMapping("/ledger")
    @OperLog(module = "dangerspecial", action = "add_ledger")
    public Result<Map<String, Object>> addLedger(@RequestBody Map<String, Object> b) {
        String type = requiredStr(b, "ledger_type");
        requireLedgerType(type);
        Map<String, Object> r = base();
        r.put("ledger_type", type);
        r.put("biz_date", str(b, "biz_date", today()));
        r.put("waybill_no", str(b, "waybill_no", ""));
        r.put("plate_no", str(b, "plate_no", ""));
        r.put("cargo_name", str(b, "cargo_name", ""));
        r.put("vehicle_id", lng(b, "vehicle_id", 0));
        r.put("driver_id", lng(b, "driver_id", 0));
        r.put("escort_id", lng(b, "escort_id", 0));
        r.put("content", str(b, "content", ""));
        r.put("file_url", str(b, "file_url", ""));
        r.put("status", str(b, "status", "confirmed"));
        r.put("remark", str(b, "remark", ""));
        long id = insert("danger_special_ledger", r);
        registrar.register("dangerspecial", CATALOG, id, typeName(type));
        return Result.ok(Map.of("id", id, "ledgerType", type, "ledgerTypeName", typeName(type)));
    }

    @GetMapping("/ledger/list")
    public Result<List<Map<String, Object>>> ledgerList(@RequestParam(required = false) String ledgerType) {
        String sql = "SELECT * FROM `danger_special_ledger` WHERE `tenant_id`=? AND `deleted`=0 ";
        if (ledgerType != null && !ledgerType.isBlank()) {
            return Result.ok(jdbc.queryForList(sql + "AND `ledger_type`=? ORDER BY `biz_date` DESC, `id` DESC",
                    tid(), ledgerType));
        }
        return Result.ok(jdbc.queryForList(sql + "ORDER BY `biz_date` DESC, `id` DESC LIMIT 300", tid()));
    }

    /** 政府检查视角：六项台账是否齐备。 */
    @GetMapping("/completeness")
    public Result<Map<String, Object>> completeness() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `ledger_type`, COUNT(*) AS cnt FROM `danger_special_ledger` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 GROUP BY `ledger_type`", tid());
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> r : rows) counts.put(String.valueOf(r.get("ledger_type")),
                ((Number) r.get("cnt")).longValue());
        List<Map<String, Object>> items = new ArrayList<>();
        int ready = 0;
        for (Map<String, String> t : LEDGER_TYPES) {
            long n = counts.getOrDefault(t.get("code"), 0L);
            if (n > 0) ready++;
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("count", n);
            m.put("ready", n > 0);
            items.add(m);
        }
        return Result.ok(Map.of("total", LEDGER_TYPES.size(), "ready", ready,
                "qualified", ready == LEDGER_TYPES.size(), "items", items));
    }

    // ==================================================================
    // 电子运单（法律法规强制）
    // ==================================================================
    @PostMapping("/waybill")
    @OperLog(module = "dangerspecial", action = "create_waybill")
    public Result<Map<String, Object>> createWaybill(@RequestBody Map<String, Object> b) {
        String no = str(b, "waybill_no");
        if (no == null || no.isBlank()) no = "DWB" + System.currentTimeMillis();
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT `id`,`status` FROM `danger_waybill` WHERE `tenant_id`=? AND `waybill_no`=? AND `deleted`=0",
                tid(), no);
        if (!old.isEmpty()) {
            return Result.ok(Map.of("id", old.get(0).get("id"), "waybillNo", no,
                    "status", String.valueOf(old.get(0).get("status")), "idempotent", true));
        }
        String cargo = requiredStr(b, "cargo_name");
        long vehicleId = lng(b, "vehicle_id", 0);
        long driverId = lng(b, "driver_id", 0);
        long escortId = lng(b, "escort_id", 0);
        if (vehicleId == 0 || driverId == 0 || escortId == 0) {
            throw ErrorCode.FIELD_REQUIRED.ex("vehicle_id / driver_id / escort_id（危货运单三项强制匹配）");
        }
        int escortOnDuty = integer(b, "escort_on_duty", 0);
        int vehicleChecked = integer(b, "vehicle_checked", 0);
        if (escortOnDuty != 1) throw ErrorCode.BIZ_STATE_INVALID.ex("押运员未确认在岗，不得生成电子运单");
        if (vehicleChecked != 1) throw ErrorCode.BIZ_STATE_INVALID.ex("车辆资质/罐体/标识未经查验，不得生成电子运单");
        int distance = integer(b, "distance_km", 0);
        int doubleDriver = integer(b, "double_driver", 0);
        if (distance >= 800 && doubleDriver != 1) {
            throw ErrorCode.BIZ_STATE_INVALID.ex("800 公里以上线路必须安排双押双驾");
        }

        Map<String, Object> r = base();
        r.put("waybill_no", no);
        r.put("cargo_name", cargo);
        r.put("danger_class", str(b, "danger_class", ""));
        r.put("un_no", str(b, "un_no", ""));
        r.put("quantity", dec(b, "quantity", null));
        r.put("unit", str(b, "unit", "吨"));
        r.put("origin", str(b, "origin", ""));
        r.put("destination", str(b, "destination", ""));
        r.put("vehicle_id", vehicleId);
        r.put("plate_no", str(b, "plate_no", ""));
        r.put("driver_id", driverId);
        r.put("escort_id", escortId);
        r.put("depart_at", LocalDateTime.now());
        r.put("distance_km", distance);
        r.put("double_driver", doubleDriver);
        r.put("escort_on_duty", escortOnDuty);
        r.put("vehicle_checked", vehicleChecked);
        r.put("status", "draft");
        r.put("file_url", str(b, "file_url", ""));
        r.put("remark", str(b, "remark", ""));
        long id = insert("danger_waybill", r);

        // 与六项台账联动：每次生成运单自动补一条「危险货物电子运单」台账
        Map<String, Object> link = base();
        link.put("ledger_type", "waybill");
        link.put("biz_date", today());
        link.put("waybill_no", no);
        link.put("plate_no", r.get("plate_no"));
        link.put("cargo_name", cargo);
        link.put("vehicle_id", vehicleId);
        link.put("driver_id", driverId);
        link.put("escort_id", escortId);
        link.put("content", "起运地：" + r.get("origin") + "；送达地：" + r.get("destination"));
        link.put("status", "confirmed");
        long linkId = insert("danger_special_ledger", link);
        registrar.register("dangerspecial", CATALOG, id, "危险货物电子运单：" + no);
        registrar.register("dangerspecial", CATALOG, linkId, "危险货物电子运单台账：" + no);
        return Result.ok(Map.of("id", id, "waybillNo", no, "ledgerId", linkId,
                "status", "draft", "doubleDriverRequired", distance >= 800));
    }

    @GetMapping("/waybill/list")
    public Result<List<Map<String, Object>>> waybillList() {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM `danger_waybill` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC LIMIT 300",
                tid()));
    }

    @PutMapping("/waybill/{id}/status")
    @OperLog(module = "dangerspecial", action = "waybill_status")
    public Result<Map<String, Object>> waybillStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
        String next = requiredStr(b, "status");
        if (!WAYBILL_FLOW.contains(next)) throw ErrorCode.PARAM_INVALID.ex("status 非法：" + next);
        requireExists("danger_waybill", id);
        if ("completed".equals(next) || "archived".equals(next)) {
            jdbc.update("UPDATE `danger_waybill` SET `status`=?, `arrive_at`=? WHERE `id`=?",
                    next, LocalDateTime.now(), id);
        } else {
            jdbc.update("UPDATE `danger_waybill` SET `status`=? WHERE `id`=?", next, id);
        }
        if ("archived".equals(next)) registrar.register("dangerspecial", CATALOG, id, "电子运单归档");
        return Result.ok(Map.of("id", id, "status", next));
    }

    // ==================================================================
    // 停车场管理（复用 B2 既有 tenant_parking）
    // ==================================================================
    @GetMapping("/parking")
    public Result<Map<String, Object>> parking() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `tenant_parking` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid());
        int ready = 0;
        for (Map<String, Object> p : rows) {
            boolean ok = p.get("address") != null && p.get("area") != null
                    && p.get("land_proof") != null && p.get("layout_image") != null;
            if (ok) ready++;
        }
        return Result.ok(Map.of("total", rows.size(), "qualified", ready,
                "note", "危货专用停车场（21 号设计：盒七·停车场管理）", "list", rows));
    }

    // ==================================================================
    // 归档盒六
    // ==================================================================
    private String archiveBody() {
        List<Map<String, Object>> waybills = jdbc.queryForList(
                "SELECT `waybill_no`,`cargo_name`,`origin`,`destination`,`plate_no`,`status` " +
                        "FROM `danger_waybill` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", tid());
        List<List<Object>> wrows = new ArrayList<>();
        for (Map<String, Object> w : waybills) {
            wrows.add(Arrays.asList(w.get("waybill_no"), w.get("cargo_name"), w.get("origin"),
                    w.get("destination"), w.get("plate_no"), w.get("status")));
        }
        List<Map<String, Object>> ledgers = jdbc.queryForList(
                "SELECT `ledger_type`, COUNT(*) AS cnt FROM `danger_special_ledger` " +
                        "WHERE `tenant_id`=? AND `deleted`=0 GROUP BY `ledger_type`", tid());
        List<List<Object>> lrows = new ArrayList<>();
        for (Map<String, Object> l : ledgers) {
            lrows.add(Arrays.asList(typeName(String.valueOf(l.get("ledger_type"))), l.get("cnt"),
                    "已留存"));
        }
        Map<String, Object> c = completeness().getData();
        return "<h2>一、危险货物电子运单</h2>"
                + table(new String[]{"运单号", "货物品名", "起运地", "送达地", "车牌", "状态"}, wrows)
                + "<h2>二、六项专项台账归集</h2>"
                + table(new String[]{"台账名称", "记录数", "归档状态"}, lrows)
                + "<p>齐备情况：" + c.get("ready") + "/" + c.get("total")
                + "，结论：" + (Boolean.TRUE.equals(c.get("qualified")) ? "齐备" : "待补齐") + "</p>";
    }

    @GetMapping("/archive")
    public Result<Map<String, Object>> archivePreview() {
        return Result.ok(Map.of("title", ARCHIVE_TITLE, "catalog", CATALOG,
                "html", html(ARCHIVE_TITLE, archiveBody())));
    }

    @PostMapping("/archive/pdf")
    @OperLog(module = "dangerspecial", action = "archive_pdf")
    public Result<Map<String, Object>> generateArchivePdf() {
        return Result.ok(archivePdf("dangerspecial", CATALOG, IdUtil.getSnowflakeNextId(),
                ARCHIVE_TITLE, archiveBody()));
    }
}
