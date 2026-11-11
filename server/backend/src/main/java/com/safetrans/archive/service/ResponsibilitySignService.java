package com.safetrans.archive.service;

import cn.hutool.core.util.IdUtil;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tenant.service.TenantService;
import com.safetrans.tpl.service.TplLibraryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 安全生产责任书「上下级双签」状态机（用户 2026-11-11 需求 ②）。
 *
 * <p>设计依据《09-静态资料范本引擎》§九：
 * <ul>
 *   <li>责任人（下级）＝ 组织架构启用岗位上的当前人员；</li>
 *   <li>责任管理人（上级）＝ <b>组织架构里该岗位的直接上级</b>（沿 org_post.parent_id
 *       读取，跳过禁用 / 无人的岗位后上提到最近可用祖先），绝不写死岗位名；</li>
 *   <li>最顶层主要负责人无人员上级 → <b>与公司签</b>（公司盖章 + 本人签字）；</li>
 *   <li>只有 <b>上级与下级两个人都签署</b> 才生效，生效时才生成带双方签名的 PDF 并归档；</li>
 *   <li>组织架构变化后，受影响的签订关系自动作废并生成新版本待签（历史 PDF 天然留存）。</li>
 * </ul>
 *
 * <p>状态：pending 待签 → partial 单方已签（未生效）→ effective 双方签齐（生效）→ void 作废。
 */
@Service
public class ResponsibilitySignService {

    private static final String RESP_TPL = "RESP_LETTER";
    private static final String ITEM_NAME = "安全生产责任书";
    private static final int BOX_NO = 1;

    /**
     * 组织架构职能 → RESP_LETTER 范本条件段识别的岗位名。
     * 范本按岗位整段替换职责条款，未设专用分支的管理岗沿用「安全员」通用条款；
     * 真实岗位名在 overrides 里覆盖回 post，保证签字栏显示的是架构里的实际岗位。
     */
    private static final Map<String, String> RENDER_POST = Map.ofEntries(
            Map.entry("gm", "主要负责人"),
            Map.entry("safe", "安全员"),
            Map.entry("mon", "监控员"),
            Map.entry("finance", "行政财务"),
            Map.entry("driver", "驾驶员"),
            Map.entry("escort", "驾驶员"),
            Map.entry("loader", "驾驶员"));

    private static final String DEFAULT_RENDER_POST = "安全员";

    private static final String SELECT_ONE =
            "SELECT * FROM `responsibility_sign` WHERE `id`=? AND `tenant_id`=? AND `deleted`=0";

    private final JdbcTemplate jdbc;
    private final ArchiveService archiveService;
    private final TplLibraryService tplLibraryService;
    private final TenantService tenantService;

