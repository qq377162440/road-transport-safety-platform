package com.safetrans.tpl.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 岗位人名解析器 —— 只在「电子档案柜生成成品」时把真实姓名填进台账。
 *
 * <p>数据来源（按优先级）：
 * <ol>
 *   <li>org_member（人员-岗位分配）→ person.name</li>
 *   <li>person.post_ids（人员直接挂岗位）→ person.name</li>
 * </ol>
 * 两者都读不到时，不静默留白，而是返回 {@link Missing} 清单，由前端提醒用户补录。
 *
 * <p>「台账范本管理」是模板层，完全不经过本解析器，因此不会出现任何真实人名。
 */
@Service
public class TplPersonResolver {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");

    /** @current = 当前生成所选岗位；@staff = 全体在岗人员名单 */
    private static final Map<String, String> VAR_TO_POST = Map.ofEntries(
            Map.entry("person_name", "@current"),
            Map.entry("姓名", "@current"),
            Map.entry("issuer", "gm"),
            Map.entry("leader_name", "gm"),
            Map.entry("主要负责人", "gm"),
            Map.entry("副组长", "safe"),
            Map.entry("授课人", "safe"),
            Map.entry("安全管理员", "safe"),
            Map.entry("监控员", "mon"),
            Map.entry("车队长", "captain"),
            Map.entry("成员名单", "@staff"));

    /** 提醒文案里的字段中文名 */
    private static final Map<String, String> VAR_LABEL = Map.ofEntries(
            Map.entry("person_name", "责任人姓名"),
            Map.entry("姓名", "责任人姓名"),
            Map.entry("issuer", "签发人"),
            Map.entry("leader_name", "主要负责人"),
            Map.entry("主要负责人", "主要负责人"),
            Map.entry("副组长", "安全生产领导小组副组长"),
            Map.entry("授课人", "培训授课人"),
            Map.entry("安全管理员", "安全管理员"),
            Map.entry("监控员", "监控员"),
            Map.entry("车队长", "车队长"),
            Map.entry("成员名单", "领导小组成员名单"));

    /** 原型 / 责任书里的岗位名 → org_post.func_key（企业可改 post_name，故按职能匹配） */
    private static final Map<String, String> POST_ALIAS = Map.ofEntries(
            Map.entry("主要负责人", "gm"), Map.entry("总经理", "gm"),
            Map.entry("法定代表人", "gm"), Map.entry("企业负责人", "gm"),
            Map.entry("驾驶员", "driver"), Map.entry("安全员", "safe"),
            Map.entry("安环主管", "safe"), Map.entry("安全管理员", "safe"),
            Map.entry("监控员", "mon"), Map.entry("车队长", "captain"),
            Map.entry("运营调度员", "disp"), Map.entry("调度员", "disp"),
            Map.entry("资料员", "clerk"), Map.entry("财务", "finance"),
            Map.entry("车辆技术管理员", "vehtech"), Map.entry("停车场管理员", "parklot"),
            Map.entry("押运员", "escort"), Map.entry("装卸管理员", "loader"));

    private final JdbcTemplate jdbc;

