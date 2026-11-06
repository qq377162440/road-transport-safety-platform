package com.safetrans.biz;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safetrans.common.audit.OperLog;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.ErrorCode;
import com.safetrans.common.web.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * E2 数据初始化与开户引导（需求 §十一 阶段 0~5 + 详细设计 04）。
 *
 * <p>服务商签约开户后，系统自动创建租户、企业主账号、12 个默认岗位、
 * 六阶段检查项进度；企业负责人按三步自助填报（企业资料 → 人车档案 → 安全现状），
 * 服务商按检查项逐项推进交付阶段，最终由 /readiness 做归档就位自检。
 *
 * <p>关键约束：
 * <ul>
 *   <li>开户接口仅平台账号（provider / admin）可调用；企业只能操作自己的数据</li>
 *   <li>录入人员手机号即自动开通小程序端账号（账号=手机号，初始密码 123456）</li>
 *   <li>服务商代录的人车档案由 ArchiveRegistrar 显式指定租户归档（铁律 6）</li>
 *   <li>三步完成前业务功能锁定由前端引导页承接；后端 submit 前校验 step=3</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ArchiveRegistrar registrar;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 阶段 1 三步填报对应的检查项 */
    private static final List<String> STEP_ITEMS = List.of("S1_STEP1", "S1_STEP2", "S1_STEP3");

    public OnboardingController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, ArchiveRegistrar registrar) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.registrar = registrar;
    }

    // ==================================================================
    // 阶段 0：签约与开户
    // ==================================================================

    /**
     * 服务商为新签约企业开户。
     * 入参：name, credit_code, biz_type(GENERAL/DANGER), region, contact, phone,
     *      contract_start, contract_end
     * 产出：sys_tenant + tenant_info 骨架 + 企业主账号 + 12 默认岗位 + 接入状态 + 检查项进度
     */
    @PostMapping("/open")
    @OperLog(module = "onboarding", action = "open_account")
    public Result<Map<String, Object>> open(@RequestBody Map<String, Object> body) {
        requirePlatform();
        String name = requiredStr(body, "name");
        String creditCode = requiredStr(body, "credit_code");
        String bizType = str(body, "biz_type", "GENERAL");
        if (!Set.of("GENERAL", "DANGER").contains(bizType)) {
            throw ErrorCode.PARAM_INVALID.ex("biz_type 必须为 GENERAL（普货）或 DANGER（危货）");
        }
        String region = str(body, "region", "");
        String contact = str(body, "contact", "");
        String phone = str(body, "phone", "");
        String contractStart = str(body, "contract_start", null);
        String contractEnd = str(body, "contract_end", null);

        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `sys_tenant` WHERE `credit_code`=? AND `deleted`=0",
                Integer.class, creditCode);
        if (dup != null && dup > 0) {
            throw ErrorCode.BIZ_STATE_INVALID.ex("该企业已开户（统一社会信用代码重复）");
        }

        long tid = IdUtil.getSnowflakeNextId();
        long uid = TenantContextHolder.userId() == null ? 0L : TenantContextHolder.userId();

        jdbc.update("INSERT INTO `sys_tenant` (`id`,`name`,`credit_code`,`status`,`expire_time`) VALUES (?,?,?,1,?)",
                tid, name, creditCode, contractEnd == null ? null : contractEnd + " 00:00:00");

        jdbc.update("INSERT INTO `tenant_info` " +
                        "(`id`,`tenant_id`,`credit_code`,`biz_type`,`biz_term_type`,`region`,`safety_charge`,`charge_phone`) " +
                        "VALUES (?,?,?,?,1,?,?,?)",
                IdUtil.getSnowflakeNextId(), tid, creditCode,
                "DANGER".equals(bizType) ? "danger" : "freight", region, contact, phone);

        // 主账号：用户名 = 企业全称（超长时回退统一社会信用代码，避免破坏 username UNIQUE/长度约束）
        String username = name.length() > 45 ? creditCode : name;
        long mainUserId = createUser(tid, username, "888888", "enterprise", name, phone);
        jdbc.update("INSERT INTO `sys_user_role` (`user_id`,`role_id`) VALUES (?,1001)", mainUserId);

        int posts = initDefaultPosts(tid);

        jdbc.update("INSERT INTO `sys_onboarding` " +
                        "(`id`,`tenant_id`,`stage`,`step`,`status`,`biz_type`,`contract_start`,`contract_end`,`created_by`) " +
                        "VALUES (?,?,0,0,'pending',?,?,?,?)",
                IdUtil.getSnowflakeNextId(), tid, bizType, contractStart, contractEnd, uid);

        List<Map<String, Object>> checks = jdbc.queryForList(
                "SELECT `item_code`,`stage` FROM `onboarding_checklist` WHERE `deleted`=0 ORDER BY `stage`,`sort_no`");
        for (Map<String, Object> c : checks) {
            jdbc.update("INSERT INTO `onboarding_item` (`id`,`tenant_id`,`item_code`,`stage`,`done`) VALUES (?,?,?,?,0)",
                    IdUtil.getSnowflakeNextId(), tid, c.get("item_code"), c.get("stage"));
        }
        // 开户动作本身完成了阶段 0 的签约/建号/配置三项；营业执照待企业上传
        markItem(tid, "S0_CONTRACT", true, uid);
        markItem(tid, "S0_TENANT", true, uid);
        markItem(tid, "S0_CONFIG", true, uid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tid);
        out.put("tenantName", name);
        out.put("username", username);
        out.put("initialPassword", "888888");
        out.put("bizType", bizType);
        out.put("defaultPosts", posts);
        out.put("checkItems", checks.size());
        out.put("nextStep", "上传营业执照并登录主账号完成三步接入填报");
        return Result.ok(out);
    }

    /** 从平台统一的 12 职能字典生成企业默认岗位（总经理为根，其余挂其下） */
    private int initDefaultPosts(long tid) {
        List<Map<String, Object>> dicts = jdbc.queryForList(
                "SELECT `id`,`dict_code`,`dict_name` FROM `org_dict` WHERE `dict_type`='post' ORDER BY `sort`");
        long gmId = 0L;
        for (Map<String, Object> d : dicts) {
            if ("gm".equals(String.valueOf(d.get("dict_code")))) {
                gmId = IdUtil.getSnowflakeNextId();
                break;
            }
        }
        int n = 0;
        for (Map<String, Object> d : dicts) {
            String code = String.valueOf(d.get("dict_code"));
            boolean isGm = "gm".equals(code);
            long postId = isGm ? gmId : IdUtil.getSnowflakeNextId();
            int required = Set.of("gm", "safe", "mon").contains(code) ? 1 : 0;
            int enabled = Set.of("gm", "safe", "mon", "driver").contains(code) ? 1 : 0;
            jdbc.update("INSERT INTO `org_post` " +
                            "(`id`,`tenant_id`,`parent_id`,`dict_id`,`func_key`,`post_name`,`required`,`enabled`,`goals`,`duties`,`assess_std`) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    postId, tid, isGm ? 0L : gmId, d.get("id"), code, d.get("dict_name"),
                    required, enabled, "", "", "");
            n++;
        }
        return n;
    }

    // ==================================================================
    // 阶段 1：三步自助填报
    // ==================================================================

    /** 第一步：企业基础资料（复用 tenant_info 正式字段，不另建一套接入字段） */
    @PostMapping("/step1")
    @OperLog(module = "onboarding", action = "step1")
    public Result<Map<String, Object>> step1(@RequestBody Map<String, Object> body) {
        long tid = resolveTenant(body);
        requireOnboarding(tid);
        Map<String, Object> allowed = pick(body, "credit_code", "biz_type", "biz_term_type", "biz_term_end",
                "legal_person", "safety_charge", "charge_phone", "region", "address", "industry",
                "employee_count", "vehicle_scale", "main_route", "main_cargo", "website", "zipcode");
        if (allowed.isEmpty()) throw ErrorCode.PARAM_INVALID.ex("无可更新的企业资料字段");
        // 营业执照上传后标记阶段 0 最后一项完成
        if (body.containsKey("license_file") || body.containsKey("business_license")) {
            markItem(tid, "S0_LICENSE", true, currentUser());
        }
        updateByTenant("tenant_info", tid, allowed);
        jdbc.update("UPDATE `sys_onboarding` SET `step`=GREATEST(`step`,1),`status`='in_progress' WHERE `tenant_id`=?", tid);
        markItem(tid, "S1_STEP1", true, currentUser());
        recomputeStage(tid);
        return Result.ok(statusOf(tid));
    }

    /** 第二步（人档）：批量录入人员；有手机号自动开通小程序账号；显式归档到 PERSON_FILE */
    @PostMapping("/step2/person")
    @OperLog(module = "onboarding", action = "step2_person")
    public Result<Map<String, Object>> step2Person(@RequestBody Map<String, Object> body) {
        long tid = resolveTenant(body);
        requireOnboarding(tid);
        List<Map<String, Object>> rows = rows(body, "persons");
        int created = 0, accounts = 0;
        for (Map<String, Object> r : rows) {
            String name = str(r, "name", null);
            String tel = str(r, "telephone", null);
            if (name == null || tel == null) throw ErrorCode.FIELD_REQUIRED.ex("人员姓名与手机号必填");
            Map<String, Object> row = pick(r, "person_type", "name", "telephone", "gender", "id_card",
                    "avatar", "dept_id", "post_ids", "politic");
            row.put("person_type", str(r, "person_type", "employee"));
            long id = insertByTenant("person", tid, row);
            created++;
            // 铁律 6：服务商代录时显式指定租户，避免归档项挂到平台名下
            registrar.register("person", "PERSON_FILE", id, name + " 人员档案", tid);
            // 阶段 3：录入即开通（账号=手机号，初始密码 123456）
            if (tel.matches("1\\d{10}")) {
                String ut = "manager".equals(str(r, "person_type", "employee")) ? "enterprise" : "driver";
                if (createUserIfAbsent(tid, tel, "123456", ut, name, tel)) accounts++;
            }
        }
        if (created == 0) throw ErrorCode.PARAM_INVALID.ex("persons 为空");
        jdbc.update("UPDATE `sys_onboarding` SET `step`=GREATEST(`step`,2),`status`='in_progress' WHERE `tenant_id`=?", tid);
        markItem(tid, "S1_STEP2", true, currentUser());
        recomputeStage(tid);
        Map<String, Object> out = statusOf(tid);
        out.put("createdPersons", created);
        out.put("openedAccounts", accounts);
        return Result.ok(out);
    }

    /** 第二步（车档）：批量录入车辆，归档到 VEHICLE_FILE */
    @PostMapping("/step2/vehicle")
    @OperLog(module = "onboarding", action = "step2_vehicle")
    public Result<Map<String, Object>> step2Vehicle(@RequestBody Map<String, Object> body) {
        long tid = resolveTenant(body);
        requireOnboarding(tid);
        List<Map<String, Object>> rows = rows(body, "vehicles");
        int created = 0;
        for (Map<String, Object> r : rows) {
            String plate = str(r, "plate_no", null);
            if (plate == null) throw ErrorCode.FIELD_REQUIRED.ex("车牌号必填");
            Map<String, Object> row = pick(r, "plate_no", "plate_color", "biz_type", "vehicle_type",
                    "vehicle_class", "is_trailer", "vehicle_no", "brand_model", "vin", "engine_no",
                    "owner", "reg_date", "license_no", "use_nature", "energy_type", "tech_level");
            row.put("plate_color", str(r, "plate_color", "黄"));
            row.put("biz_type", str(r, "biz_type", "freight"));
            long id = insertByTenant("vehicle", tid, row);
            registrar.register("vehicle", "VEHICLE_FILE", id, plate + " 车辆档案", tid);
            created++;
        }
        if (created == 0) throw ErrorCode.PARAM_INVALID.ex("vehicles 为空");
        Map<String, Object> out = statusOf(tid);
        out.put("createdVehicles", created);
        return Result.ok(out);
    }

    /** 第三步：安全管理现状（选择题 + 开放题，原样存 JSON，驱动阶段 2 整改范围） */
    @PostMapping("/step3")
    @OperLog(module = "onboarding", action = "step3")
    public Result<Map<String, Object>> step3(@RequestBody Map<String, Object> body) {
        long tid = resolveTenant(body);
        requireOnboarding(tid);
        Map<String, Object> payload = body.containsKey("diagnosis") && body.get("diagnosis") instanceof Map
                ? cast(body.get("diagnosis")) : body;
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw ErrorCode.PARAM_INVALID.ex("现状诊断序列化失败");
        }
        jdbc.update("UPDATE `sys_onboarding` SET `step`=3,`diagnosis`=? WHERE `tenant_id`=?", json, tid);
        markItem(tid, "S1_STEP3", true, currentUser());
        markItem(tid, "S1_DIAGNOSIS", true, currentUser());
        recomputeStage(tid);
        return Result.ok(statusOf(tid));
    }

    /** 完成接入：解锁业务功能、进入阶段 2 整改 */
    @PostMapping("/submit")
    @OperLog(module = "onboarding", action = "submit")
    public Result<Map<String, Object>> submit(@RequestBody(required = false) Map<String, Object> body) {
        long tid = resolveTenant(body);
        Map<String, Object> ob = requireOnboarding(tid);
        int step = ((Number) ob.get("step")).intValue();
        if (step < 3) throw ErrorCode.BIZ_STATE_INVALID.ex("三步填报未完成（当前 step=" + step + "）");
        jdbc.update("UPDATE `sys_onboarding` SET `status`='completed',`stage`=2,`completed_at`=? WHERE `tenant_id`=?",
                LocalDateTime.now(), tid);
        for (String code : STEP_ITEMS) markItem(tid, code, true, currentUser());
        return Result.ok(statusOf(tid));
    }

    // ==================================================================
    // 阶段 2~5：交付检查项推进与查询
    // ==================================================================

    /** 勾选/取消检查项（服务商交付或企业自助），自动重算阶段 */
    @PutMapping("/items/{code}")
    @OperLog(module = "onboarding", action = "mark_item")
    public Result<Map<String, Object>> mark(@PathVariable String code,
                                            @RequestBody(required = false) Map<String, Object> body) {
        long tid = resolveTenant(body);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `onboarding_item` WHERE `tenant_id`=? AND `item_code`=? AND `deleted`=0",
                Integer.class, tid, code);
        if (exists == null || exists == 0) throw ErrorCode.BIZ_NOT_FOUND.ex("检查项 " + code);
        boolean done = body == null || !Boolean.FALSE.equals(body.get("done"));
        markItem(tid, code, done, currentUser());
        recomputeStage(tid);
        return Result.ok(statusOf(tid));
    }

    /** 当前企业（或指定企业）的完整接入状态 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam(required = false) Long tenantId) {
        return Result.ok(statusOf(resolveTenantId(tenantId)));
    }

    /** 六阶段检查项字典 */
    @GetMapping("/checklist")
    public Result<List<Map<String, Object>>> checklist() {
        return Result.ok(jdbc.queryForList(
                "SELECT `stage`,`item_code`,`item_name`,`target_route`,`required`,`sort_no`,`remark` " +
                        "FROM `onboarding_checklist` WHERE `deleted`=0 ORDER BY `stage`,`sort_no`"));
    }

    /** 服务商/超管：全部企业接入进度看板 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        requirePlatform();
        return Result.ok(jdbc.queryForList(
                "SELECT t.`id` AS tenant_id, t.`name` AS tenant_name, t.`credit_code`, " +
                        "o.`stage`, o.`step`, o.`status`, o.`biz_type`, o.`contract_end`, o.`completed_at`, " +
                        "(SELECT COUNT(*) FROM `onboarding_item` i WHERE i.`tenant_id`=t.`id` AND i.`done`=1 AND i.`deleted`=0) AS done_count, " +
                        "(SELECT COUNT(*) FROM `onboarding_item` i WHERE i.`tenant_id`=t.`id` AND i.`deleted`=0) AS total_count " +
                        "FROM `sys_tenant` t LEFT JOIN `sys_onboarding` o ON o.`tenant_id`=t.`id` AND o.`deleted`=0 " +
                        "WHERE t.`deleted`=0 ORDER BY t.`id` DESC"));
    }

    /**
     * 阶段 4 归档就位自检（同时供 E4「5 分钟调出完整台账」复测计时）。
     * 校验：档案盒目录齐全、归档项已产生、人车档案、制度归档、检查项完成率。
     */
    @GetMapping("/readiness")
    public Result<Map<String, Object>> readiness(@RequestParam(required = false) Long tenantId) {
        long tid = resolveTenantId(tenantId);
        long start = System.currentTimeMillis();
        Map<String, Object> ob = requireOnboarding(tid);
        String bizType = String.valueOf(ob.getOrDefault("biz_type", "GENERAL"));
        int expectedBoxes = "DANGER".equals(bizType) ? 11 : 10;

        int boxes = count("SELECT COUNT(*) FROM `archive_catalog` WHERE `level`=1 AND `biz_type`=? AND `deleted`=0", bizType);
        int archiveItems = count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? AND `deleted`=0", tid);
        int persons = count("SELECT COUNT(*) FROM `person` WHERE `tenant_id`=? AND `deleted`=0", tid);
        int vehicles = count("SELECT COUNT(*) FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0", tid);
        // 铁律 6 专项：归档项须按业务模块正确挂到本租户，而非平台（tenant_id=0）
        int personArchiveItems = count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? " +
                "AND `biz_module`='person' AND `deleted`=0", tid);
        int vehicleArchiveItems = count("SELECT COUNT(*) FROM `archive_item` WHERE `tenant_id`=? " +
                "AND `biz_module`='vehicle' AND `deleted`=0", tid);
        int institutionItems = count("SELECT COUNT(*) FROM `archive_item` a JOIN `archive_catalog` c " +
                "ON a.`catalog_id`=c.`id` WHERE a.`tenant_id`=? AND a.`deleted`=0 AND c.`code` LIKE '%BOX2%'", tid);
        int doneItems = count("SELECT COUNT(*) FROM `onboarding_item` WHERE `tenant_id`=? AND `done`=1 AND `deleted`=0", tid);
        int totalItems = count("SELECT COUNT(*) FROM `onboarding_item` WHERE `tenant_id`=? AND `deleted`=0", tid);

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("档案盒目录齐全", expectedBoxes, boxes));
        checks.add(check("已产生归档项", 1, archiveItems));
        checks.add(check("人员档案", 1, persons));
        checks.add(check("车辆档案", 1, vehicles));
        checks.add(check("人员档案归档登记（铁律6）", 1, personArchiveItems));
        checks.add(check("车辆档案归档登记（铁律6）", 1, vehicleArchiveItems));
        checks.add(check("制度类归档", 1, institutionItems));
        checks.add(check("接入检查项已推进", 1, doneItems));
        boolean allPass = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tid);
        out.put("bizType", bizType);
        out.put("expectedBoxes", expectedBoxes);
        out.put("archiveItems", archiveItems);
        out.put("persons", persons);
        out.put("vehicles", vehicles);
        out.put("personArchiveItems", personArchiveItems);
        out.put("vehicleArchiveItems", vehicleArchiveItems);
        out.put("checkProgress", doneItems + "/" + totalItems);
        out.put("checks", checks);
        out.put("ready", allPass);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("checkedAt", LocalDateTime.now().toString());
        return Result.ok(out);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private Map<String, Object> statusOf(long tid) {
        Map<String, Object> ob = requireOnboarding(tid);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT c.`stage`,c.`item_code`,c.`item_name`,c.`target_route`,c.`required`,c.`sort_no`, " +
                        "i.`done`,i.`done_at` FROM `onboarding_item` i " +
                        "JOIN `onboarding_checklist` c ON c.`item_code`=i.`item_code` " +
                        "WHERE i.`tenant_id`=? AND i.`deleted`=0 ORDER BY c.`stage`,c.`sort_no`", tid);
        int done = (int) items.stream().filter(i -> intVal(i.get("done")) == 1).count();
        int required = (int) items.stream().filter(i -> intVal(i.get("required")) == 1).count();
        int requiredDone = (int) items.stream()
                .filter(i -> intVal(i.get("required")) == 1 && intVal(i.get("done")) == 1).count();

        Map<String, Object> out = new LinkedHashMap<>(ob);
        out.put("items", items);
        out.put("progress", Map.of("done", done, "total", items.size(),
                "required", required, "requiredDone", requiredDone,
                "percent", items.isEmpty() ? 0 : done * 100 / items.size()));
        String stageName = stageName(intVal(ob.get("stage")));
        out.put("stageName", stageName);
        out.put("locked", !"completed".equals(ob.get("status")) && !"operating".equals(ob.get("status"))
                && intVal(ob.get("step")) < 3);
        return out;
    }

    private String stageName(int stage) {
        return switch (stage) {
            case 0 -> "阶段0 · 签约与开户";
            case 1 -> "阶段1 · 资料采集";
            case 2 -> "阶段2 · 静态资料全面整改";
            case 3 -> "阶段3 · 小程序全员账号开通";
            case 4 -> "阶段4 · 制度与台账归档就位";
            default -> "阶段5 · 日常运营与年审续签";
        };
    }

    /** 阶段 = 第一个仍有必做项未完成的阶段；全部完成则进入阶段 5 运营中 */
    private void recomputeStage(long tid) {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT MIN(c.`stage`) AS s FROM `onboarding_item` i " +
                        "JOIN `onboarding_checklist` c ON c.`item_code`=i.`item_code` " +
                        "WHERE i.`tenant_id`=? AND i.`deleted`=0 AND i.`done`=0 AND c.`required`=1", tid);
        Object min = pending.isEmpty() ? null : pending.get(0).get("s");
        int stage = min == null ? 5 : intVal(min);
        String status = stage == 5 ? "operating"
                : (stage <= 1 ? "in_progress" : "completed");
        jdbc.update("UPDATE `sys_onboarding` SET `stage`=?,`status`=? WHERE `tenant_id`=?", stage, status, tid);
    }

    private void markItem(long tid, String code, boolean done, long uid) {
        jdbc.update("UPDATE `onboarding_item` SET `done`=?,`done_at`=?,`done_by`=? " +
                        "WHERE `tenant_id`=? AND `item_code`=?",
                done ? 1 : 0, done ? LocalDateTime.now() : null, done ? uid : null, tid, code);
    }

    private long createUser(long tid, String username, String rawPassword, String userType,
                            String realName, String phone) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM `sys_user` WHERE `username`=?", Integer.class, username);
        if (exists != null && exists > 0) throw ErrorCode.BIZ_STATE_INVALID.ex("登录账号已存在：" + username);
        long id = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO `sys_user` " +
                        "(`id`,`tenant_id`,`username`,`password`,`real_name`,`phone`,`user_type`,`status`) " +
                        "VALUES (?,?,?,?,?,?,?,1)",
                id, tid, username, passwordEncoder.encode(rawPassword), realName, phone, userType);
        return id;
    }

    private boolean createUserIfAbsent(long tid, String username, String rawPassword, String userType,
                                       String realName, String phone) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM `sys_user` WHERE `username`=?", Integer.class, username);
        if (exists != null && exists > 0) return false;
        jdbc.update("INSERT INTO `sys_user` " +
                        "(`id`,`tenant_id`,`username`,`password`,`real_name`,`phone`,`user_type`,`status`) " +
                        "VALUES (?,?,?,?,?,?,?,1)",
                IdUtil.getSnowflakeNextId(), tid, username, passwordEncoder.encode(rawPassword), realName, phone, userType);
        return true;
    }

    /** 通用插入：只接受白名单字段，表名来自内部常量（不接收用户输入） */
    private long insertByTenant(String table, long tid, Map<String, Object> row) {
        Map<String, Object> data = new LinkedHashMap<>(row);
        long id = IdUtil.getSnowflakeNextId();
        data.put("id", id);
        data.put("tenant_id", tid);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());
        data.put("created_by", currentUser());
        String cols = data.keySet().stream().map(c -> "`" + c + "`").reduce((a, b) -> a + "," + b).orElse("");
        String marks = String.join(",", Collections.nCopies(data.size(), "?"));
        jdbc.update("INSERT INTO `" + table + "` (" + cols + ") VALUES (" + marks + ")", data.values().toArray());
        return id;
    }

    private void updateByTenant(String table, long tid, Map<String, Object> row) {
        if (row.isEmpty()) return;
        String sets = row.keySet().stream().map(c -> "`" + c + "`=?").reduce((a, b) -> a + "," + b).orElse("");
        List<Object> args = new ArrayList<>(row.values());
        args.add(LocalDateTime.now());
        args.add(tid);
        jdbc.update("UPDATE `" + table + "` SET " + sets + ",`updated_at`=? WHERE `tenant_id`=?", args.toArray());
    }

    private Map<String, Object> requireOnboarding(long tid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `sys_onboarding` WHERE `tenant_id`=? AND `deleted`=0", tid);
        if (rows.isEmpty()) throw ErrorCode.BIZ_NOT_FOUND.ex("企业接入记录（tenant_id=" + tid + "）");
        return rows.get(0);
    }

    /** 平台账号必须显式指定 tenant_id；企业账号只能操作自己 */
    private long resolveTenant(Map<String, Object> body) {
        Long explicit = body == null ? null : longOrNull(body.get("tenant_id"));
        return resolveTenantId(explicit);
    }

    private long resolveTenantId(Long explicit) {
        if (TenantContextHolder.isPlatform()) {
            if (explicit == null || explicit == 0L) {
                throw ErrorCode.PARAM_INVALID.ex("平台账号需指定 tenant_id");
            }
            return explicit;
        }
        Long ctx = TenantContextHolder.tenantId();
        if (ctx == null || ctx == 0L) throw ErrorCode.FORBIDDEN.ex();
        return ctx;
    }

    private void requirePlatform() {
        if (!TenantContextHolder.isPlatform()) throw ErrorCode.FORBIDDEN.ex("仅服务商/超管可操作");
    }

    private Map<String, Object> check(String name, int expected, int actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("expectedAtLeast", expected);
        m.put("actual", actual);
        m.put("pass", actual >= expected);
        return m;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long currentUser() {
        Long uid = TenantContextHolder.userId();
        return uid == null ? 0L : uid;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        if (m == null) return def;
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static String requiredStr(Map<String, Object> m, String key) {
        String v = str(m, key, null);
        if (v == null || v.isBlank()) throw ErrorCode.FIELD_REQUIRED.ex(key);
        return v;
    }

    private static Map<String, Object> pick(Map<String, Object> src, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (src.containsKey(k) && src.get(k) != null) out.put(k, src.get(k));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        // 也支持单条对象直接提交
        return v instanceof Map<?, ?> ? List.of(cast(v)) : Collections.emptyList();
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intVal(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
