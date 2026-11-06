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
import java.util.*;

/**
 * D3 风险分级管控 / 风险辨识（18 号设计）。
 *
 * <p>只做**静态、事前**的风险辨识：预置通用风险点库（五类 × 四色），企业勾选后
 * 由系统模板生成手册、两级清单、每岗位一份告知卡、无重大风险说明；危货额外生成
 * 安全评估与无重大事故隐患声明。隐患排查（动态、事中）由 13 号模块负责，不在此重复。
 *
 * <p>归档到「盒八（普货）/ 盒九（危货）·风险分级管控与隐患排查治理」静态项。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController extends PhaseDSupport {

    private static final Set<String> LEVELS = Set.of("红", "橙", "黄", "蓝");
    private static final Set<String> DOC_TYPES = Set.of("manual", "no_risk", "safety_eval", "no_hidden");
    private static final Map<String, String> DOC_NAMES = Map.of(
            "manual", "风险辨识手册", "no_risk", "无重大风险的说明",
            "safety_eval", "安全评估（危货）", "no_hidden", "无重大事故隐患的声明（危货）");
    /** 岗位职能 → 关联风险点分类（R5：告知卡自动从组织架构读取岗位） */
    private static final Map<String, String> POST_RISK = Map.ofEntries(
            Map.entry("driver", "driver,env"),
            Map.entry("escort", "driver,cargo,env"),
            Map.entry("loader", "cargo,env"),
            Map.entry("vehtech", "vehicle"),
            Map.entry("parklot", "vehicle,env"),
            Map.entry("captain", "driver,vehicle,env"),
            Map.entry("mon", "driver,env,manage"),
            Map.entry("disp", "env,manage"),
            Map.entry("safe", "manage,driver,vehicle"),
            Map.entry("gm", "manage"),
            Map.entry("clerk", "manage"),
            Map.entry("finance", "manage"));

    public RiskController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                           PdfService pdfService, StorageService storage) {
        super(jdbc, registrar, pdfService, storage);
    }

    private String bizType() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `biz_type` FROM `tenant_info` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
        return !rows.isEmpty() && "danger".equalsIgnoreCase(String.valueOf(rows.get(0).get("biz_type")))
                ? "DANGER" : "GENERAL";
    }

    private String catalog() {
        return "DANGER".equals(bizType()) ? "BOX9" : "BOX8";
    }

    // ==================================================================
    // 风险点库（R8）：系统预置 + 企业自定义
    // ==================================================================
    @GetMapping("/point-dict")
    public Result<Map<String, Object>> pointDict(@RequestParam(required = false) String category,
                                                @RequestParam(required = false) String cargoType) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM `risk_point_dict` WHERE (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0");
        List<Object> args = new ArrayList<>();
        args.add(tid());
        if (category != null && !category.isBlank()) {
            sql.append(" AND `risk_category`=?");
            args.add(category);
        }
        if (cargoType != null && !cargoType.isBlank()) {
            sql.append(" AND (`cargo_type`='all' OR `cargo_type`=?)");
            args.add(cargoType);
        }
        sql.append(" ORDER BY `id`");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String k = String.valueOf(r.get("risk_category"));
            byCategory.merge(k, 1L, Long::sum);
        }
        return Result.ok(Map.of("total", rows.size(), "byCategory", byCategory, "list", rows));
    }

    @PostMapping("/point-dict")
    @OperLog(module = "risk", action = "add_point")
    public Result<Map<String, Object>> addPoint(@RequestBody Map<String, Object> b) {
        String name = requiredStr(b, "risk_name");
        String level = str(b, "risk_level", "蓝");
        if (!LEVELS.contains(level)) throw ErrorCode.PARAM_INVALID.ex("风险等级必须为 红/橙/黄/蓝");
        Map<String, Object> r = base();
        r.put("risk_name", name);
        r.put("risk_category", str(b, "risk_category", "manage"));
        r.put("risk_level", level);
        r.put("control_measure", str(b, "control_measure", ""));
        r.put("cargo_type", str(b, "cargo_type", "all"));
        long id = insert("risk_point_dict", r);
        return Result.ok(Map.of("id", id, "riskName", name, "riskLevel", level));
    }

    // ==================================================================
    // 两级风险辨识清单（R4）
    // ==================================================================
    @PostMapping("/list")
    @OperLog(module = "risk", action = "generate_list")
    public Result<Map<String, Object>> generateList(@RequestBody Map<String, Object> b) {
        int year = integer(b, "year", LocalDate.now().getYear());
        List<Object> ids = list(b, "pointIds");
        String cargo = "danger".equalsIgnoreCase(bizType()) ? "danger" : "general";
        int generated = 0, skipped = 0;

        if (!ids.isEmpty()) {
            for (Object o : ids) {
                long pid = Long.parseLong(String.valueOf(o));
                List<Map<String, Object>> pts = jdbc.queryForList(
                        "SELECT * FROM `risk_point_dict` WHERE `id`=? AND `deleted`=0 " +
                                "AND (`tenant_id`=0 OR `tenant_id`=?)", pid, tid());
                if (pts.isEmpty()) continue;
                Integer dup = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `risk_list` WHERE `tenant_id`=? AND `year`=? " +
                                "AND `risk_point_id`=? AND `deleted`=0", Integer.class, tid(), year, pid);
                if (dup != null && dup > 0) {
                    skipped++;
                    continue;
                }
                Map<String, Object> p = pts.get(0);
                Map<String, Object> r = base();
                r.put("year", year);
                r.put("risk_point_id", pid);
                r.put("risk_source", String.valueOf(p.get("risk_category")));
                r.put("risk_desc", String.valueOf(p.get("risk_name")));
                r.put("consequence", "人员伤亡、车辆损坏、财产损失");
                r.put("risk_level", p.get("risk_level"));
                r.put("control_engineering", "按车辆技术规程与设备防护要求落实");
                r.put("control_manage", p.get("control_measure"));
                r.put("remark", "由通用风险点库勾选生成");
                long id = insert("risk_list", r);
                registrar.register("risk", catalog(), id, "风险辨识清单：" + p.get("risk_name"));
                generated++;
            }
        } else {
            // 未传 pointIds 时按单条自定义登记
            String desc = requiredStr(b, "risk_desc");
            String level = str(b, "risk_level", "蓝");
            if (!LEVELS.contains(level)) throw ErrorCode.PARAM_INVALID.ex("风险等级必须为 红/橙/黄/蓝");
            Map<String, Object> r = base();
            r.put("year", year);
            r.put("risk_point_id", lng(b, "risk_point_id", 0));
            r.put("risk_source", str(b, "risk_source", ""));
            r.put("risk_desc", desc);
            r.put("consequence", str(b, "consequence", ""));
            r.put("risk_level", level);
            r.put("control_engineering", str(b, "control_engineering", ""));
            r.put("control_manage", str(b, "control_manage", ""));
            r.put("responsible_id", lng(b, "responsible_id", 0));
            long id = insert("risk_list", r);
            registrar.register("risk", catalog(), id, "风险辨识清单：" + desc);
            generated++;
        }
        // 清单应覆盖本企业适用的预置风险点（cargo_type=all 或匹配），供齐备性检查
        Integer applicable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `risk_point_dict` WHERE (`tenant_id`=0 OR `tenant_id`=?) " +
                        "AND `deleted`=0 AND (`cargo_type`='all' OR `cargo_type`=?)", Integer.class, tid(), cargo);
        return Result.ok(Map.of("year", year, "generated", generated, "skipped", skipped,
                "applicablePoints", applicable == null ? 0 : applicable));
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> riskList(@RequestParam(required = false) Integer year) {
        if (year == null) {
            return Result.ok(jdbc.queryForList(
                    "SELECT * FROM `risk_list` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id` DESC", tid()));
        }
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM `risk_list` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0 ORDER BY `id` DESC",
                tid(), year));
    }

    // ==================================================================
    // 岗位风险告知卡（R5）+ 岗位内容块联动（org_post_content）
    // ==================================================================
    @PostMapping("/card")
    @OperLog(module = "risk", action = "generate_cards")
    public Result<Map<String, Object>> generateCards(@RequestBody Map<String, Object> b) {
        int year = integer(b, "year", LocalDate.now().getYear());
        long onlyPost = lng(b, "postId", 0);
        String sql = "SELECT `id`,`post_name`,`func_key`,`duties` FROM `org_post` " +
                "WHERE `tenant_id`=? AND `deleted`=0 AND `enabled`=1";
        List<Map<String, Object>> posts = onlyPost > 0
                ? jdbc.queryForList(sql + " AND `id`=?", tid(), onlyPost)
                : jdbc.queryForList(sql, tid());
        int created = 0, updated = 0;
        for (Map<String, Object> p : posts) {
            long postId = ((Number) p.get("id")).longValue();
            String func = String.valueOf(p.get("func_key") == null ? "" : p.get("func_key"));
            String cats = POST_RISK.getOrDefault(func, "manage");
            List<Map<String, Object>> pts = pointRowsForPost(cats);
            StringBuilder card = new StringBuilder("<h2>岗位风险告知卡</h2><p>岗位：")
                    .append(esc(p.get("post_name"))).append("（").append(esc(func)).append("）</p><ul>");
            List<String> names = new ArrayList<>();
            for (Map<String, Object> pt : pts) {
                card.append("<li>").append(esc(pt.get("risk_name"))).append("【")
                        .append(esc(pt.get("risk_level"))).append("】")
                        .append(esc(pt.get("control_measure"))).append("</li>");
                names.add(String.valueOf(pt.get("risk_name")));
            }
            card.append("</ul><p>本告知卡由系统依据组织架构岗位与通用风险点库自动生成，"
                    + "经主要负责人与安全员签字后归档。</p>");

            List<Map<String, Object>> old = jdbc.queryForList(
                    "SELECT `id` FROM `risk_card` WHERE `tenant_id`=? AND `year`=? AND `post_id`=? AND `deleted`=0",
                    tid(), year, postId);
            long cardId;
            if (old.isEmpty()) {
                Map<String, Object> r = base();
                r.put("year", year);
                r.put("post_id", postId);
                r.put("post_name", p.get("post_name"));
                r.put("risk_points", String.join("、", names));
                r.put("content_html", card.toString());
                cardId = insert("risk_card", r);
                created++;
            } else {
                cardId = ((Number) old.get(0).get("id")).longValue();
                jdbc.update("UPDATE `risk_card` SET `post_name`=?, `risk_points`=?, `content_html`=? WHERE `id`=?",
                        p.get("post_name"), String.join("、", names), card.toString(), cardId);
                updated++;
            }
            // 联动岗位内容块（hazard），供责任制/范本引用
            List<Map<String, Object>> content = jdbc.queryForList(
                    "SELECT `id` FROM `org_post_content` WHERE `tenant_id`=? AND `post_code`=? " +
                            "AND `content_code`='hazard' AND `deleted`=0", tid(), func);
            if (content.isEmpty()) {
                Map<String, Object> c = base();
                c.put("post_code", func);
                c.put("content_code", "hazard");
                c.put("content_val", String.join("、", names));
                insert("org_post_content", c);
            } else {
                jdbc.update("UPDATE `org_post_content` SET `content_val`=? WHERE `id`=?",
                        String.join("、", names), content.get(0).get("id"));
            }
            registrar.register("risk", catalog(), cardId, "岗位风险告知卡：" + p.get("post_name"));
        }
        return Result.ok(Map.of("year", year, "posts", posts.size(), "created", created, "updated", updated));
    }

    private List<Map<String, Object>> pointRowsForPost(String cats) {
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(tid());
        for (String c : cats.split(",")) {
            if (in.length() > 0) in.append(",");
            in.append("?");
            args.add(c.trim());
        }
        args.add("danger".equalsIgnoreCase(bizType()) ? "danger" : "general");
        return jdbc.queryForList(
                "SELECT `risk_name`,`risk_level`,`control_measure` FROM `risk_point_dict` " +
                        "WHERE (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0 " +
                        "AND `risk_category` IN (" + in + ") " +
                        "AND (`cargo_type`='all' OR `cargo_type`=?) ORDER BY `id` LIMIT 8", args.toArray());
    }

    // ==================================================================
    // 手册 / 说明 / 危货专属（R3/R6/R7）
    // ==================================================================
    @PostMapping("/manual")
    @OperLog(module = "risk", action = "generate_manual")
    public Result<Map<String, Object>> generateManual(@RequestBody Map<String, Object> b) {
        String docType = str(b, "doc_type", "manual");
        if (!DOC_TYPES.contains(docType)) throw ErrorCode.PARAM_INVALID.ex("doc_type 非法：" + docType);
        boolean dangerOnly = "safety_eval".equals(docType) || "no_hidden".equals(docType);
        boolean danger = "DANGER".equals(bizType());
        if (dangerOnly && !danger) throw ErrorCode.BIZ_STATE_INVALID.ex("该文档仅危货企业可生成");
        int year = integer(b, "year", LocalDate.now().getYear());
        String docName = DOC_NAMES.get(docType);
        String body = switch (docType) {
            case "manual" -> manualHtml(year);
            case "no_risk" -> "<p>经全面辨识，本公司现有运输经营活动中未发现构成重大风险（红色等级）的风险源，"
                    + "已按四色分级对一般风险落实管控措施并纳入清单与岗位告知卡。</p>";
            case "safety_eval" -> "<p>依据《危险化学品安全法》运输安全章节，对承运品类、车辆罐体、押运员在岗、"
                    + "电子运单、动态监控台账及装卸查验记录逐项评估，结论见附页清单。</p>";
            default -> "<p>经排查，本公司不存在重大事故隐患；已建立隐患排查治理台账并实行闭环管理。</p>";
        };
        String content = html(docName, body);
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT `id` FROM `risk_manual` WHERE `tenant_id`=? AND `year`=? AND `doc_type`=? AND `deleted`=0",
                tid(), year, docType);
        long id;
        if (old.isEmpty()) {
            Map<String, Object> r = base();
            r.put("year", year);
            r.put("doc_type", docType);
            r.put("doc_name", docName);
            r.put("content_html", content);
            id = insert("risk_manual", r);
        } else {
            id = ((Number) old.get(0).get("id")).longValue();
            jdbc.update("UPDATE `risk_manual` SET `content_html`=? WHERE `id`=?", content, id);
        }
        registrar.register("risk", catalog(), id, docName);
        return Result.ok(Map.of("id", id, "docType", docType, "docName", docName,
                "bizType", danger ? "DANGER" : "GENERAL"));
    }

    private String manualHtml(int year) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT `risk_desc`,`risk_level`,`control_manage` FROM `risk_list` " +
                        "WHERE `tenant_id`=? AND `year`=? AND `deleted`=0 ORDER BY `id`", tid(), year);
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> i : items) {
            rows.add(Arrays.asList(i.get("risk_desc"), i.get("risk_level"), i.get("control_manage")));
        }
        return "<h2>一、辨识范围与方法</h2><p>按驾驶员、车辆、货物、环境、管理五类场景，"
                + "采用作业活动分解与风险矩阵法，按红（重大）、橙（较大）、黄（一般）、蓝（低）四色分级。</p>"
                + "<h2>二、" + year + " 年度风险辨识清单</h2>"
                + table(new String[]{"风险描述", "等级", "管理措施"}, rows)
                + "<h2>三、管控要求</h2><p>红色风险由主要负责人挂牌督办，橙色由安全管理部门专项管控，"
                + "黄蓝两级纳入岗位告知与日常检查；每年度复评一次。</p>";
    }

    /** 一键生成全部风险辨识资料（R2~R7）。 */
    @PostMapping("/generate")
    @OperLog(module = "risk", action = "generate_all")
    public Result<Map<String, Object>> generateAll(@RequestBody Map<String, Object> b) {
        int year = integer(b, "year", LocalDate.now().getYear());
        boolean danger = "DANGER".equals(bizType());
        // 未显式指定 pointIds 时，默认勾选全部适用预置风险点
        Map<String, Object> listBody = new HashMap<>(b);
        if (list(b, "pointIds").isEmpty()) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT `id` FROM `risk_point_dict` WHERE (`tenant_id`=0 OR `tenant_id`=?) " +
                            "AND `deleted`=0 AND (`cargo_type`='all' OR `cargo_type`=?) ORDER BY `id`",
                    Long.class, tid(), danger ? "danger" : "general");
            listBody.put("pointIds", ids);
        }
        Map<String, Object> lists = generateList(listBody).getData();
        Map<String, Object> cards = generateCards(b).getData();
        List<String> docs = new ArrayList<>(List.of("manual", "no_risk"));
        if (danger) docs.addAll(List.of("safety_eval", "no_hidden"));
        int docCount = 0;
        for (String d : docs) {
            Map<String, Object> db = new HashMap<>(b);
            db.put("doc_type", d);
            generateManual(db);
            docCount++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("bizType", danger ? "DANGER" : "GENERAL");
        out.put("catalog", catalog());
        out.put("list", lists);
        out.put("cards", cards);
        out.put("manuals", docCount);
        return Result.ok(out);
    }

    // ==================================================================
    // 四色统计与归档
    // ==================================================================
    @GetMapping("/four-color")
    public Result<Map<String, Object>> fourColor(@RequestParam(required = false) Integer year) {
        String cond = year == null ? "" : " AND `year`=" + year;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `risk_level`, COUNT(*) AS cnt FROM `risk_list` " +
                        "WHERE `tenant_id`=? AND `deleted`=0" + cond + " GROUP BY `risk_level`", tid());
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String lv : List.of("红", "橙", "黄", "蓝")) counts.put(lv, 0);
        int total = 0;
        for (Map<String, Object> r : rows) {
            long n = ((Number) r.get("cnt")).longValue();
            counts.put(String.valueOf(r.get("risk_level")), n);
            total += n;
        }
        Long cards = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `risk_card` WHERE `tenant_id`=? AND `deleted`=0", Long.class, tid());
        Long manuals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `risk_manual` WHERE `tenant_id`=? AND `deleted`=0", Long.class, tid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("levels", counts);
        out.put("total", total);
        out.put("cards", cards == null ? 0 : cards);
        out.put("manuals", manuals == null ? 0 : manuals);
        out.put("catalog", catalog());
        return Result.ok(out);
    }

    @GetMapping("/archive")
    public Result<Map<String, Object>> archivePreview(@RequestParam(defaultValue = "0") int year) {
        int y = year == 0 ? LocalDate.now().getYear() : year;
        return Result.ok(Map.of("title", "风险分级管控静态资料", "catalog", catalog(), "year", y,
                "html", html("风险辨识资料汇编（" + y + "年度）", manualHtml(y))));
    }

    @PostMapping("/archive/pdf")
    @OperLog(module = "risk", action = "archive_pdf")
    public Result<Map<String, Object>> generateArchivePdf(@RequestParam(defaultValue = "0") int year) {
        int y = year == 0 ? LocalDate.now().getYear() : year;
        return Result.ok(archivePdf("risk", catalog(), IdUtil.getSnowflakeNextId(),
                "风险辨识手册与清单（" + y + "年度）", manualHtml(y)));
    }
}
