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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * D2 安全生产费用（17 号设计）。
 *
 * <p>计提基数 = 上年度营业收入；比例按营运类型自动匹配：普货 1%、危货 1.5%
 * （财资〔2022〕136 号）。客运 passenger 维度按 21 号设计保持冻结。
 * 支出按 11 类标准范围登记并留发票，实时汇总已用/结余与达标提示，归档盒五。
 */
@RestController
@RequestMapping("/api/budget")
public class BudgetController extends PhaseDSupport {

    private static final String CATALOG = "BOX5";
    private static final String ARCHIVE_TITLE = "盒五·安全生产费用提取与使用";
    private static final BigDecimal RATE_GENERAL = new BigDecimal("0.0100");
    private static final BigDecimal RATE_DANGER = new BigDecimal("0.0150");

    public BudgetController(JdbcTemplate jdbc, ArchiveRegistrar registrar,
                            PdfService pdfService, StorageService storage) {
        super(jdbc, registrar, pdfService, storage);
    }

    /** 按企业营运类型自动匹配计提比例（B2）；客运冻结。 */
    private BigDecimal rateFor(Map<String, Object> b) {
        String cargo = str(b, "cargo_type");
        if (cargo == null || cargo.isBlank()) {
            List<Map<String, Object>> infos = jdbc.queryForList(
                    "SELECT `biz_type` FROM `tenant_info` WHERE `tenant_id`=? AND `deleted`=0 LIMIT 1", tid());
            String biz = infos.isEmpty() ? "freight" : String.valueOf(infos.get(0).get("biz_type"));
            if ("passenger".equalsIgnoreCase(biz)) {
                throw ErrorCode.BIZ_STATE_INVALID.ex("客运计提比例按设计冻结，暂不支持");
            }
            cargo = "danger".equalsIgnoreCase(biz) ? "danger" : "general";
        }
        if ("passenger".equalsIgnoreCase(cargo)) {
            throw ErrorCode.BIZ_STATE_INVALID.ex("客运计提比例按设计冻结，暂不支持");
        }
        if (!Set.of("general", "danger").contains(cargo.toLowerCase())) {
            throw ErrorCode.PARAM_INVALID.ex("cargo_type 必须为 general 或 danger");
        }
        return "danger".equalsIgnoreCase(cargo) ? RATE_DANGER : RATE_GENERAL;
    }