    public TplPersonResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return t == null ? 0L : t;
    }

    /** 岗位快照：id / 名称 / 职能 */
    private record PostRow(long id, String name, String funcKey) {
    }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    private static long lng(Map<String, Object> m, String... keys) {
        String s = str(m, keys);
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private List<PostRow> posts() {
        List<PostRow> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, post_name, func_key FROM org_post WHERE tenant_id=? AND deleted=0 AND enabled=1", tid())) {
            out.add(new PostRow(lng(r, "id"), str(r, "post_name", "postName"), str(r, "func_key", "funcKey")));
        }
        return out;
    }

    /** postId → 人名列表（org_member 优先，person.post_ids 兜底） */
    private Map<Long, List<String>> namesByPost() {
        Map<Long, String> personNames = new HashMap<>();
        Map<Long, Set<Long>> personPosts = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, name, post_ids FROM person WHERE tenant_id=? AND deleted=0", tid())) {
            long id = lng(r, "id");
            String name = str(r, "name");
            if (id == 0 || name.isEmpty()) continue;
            personNames.put(id, name);
            String raw = str(r, "post_ids", "postIds");
            if (!raw.isEmpty()) {
                Set<Long> ids = new LinkedHashSet<>();
                for (String s : raw.split("[,，\\s]+")) {
                    try {
                        if (!s.isBlank()) ids.add(Long.parseLong(s.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                personPosts.put(id, ids);
            }
        }

        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT user_id, post_id FROM org_member WHERE tenant_id=? AND deleted=0", tid())) {
            String name = personNames.get(lng(r, "user_id", "userId"));
            if (name == null) continue;
            out.computeIfAbsent(lng(r, "post_id", "postId"), k -> new ArrayList<>()).add(name);
        }
        for (Map.Entry<Long, Set<Long>> e : personPosts.entrySet()) {
            String name = personNames.get(e.getKey());
            if (name == null) continue;
            for (Long pid : e.getValue()) {
                List<String> l = out.computeIfAbsent(pid, k -> new ArrayList<>());
                if (!l.contains(name)) l.add(name);
            }
        }
        return out;
    }

    /** 原型岗位名 / 变量名 → org_post.func_key */
    private String funcKeyOf(String post, List<PostRow> posts) {
        String p = post == null ? "" : post.trim();
        if (p.isEmpty()) return "gm";
        String mapped = POST_ALIAS.get(p);
        if (mapped != null) return mapped;
        for (PostRow r : posts) {
            if (p.equals(r.funcKey()) || p.equals(r.name())) return r.funcKey();
        }
        return p;
    }

    /** 解析某岗位的人名；@staff 返回全体在岗人员 */
    private List<String> namesFor(String slot, String post) {
        List<PostRow> posts = posts();
        Map<Long, List<String>> byPost = namesByPost();
        if ("@staff".equals(slot)) {
            List<String> all = new ArrayList<>();
            for (PostRow r : posts) {
                for (String n : byPost.getOrDefault(r.id(), List.of())) if (!all.contains(n)) all.add(n);
            }
            return all;
        }
        String key = "@current".equals(slot) ? funcKeyOf(post, posts) : slot;
        List<String> names = new ArrayList<>();
        for (PostRow r : posts) {
            if (r.funcKey().equalsIgnoreCase(key) || r.name().equals(key)) {
                for (String n : byPost.getOrDefault(r.id(), List.of())) if (!names.contains(n)) names.add(n);
            }
        }
        return names;
    }

    /** 范本正文里实际用到的人名变量（保持出现顺序） */
    private List<String> personVarsIn(String content) {
        List<String> out = new ArrayList<>();
        Matcher m = VAR.matcher(content == null ? "" : content);
        while (m.find()) {
            String v = m.group(1).trim();
            if (VAR_TO_POST.containsKey(v) && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** 缺失项：供前端黄色提醒条与生成前确认框使用 */
    @Data
    public static class Missing {
        private String var;
        private String label;
        private String post;
    }

    private Missing missing(String var, String post) {
        Missing m = new Missing();
        m.setVar(var);
        m.setLabel(VAR_LABEL.getOrDefault(var, var));
        m.setPost(post);
        return m;
    }

    /**
     * 只检查不改写：返回该范本在当前岗位下读不到人名的人员字段。
     * content 建议先经过条件段解析，避免未命中分支的变量被误报。
     */
    public List<Missing> check(String content, String post) {
        List<Missing> out = new ArrayList<>();
        for (String v : personVarsIn(content)) {
            List<String> names = namesFor(VAR_TO_POST.get(v), post);
            if (names.isEmpty()) out.add(missing(v, post));
        }
        return out;
    }

    /**
     * 成品渲染前调用：把读到的真实人名写入变量表；读不到的字段留空栏，并回传缺失清单。
     */
    public List<Missing> apply(Map<String, String> vars, String content, String post) {
        List<Missing> out = new ArrayList<>();
        for (String v : personVarsIn(content)) {
            List<String> names = namesFor(VAR_TO_POST.get(v), post);
            if (names.isEmpty()) {
                vars.put(v, TplRenderEngine.BLANK);
                out.add(missing(v, post));
            } else {
                vars.put(v, String.join("、", names));
            }
        }
        return out;
    }

    /** 补录：为岗位绑定人名（若同名人员不存在则先建一条人员记录），让读取链路闭环 */
    @Transactional
    public String assign(String post, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new BizException("请填写人员姓名");
        List<PostRow> posts = posts();
        String key = funcKeyOf(post, posts);
        PostRow target = posts.stream()
                .filter(r -> r.funcKey().equalsIgnoreCase(key) || r.name().equals(key))
                .findFirst().orElse(null);
        if (target == null) throw new BizException("未找到岗位：" + post);

        long t = tid();
        Long personId = null;
        List<Map<String, Object>> exists = jdbc.queryForList(
                "SELECT id FROM person WHERE tenant_id=? AND deleted=0 AND name=? LIMIT 1", t, n);
        if (!exists.isEmpty()) personId = lng(exists.get(0), "id");
        if (personId == null || personId == 0) {
            personId = IdWorker.getId();
            jdbc.update("INSERT INTO person (id, tenant_id, person_type, name, telephone, created_by, deleted) "
                    + "VALUES (?,?,?,?,?,?,0)", personId, t, "manager", n, "", 0L);
        }
        List<Map<String, Object>> bound = jdbc.queryForList(
                "SELECT id FROM org_member WHERE tenant_id=? AND deleted=0 AND post_id=? AND user_id=? LIMIT 1",
                t, target.id(), personId);
        if (bound.isEmpty()) {
            jdbc.update("INSERT INTO org_member (id, tenant_id, user_id, post_id, created_by, deleted) "
                    + "VALUES (?,?,?,?,?,0)", IdWorker.getId(), t, personId, target.id(), 0L);
        }
        return n + " → " + target.name();
    }

    /** 供前端补录弹窗展示：当前企业启用岗位 + 已绑定人名 */
    public List<Map<String, Object>> roster() {
        Map<Long, List<String>> byPost = namesByPost();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PostRow r : posts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("post", r.name());
            m.put("funcKey", r.funcKey());
            m.put("names", String.join("、", byPost.getOrDefault(r.id(), List.of())));
            out.add(m);
        }
        return out;
    }
}