    public ResponsibilitySignService(JdbcTemplate jdbc, ArchiveService archiveService,
                                     TplLibraryService tplLibraryService, TenantService tenantService) {
        this.jdbc = jdbc;
        this.archiveService = archiveService;
        this.tplLibraryService = tplLibraryService;
        this.tenantService = tenantService;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ==================================================================
    // 1. 从安全管理架构计算上下级签订关系
    // ==================================================================

    /**
     * 当前架构下所有「启用岗位 + 已绑定人员」的签订关系。
     * 上级沿 org_post.parent_id 上提；parent_id=0 或链上找不到可用祖先 → 与公司签。
     */
    private List<Map<String, Object>> relations() {
        long t = tid();
        Map<Long, Map<String, Object>> posts = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `id`,`post_name`,`func_key`,`parent_id`,`enabled` FROM `org_post` " +
                "WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t)) {
            posts.put(num(r.get("id")), r);
        }
        Map<Long, List<Map<String, Object>>> members = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT m.`post_id`, p.`id` AS `person_id`, p.`name` AS `person_name` " +
                "FROM `org_member` m JOIN `person` p ON p.`id`=m.`user_id` AND p.`deleted`=0 " +
                "WHERE m.`tenant_id`=? AND m.`deleted`=0 ORDER BY m.`post_id`, p.`id`", t)) {
            members.computeIfAbsent(num(r.get("post_id")), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> e : posts.entrySet()) {
            long postId = e.getKey();
            Map<String, Object> po = e.getValue();
            if (!"1".equals(s(po.get("enabled")))) continue;
            List<Map<String, Object>> persons = members.get(postId);
            if (persons == null || persons.isEmpty()) continue;

            // 上级岗位：沿 parent_id 逐级上提，跳过禁用岗位与未配人员的岗位
            long parentId = num(po.get("parent_id"));
            Map<String, Object> supPost = null;
            List<Map<String, Object>> supPersons = null;
            Set<Long> visited = new HashSet<>();
            while (parentId > 0 && visited.add(parentId)) {
                Map<String, Object> cand = posts.get(parentId);
                if (cand == null) break;
                List<Map<String, Object>> cm = members.get(parentId);
                if ("1".equals(s(cand.get("enabled"))) && cm != null && !cm.isEmpty()) {
                    supPost = cand;
                    supPersons = cm;
                    break;
                }
                parentId = num(cand.get("parent_id"));
            }

            for (Map<String, Object> person : persons) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subPersonId", num(person.get("person_id")));
                row.put("subName", s(person.get("person_name")));
                row.put("subPostId", postId);
                row.put("subPostName", s(po.get("post_name")));
                row.put("subFuncKey", s(po.get("func_key")));
                if (supPost == null) {
                    row.put("supType", "company");
                    row.put("supPersonId", null);
                    row.put("supName", tenantService.currentTenantName());
                    row.put("supPostId", null);
                    row.put("supPostName", "公司");
                    row.put("supFuncKey", "");
                } else {
                    Map<String, Object> sup = supPersons.get(0);
                    row.put("supType", "person");
                    row.put("supPersonId", num(sup.get("person_id")));
                    row.put("supName", s(sup.get("person_name")));
                    row.put("supPostId", num(supPost.get("id")));
                    row.put("supPostName", s(supPost.get("post_name")));
                    row.put("supFuncKey", s(supPost.get("func_key")));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static boolean sameRelation(Map<String, Object> db, Map<String, Object> now) {
        return s(db.get("sup_type")).equals(s(now.get("supType")))
                && s(db.get("sub_post_id")).equals(String.valueOf(num(now.get("subPostId"))))
                && s(db.get("sub_name")).equals(s(now.get("subName")))
                && s(db.get("sup_name")).equals(s(now.get("supName")))
                && s(db.get("sup_post_id")).equals(now.get("supPostId") == null
                        ? "" : String.valueOf(num(now.get("supPostId"))))
                && s(db.get("sub_post_name")).equals(s(now.get("subPostName")))
                && s(db.get("sup_post_name")).equals(s(now.get("supPostName")));
    }

    // ==================================================================
    // 2. 关系同步（幂等）—— 架构变化即作废重签
    // ==================================================================

    /**
     * 按当前架构重算本年度签订关系。关系未变的记录原样保留；变化或人员离岗的记录置 void，
     * 并插入 revision+1 的 pending 新记录。已生效 PDF 留在 archive_pdf，可作历史版本下载。
     */
    @Transactional
    public Map<String, Object> sync(int year) {
        long t = tid();
        List<Map<String, Object>> rel = relations();
        Set<Long> active = new HashSet<>();
        int created = 0, voided = 0, unchanged = 0;

        for (Map<String, Object> r : rel) {
            long subId = num(r.get("subPersonId"));
            active.add(subId);
            List<Map<String, Object>> curList = jdbc.queryForList(
                    "SELECT * FROM `responsibility_sign` WHERE `tenant_id`=? AND `sign_year`=? " +
                    "AND `sub_person_id`=? AND `deleted`=0 AND `status`<>'void' " +
                    "ORDER BY `revision` DESC, `id` DESC LIMIT 1", t, year, subId);
            Map<String, Object> cur = curList.isEmpty() ? null : curList.get(0);
            if (cur != null && !sameRelation(cur, r)) {
                jdbc.update("UPDATE `responsibility_sign` SET `status`='void',`updated_at`=NOW() " +
                        "WHERE `id`=?", num(cur.get("id")));
                voided++;
                cur = null;
            }
            if (cur == null) {
                insertRecord(t, year, r);
                created++;
            } else {
                unchanged++;
            }
        }

        // 架构中已不存在的责任人：本年度未作废记录一并作废
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT `id`,`sub_person_id` FROM `responsibility_sign` " +
                "WHERE `tenant_id`=? AND `sign_year`=? AND `deleted`=0 AND `status`<>'void'", t, year)) {
            if (!active.contains(num(r.get("sub_person_id")))) {
                jdbc.update("UPDATE `responsibility_sign` SET `status`='void',`updated_at`=NOW() " +
                        "WHERE `id`=?", num(r.get("id")));
                voided++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("total", rel.size());
        out.put("created", created);
        out.put("voided", voided);
        out.put("unchanged", unchanged);
        return out;
    }

    private void insertRecord(long t, int year, Map<String, Object> r) {
        Integer max = jdbc.queryForObject(
                "SELECT IFNULL(MAX(`revision`),0) FROM `responsibility_sign` " +
                "WHERE `tenant_id`=? AND `sign_year`=? AND `sub_person_id`=?",
                Integer.class, t, year, num(r.get("subPersonId")));
        int revision = (max == null ? 0 : max) + 1;
        jdbc.update(
                "INSERT INTO `responsibility_sign` " +
                "(`id`,`tenant_id`,`sign_year`,`sub_person_id`,`sub_name`,`sub_post_id`,`sub_post_name`,`sub_func_key`," +
                " `sup_type`,`sup_person_id`,`sup_name`,`sup_post_id`,`sup_post_name`,`sup_func_key`," +
                " `sup_signed`,`sub_signed`,`status`,`revision`,`created_at`,`updated_at`,`created_by`,`deleted`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,'pending',?,NOW(),NOW(),0,0)",
                IdUtil.getSnowflakeNextId(), t, year,
                num(r.get("subPersonId")), s(r.get("subName")), num(r.get("subPostId")),
                s(r.get("subPostName")), s(r.get("subFuncKey")),
                s(r.get("supType")), r.get("supPersonId"), s(r.get("supName")),
                r.get("supPostId"), s(r.get("supPostName")), s(r.get("supFuncKey")), revision);
    }

    // ==================================================================
    // 3. 查询
    // ==================================================================

    /** 责任书签订总览：先同步架构关系，再返回本年度全部待签/生效记录。 */
    @Transactional
    public Map<String, Object> overview(int year) {
        Map<String, Object> syncResult = sync(year);
        List<Map<String, Object>> rows = new ArrayList<>();
        int effective = 0, partial = 0, pending = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT * FROM `responsibility_sign` WHERE `tenant_id`=? AND `sign_year`=? " +
                "AND `deleted`=0 AND `status`<>'void' " +
                "ORDER BY `sup_type` DESC, `sub_post_id`, `sub_person_id`", tid(), year)) {
            rows.add(toView(r));
            switch (s(r.get("status"))) {
                case "effective" -> effective++;
                case "partial" -> partial++;
                default -> pending++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("company", tenantService.currentTenantName());
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("effective", effective);
        out.put("partial", partial);
        out.put("pending", pending);
        out.put("sync", syncResult);
        return out;
    }

    /** 某责任人的全部历史版本（含已作废），供前端查看历史签署件。 */
    public List<Map<String, Object>> history(long subPersonId, int year) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT * FROM `responsibility_sign` WHERE `tenant_id`=? AND `sign_year`=? " +
                "AND `sub_person_id`=? AND `deleted`=0 ORDER BY `revision` DESC, `id` DESC",
                tid(), year, subPersonId)) {
            out.add(toView(r));
        }
        return out;
    }

    private Map<String, Object> toView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", num(r.get("id")));
        m.put("year", r.get("sign_year"));
        m.put("subPersonId", num(r.get("sub_person_id")));
        m.put("subName", s(r.get("sub_name")));
        m.put("subPostId", r.get("sub_post_id"));
        m.put("subPostName", s(r.get("sub_post_name")));
        m.put("supType", s(r.get("sup_type")));
        m.put("supPersonId", r.get("sup_person_id"));
        m.put("supName", s(r.get("sup_name")));
        m.put("supPostName", s(r.get("sup_post_name")));
        m.put("supSigned", "1".equals(s(r.get("sup_signed"))));
        m.put("supSignAt", r.get("sup_sign_at"));
        m.put("subSigned", "1".equals(s(r.get("sub_signed"))));
        m.put("subSignAt", r.get("sub_sign_at"));
        m.put("status", s(r.get("status")));
        m.put("statusLabel", statusLabel(s(r.get("status"))));
        m.put("revision", r.get("revision"));
        m.put("effectiveAt", r.get("effective_at"));
        m.put("pdfId", r.get("pdf_id"));
        m.put("signRelation", "company".equals(s(r.get("sup_type")))
                ? "公司与 " + s(r.get("sub_post_name")) + " " + s(r.get("sub_name")) + " 签订"
                : s(r.get("sup_post_name")) + " " + s(r.get("sup_name"))
                        + " → " + s(r.get("sub_post_name")) + " " + s(r.get("sub_name")));
        return m;
    }

    private static String statusLabel(String st) {
        return switch (st) {
            case "effective" -> "已生效（双方已签）";
            case "partial" -> "待生效（单方已签）";
            case "void" -> "已作废（需重签）";
            default -> "待签订";
        };
    }

    // ==================================================================
    // 4. 签署与生效
    // ==================================================================

    /**
     * 单方签署。side=sup 为上级/公司，side=sub 为下级本人。
     * 只有 sup_signed=1 且 sub_signed=1 才置 effective、写入 effective_at 并生成生效 PDF。
     */
    @Transactional
    public Map<String, Object> sign(long id, String side, String signName) {
        if (!"sup".equals(side) && !"sub".equals(side)) {
            throw new BizException("签署方只能是 sup（上级/公司）或 sub（下级本人）");
        }
        long t = tid();
        Map<String, Object> rec = mustGet(id, t);
        String status = s(rec.get("status"));
        if ("void".equals(status)) throw new BizException("该责任书已作废，请按新版本重新签订");
        if ("effective".equals(status)) throw new BizException("该责任书已生效，无需重复签署");

        boolean isSup = "sup".equals(side);
        String defaultName = isSup ? s(rec.get("sup_name")) : s(rec.get("sub_name"));
        String name = signName == null || signName.isBlank() ? defaultName : signName.trim();
        String text = esc(name) + "（已电子签署 " + LocalDate.now() + "）";
        if (isSup) {
            jdbc.update("UPDATE `responsibility_sign` SET `sup_signed`=1,`sup_sign_at`=NOW()," +
                    "`sup_sign_image`=?,`updated_at`=NOW() WHERE `id`=?", text, id);
        } else {
            jdbc.update("UPDATE `responsibility_sign` SET `sub_signed`=1,`sub_sign_at`=NOW()," +
                    "`sub_sign_image`=?,`updated_at`=NOW() WHERE `id`=?", text, id);
        }

        rec = mustGet(id, t);
        boolean both = "1".equals(s(rec.get("sup_signed"))) && "1".equals(s(rec.get("sub_signed")));
        if (both) {
            int year = ((Number) rec.get("sign_year")).intValue();
            long subPersonId = num(rec.get("sub_person_id"));
            byte[] bytes = renderEffective(rec, year);
            ArchivePdf pdf = archiveService.generateByBytes(
                    "安全生产责任书-" + s(rec.get("sub_name")), BOX_NO, year, bytes,
                    "RESP_LETTER_SIGN", subPersonId, "responsibility_sign");
            jdbc.update("UPDATE `responsibility_sign` SET `status`='effective',`effective_at`=NOW()," +
                    "`pdf_id`=?,`updated_at`=NOW() WHERE `id`=?", pdf.getId(), id);
            // 联动：生效的责任书 PDF 落到一人一档 P05，供管理人员/从业人员档案点击预览
            upsertPersonItem(subPersonId, "P05", pdf.getFilePath(), "sign");
        } else {
            jdbc.update("UPDATE `responsibility_sign` SET `status`='partial',`updated_at`=NOW() " +
                    "WHERE `id`=?", id);
        }
        return toView(mustGet(id, t));
    }

    private Map<String, Object> mustGet(long id, long t) {
        List<Map<String, Object>> list = jdbc.queryForList(SELECT_ONE, id, t);
        if (list.isEmpty()) throw new BizException("责任书签订记录不存在：" + id);
        return list.get(0);
    }

    /** 生效的责任书 PDF 落到一人一档 P05（file_url = MinIO 对象名），供点击预览。 */
    private void upsertPersonItem(long personId, String itemCode, String fileUrl, String sourceType) {
        long t = tid();
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? " +
                "AND `tenant_id`=? AND `deleted`=0", Long.class, personId, itemCode, t);
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO `person_archive_item` " +
                    "(`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`," +
                    " `status`,`created_at`,`created_by`,`deleted`) " +
                    "VALUES (?,?,?,?,?,NULL,?,NULL,1,NOW(),0,0)",
                    IdUtil.getSnowflakeNextId(), t, personId, itemCode, sourceType, fileUrl);
        } else {
            jdbc.update("UPDATE `person_archive_item` SET `file_url`=?, `source_type`=?, `status`=1, " +
                    "`updated_at`=NOW() WHERE `id`=?", fileUrl, sourceType, ids.get(0));
        }
    }

    /**
     * 生效件：复用 RESP_LETTER 变量化范本与统一 A4 版式、公司章，
     * 用 overrides 把签字栏换成真实的上下级电子签名与签署关系说明。
     */
    private byte[] renderEffective(Map<String, Object> rec, int year) {
        String realPost = s(rec.get("sub_post_name"));
        String func = s(rec.get("sub_func_key"));
        String renderPost = RENDER_POST.getOrDefault(func, DEFAULT_RENDER_POST);
        boolean company = "company".equals(s(rec.get("sup_type")));

        Map<String, String> ov = new LinkedHashMap<>();
        // resolveConditions 已按 renderPost 解析完条件段；这里把 post 覆盖回架构里的真实岗位名，
        // 使签字栏「责任人」显示实际岗位（如「总经理」「运营调度员」），职责条款仍用范本对应分支。
        ov.put("post", esc(realPost));
        ov.put("person_name", esc(s(rec.get("sub_name"))));
        ov.put("manager_post", esc(company ? s(rec.get("sup_name")) + "（公司）"
                : s(rec.get("sup_post_name")) + " " + s(rec.get("sup_name"))));
        ov.put("sign_image", s(rec.get("sub_sign_image")));
        ov.put("manager_sign_image", s(rec.get("sup_sign_image")));
        ov.put("sign_relation_intro", company
                ? "依据《中华人民共和国安全生产法》及公司《安全生产责任制》，" + esc(realPost)
                        + " " + esc(s(rec.get("sub_name")))
                        + " 作为公司安全管理架构最顶层主要负责人，与公司签订本责任书"
                : "依据公司《安全生产责任制》及安全管理架构的上下级汇报关系，由"
                        + esc(s(rec.get("sup_post_name"))) + " " + esc(s(rec.get("sup_name")))
                        + "（上级）与 " + esc(realPost) + " " + esc(s(rec.get("sub_name")))
                        + "（下级）共同签订本责任书");
        return tplLibraryService.renderPdf(RESP_TPL, ITEM_NAME, year, renderPost, ov);
    }

    /** 组织架构变化后由 OrgChartController 调用：返回作废/新建数量供前端提示。 */
    public Map<String, Object> onOrgChanged(Integer year) {
        int y = year == null ? Year.now().getValue() : year;
        return sync(y);
    }
}