    // ==================================================================
    // 年度计提（B1/B2/B3/B7）
    // ==================================================================
    @PostMapping("/plan")
    @OperLog(module = "budget", action = "create_plan")
    public Result<Map<String, Object>> createPlan(@RequestBody Map<String, Object> b) {
        int year = integer(b, "year", LocalDate.now().getYear());
        BigDecimal revenue = dec(b, "revenue", null);
        if (revenue == null || revenue.signum() <= 0) throw ErrorCode.FIELD_REQUIRED.ex("revenue（上年度营业收入）");
        BigDecimal rate = rateFor(b);
        String cargo = str(b, "cargo_type", "");
        if (cargo.isBlank()) cargo = rate.compareTo(RATE_DANGER) == 0 ? "danger" : "general";
        BigDecimal planAmount = revenue.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String revenueFile = str(b, "revenue_file", "");

        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT `id` FROM `budget_plan` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), year);
        long id;
        String status;
        if (old.isEmpty()) {
            Map<String, Object> r = base();
            r.put("year", year);
            r.put("cargo_type", cargo);
            r.put("revenue", revenue);
            r.put("revenue_file", revenueFile);
            r.put("rate", rate);
            r.put("plan_amount", planAmount);
            r.put("used_amount", BigDecimal.ZERO);
            r.put("balance", planAmount);
            r.put("status", "confirmed");
            id = insert("budget_plan", r);
            status = "created";
        } else {
            id = ((Number) old.get(0).get("id")).longValue();
            jdbc.update("UPDATE `budget_plan` SET `cargo_type`=?, `revenue`=?, `revenue_file`=?, " +
                            "`rate`=?, `plan_amount`=?, `status`='confirmed' WHERE `id`=?",
                    cargo, revenue, revenueFile, rate, planAmount, id);
            status = "updated";
        }
        recalc(id);
        registrar.register("budget", CATALOG, id, year + "年度安全费用提取标准及计划");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("year", year);
        out.put("cargoType", cargo);
        out.put("revenue", revenue);
        out.put("rate", rate);
        out.put("planAmount", planAmount);
        out.put("status", status);
        return Result.ok(out);
    }

    @GetMapping("/plan/{year}")
    public Result<Map<String, Object>> plan(@PathVariable int year) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `budget_plan` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), year);
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex(year + " 年度尚未计提");
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.putAll(summaryData(year));
        return Result.ok(out);
    }

    // ==================================================================
    // 费用类别字典（B5）：系统预置 11 类 + 企业自定义
    // ==================================================================
    @GetMapping("/category")
    public Result<List<Map<String, Object>>> categories() {
        return Result.ok(jdbc.queryForList(
                "SELECT `id`,`tenant_id`,`category_name`,`category_code`,`sort_order` " +
                        "FROM `budget_category` WHERE (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0 " +
                        "ORDER BY `sort_order`,`id`", tid()));
    }

    @PostMapping("/category")
    @OperLog(module = "budget", action = "add_category")
    public Result<Map<String, Object>> addCategory(@RequestBody Map<String, Object> b) {
        String name = requiredStr(b, "category_name");
        Map<String, Object> r = base();
        r.put("category_name", name);
        r.put("category_code", str(b, "category_code", "CUSTOM"));
        r.put("sort_order", integer(b, "sort_order", 99));
        long id = insert("budget_category", r);
        return Result.ok(Map.of("id", id, "categoryName", name));
    }

    // ==================================================================
    // 支出台账（B4/B6）+ 实时汇总（B8）
    // ==================================================================
    @PostMapping("/expense")
    @OperLog(module = "budget", action = "add_expense")
    public Result<Map<String, Object>> addExpense(@RequestBody Map<String, Object> b) {
        long planId = lng(b, "plan_id", 0);
        requireExists("budget_plan", planId);
        BigDecimal amount = dec(b, "amount", null);
        if (amount == null || amount.signum() <= 0) throw ErrorCode.FIELD_REQUIRED.ex("amount 必须大于 0");
        String date = str(b, "expense_date", today());
        long categoryId = lng(b, "category_id", 0);
        String desc = str(b, "expense_desc", "");
        String receipt = str(b, "receipt_file", "");

        Map<String, Object> r = base();
        r.put("plan_id", planId);
        r.put("category_id", categoryId);
        r.put("expense_date", date);
        r.put("expense_desc", desc);
        r.put("amount", amount);
        r.put("receipt_file", receipt);
        long id = insert("budget_expense", r);
        recalc(planId);
        registrar.register("budget", CATALOG, id, "安全费用支出：" + desc);

        BigDecimal used = usedOf(planId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("planId", planId);
        out.put("amount", amount);
        out.put("usedAmount", used);
        return Result.ok(out);
    }

    @GetMapping("/expense/list")
    public Result<List<Map<String, Object>>> expenseList(@RequestParam(required = false) Long planId) {
        String sql = "SELECT e.*, c.`category_name` FROM `budget_expense` e " +
                "LEFT JOIN `budget_category` c ON c.`id`=e.`category_id` " +
                "WHERE e.`tenant_id`=? AND e.`deleted`=0 ";
        if (planId != null) {
            return Result.ok(jdbc.queryForList(sql + "AND e.`plan_id`=? ORDER BY e.`expense_date` DESC", tid(), planId));
        }
        return Result.ok(jdbc.queryForList(sql + "ORDER BY e.`expense_date` DESC LIMIT 300", tid()));
    }

    private BigDecimal usedOf(long planId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(`amount`),0) FROM `budget_expense` WHERE `plan_id`=? AND `deleted`=0",
                BigDecimal.class, planId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private void recalc(long planId) {
        BigDecimal used = usedOf(planId);
        jdbc.update("UPDATE `budget_plan` SET `used_amount`=?, `balance`=`plan_amount`-? WHERE `id`=?",
                used, used, planId);
    }

    private Map<String, Object> summaryData(int year) {
        List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT `id`,`plan_amount`,`used_amount`,`balance`,`rate`,`revenue` FROM `budget_plan` " +
                        "WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), year);
        BigDecimal planAmt = BigDecimal.ZERO, used = BigDecimal.ZERO;
        long planId = 0;
        if (!plans.isEmpty()) {
            Map<String, Object> p = plans.get(0);
            planId = ((Number) p.get("id")).longValue();
            planAmt = (BigDecimal) p.getOrDefault("plan_amount", BigDecimal.ZERO);
            used = usedOf(planId);
        }
        String tip = used.compareTo(planAmt) < 0 ? "未足额使用" :
                (used.compareTo(planAmt) > 0 ? "超支提醒" : "已足额使用");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("planId", planId);
        out.put("planAmount", planAmt);
        out.put("usedAmount", used);
        out.put("balance", planAmt.subtract(used));
        out.put("usageRate", planAmt.signum() == 0 ? 0 :
                used.multiply(new BigDecimal("100")).divide(planAmt, 0, RoundingMode.HALF_UP));
        out.put("complianceTip", tip);
        out.put("matrix", matrix(planId));
        return out;
    }

    /** 使用台账矩阵：11 类费用 × 12 月（对齐线下 xlsx 横向汇总 Sheet）。 */
    private List<Map<String, Object>> matrix(long planId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (planId == 0) return rows;
        List<Map<String, Object>> cats = jdbc.queryForList(
                "SELECT `id`,`category_name` FROM `budget_category` " +
                        "WHERE (`tenant_id`=0 OR `tenant_id`=?) AND `deleted`=0 ORDER BY `sort_order`,`id`", tid());
        List<Map<String, Object>> sums = jdbc.queryForList(
                "SELECT `category_id`, MONTH(`expense_date`) AS m, SUM(`amount`) AS amt " +
                        "FROM `budget_expense` WHERE `plan_id`=? AND `deleted`=0 " +
                        "GROUP BY `category_id`, MONTH(`expense_date`)", planId);
        Map<String, BigDecimal> idx = new HashMap<>();
        for (Map<String, Object> s : sums) {
            idx.put(String.valueOf(s.get("category_id")) + "-" + s.get("m"), (BigDecimal) s.get("amt"));
        }
        for (Map<String, Object> c : cats) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryId", c.get("id"));
            row.put("categoryName", c.get("category_name"));
            BigDecimal total = BigDecimal.ZERO;
            for (int m = 1; m <= 12; m++) {
                BigDecimal v = idx.getOrDefault(String.valueOf(c.get("id")) + "-" + m, BigDecimal.ZERO);
                row.put("m" + m, v);
                total = total.add(v);
            }
            row.put("total", total);
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/summary/{year}")
    public Result<Map<String, Object>> summary(@PathVariable int year) {
        return Result.ok(summaryData(year));
    }

    // ==================================================================
    // 归档盒五（B12）
    // ==================================================================
    private String archiveBody(int year) {
        Map<String, Object> s = summaryData(year);
        List<Map<String, Object>> cat = matrix(((Number) s.get("planId")).longValue());
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : cat) {
            List<Object> r = new ArrayList<>();
            r.add(c.get("categoryName"));
            for (int m = 1; m <= 12; m++) r.add(c.get("m" + m));
            r.add(c.get("total"));
            rows.add(r);
        }
        List<Map<String, Object>> details = jdbc.queryForList(
                "SELECT e.`expense_date`,c.`category_name`,e.`expense_desc`,e.`amount`,e.`receipt_file` " +
                        "FROM `budget_expense` e LEFT JOIN `budget_category` c ON c.`id`=e.`category_id` " +
                        "WHERE e.`tenant_id`=? AND e.`deleted`=0 ORDER BY e.`expense_date`", tid());
        List<List<Object>> drows = new ArrayList<>();
        for (Map<String, Object> d : details) {
            drows.add(Arrays.asList(d.get("expense_date"), d.get("category_name"), d.get("expense_desc"),
                    d.get("amount"), d.get("receipt_file") == null ? "" : "已留存"));
        }
        String[] heads = new String[14];
        heads[0] = "费用类别";
        for (int m = 1; m <= 12; m++) heads[m] = m + "月";
        heads[13] = "合计";
        List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT `revenue`,`rate`,`revenue_file` FROM `budget_plan` " +
                        "WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), year);
        String revenue = plans.isEmpty() ? "未录入" : String.valueOf(plans.get(0).get("revenue"));
        String rate = plans.isEmpty() ? "未计提" : String.valueOf(plans.get(0).get("rate"));
        String proof = plans.isEmpty() || plans.get(0).get("revenue_file") == null
                || String.valueOf(plans.get(0).get("revenue_file")).isBlank() ? "待上传" : "已留存";
        return "<h2>一、年度营收与提取标准</h2><p>计提年度：" + year
                + "；上年度营业收入：" + revenue + " 元；计提比例：" + rate
                + "；营收证明：" + proof + "；应提金额：" + s.get("planAmount") + " 元。</p>"
                + "<h2>二、安全费用使用台账（11 类 × 12 月）</h2>" + table(heads, rows)
                + "<h2>三、逐笔支出明细（含发票/收据）</h2>"
                + table(new String[]{"支出日期", "费用类别", "事由", "金额（元）", "凭证"}, drows)
                + "<h2>四、达标情况</h2><p>应提：" + s.get("planAmount") + "，已用：" + s.get("usedAmount")
                + "，结余：" + s.get("balance") + "，使用率：" + s.get("usageRate")
                + "%，系统提示：" + s.get("complianceTip") + "</p>";
    }

    @GetMapping("/archive")
    public Result<Map<String, Object>> archivePreview(@RequestParam(defaultValue = "0") int year) {
        int y = year == 0 ? LocalDate.now().getYear() : year;
        return Result.ok(Map.of("title", ARCHIVE_TITLE, "catalog", CATALOG, "year", y,
                "html", html(ARCHIVE_TITLE, archiveBody(y))));
    }

    @PostMapping("/archive/pdf")
    @OperLog(module = "budget", action = "archive_pdf")
    public Result<Map<String, Object>> generateArchivePdf(@RequestParam(defaultValue = "0") int year) {
        int y = year == 0 ? LocalDate.now().getYear() : year;
        List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT `id` FROM `budget_plan` WHERE `tenant_id`=? AND `year`=? AND `deleted`=0", tid(), y);
        long bizId = plans.isEmpty() ? IdUtil.getSnowflakeNextId() : ((Number) plans.get(0).get("id")).longValue();
        return Result.ok(archivePdf("budget", CATALOG, bizId, ARCHIVE_TITLE + "（" + y + "年度）", archiveBody(y)));
    }
}
