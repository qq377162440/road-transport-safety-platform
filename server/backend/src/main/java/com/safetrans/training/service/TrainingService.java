package com.safetrans.training.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 岗中「线上学习（看视频 + 做考核）」闭环服务。
 *
 * <p>两层数据：
 * <ul>
 *   <li><b>平台级课程库</b>（tenant_id=0）：training_course / training_video /
 *       training_paper / training_question / training_course_video，服务商下发、企业只读；</li>
 *   <li><b>企业级学习数据</b>（tenant_id=当前企业）：training_plan / training_assign /
 *       training_exam，PC 下发、小程序学习、PC 统计。</li>
 * </ul>
 *
 * <p>PC 端负责「下发数据」（课程 / 卷子 / 月度计划 / 绑定人员），
 * 小程序端负责「看和做」（在线播放并上报进度 → 在线考核自动判分），
 * 两端通过 training_assign 的观看进度与 training_exam 的考核结果联动。
 */
@Service
public class TrainingService {

    private final JdbcTemplate jdbc;

    public TrainingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 0L : t;
    }

    /** 当前企业 id；平台账号用演示企业兜底，保证课程库与统计有数据可测 */
    private long entId() {
        long t = tid();
        return t == 0L ? 5001L : t;
    }

    private long uid() {
        Long u = TenantContextHolder.userId();
        return u == null ? 0L : u;
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int i(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static long l(Object o) {
        if (o == null) return 0L;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> list = jdbc.queryForList(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    private String json(Object o) {
        return JSONUtil.toJsonStr(o);
    }

    // =====================================================================
    // 一、字典 & 视频库（平台级只读）
    // =====================================================================

    /** 课程分类 / 行业分类 / 岗位分类 / 来源 / 使用范围（对齐原型 course_data.js 字典） */
    public Map<String, Object> dict() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("categories", List.of("安全管理知识", "警示教育案例", "法律法规", "职业道德和生理健康",
                "道路运输业务知识", "应急救援处置", "安全操作规程", "安全行车规范"));
        d.put("industries", List.of("危险货物运输", "普通货物运输", "道路旅客运输", "出租客运", "公交客运"));
        d.put("posts", List.of("管理人员", "驾驶员", "押运员", "装卸人员", "动态监控人员"));
        d.put("sources", List.of("服务商", "平台"));
        d.put("scopes", List.of("全部", "会员"));
        d.put("questionTypes", List.of(Map.of("key", "single", "label", "单选"),
                Map.of("key", "multi", "label", "多选"), Map.of("key", "judge", "label", "判断")));
        return d;
    }

    public List<Map<String, Object>> videos() {
        return jdbc.queryForList(
                "SELECT id, video_name, video_desc, video_url, duration, duration_sec, cargo_type " +
                        "FROM training_video WHERE tenant_id=0 AND deleted=0 ORDER BY id");
    }

    // =====================================================================
    // 二、PC：课程库（对齐原型 pc/video_exam.html）
    // =====================================================================

    /** 课程列表：分类可多选、行业/岗位筛选、分页 */
    public Map<String, Object> courses(Map<String, Object> q) {
        String kw = s(q.get("kw")).trim();
        String industry = s(q.get("industry")).trim();
        String post = s(q.get("post")).trim();
        String cats = s(q.get("categories")).trim();
        int page = Math.max(1, i(q.get("page"), 1));
        int size = Math.min(50, Math.max(1, i(q.get("pageSize"), 10)));

        // 用独立的前缀：列表 SQL 带 JOIN（p/cv），必须限定别名避免 tenant_id / deleted 歧义
        StringBuilder w = new StringBuilder(" WHERE c.tenant_id=0 AND c.deleted=0 ");
        List<Object> args = new ArrayList<>();
        if (!kw.isEmpty()) { w.append(" AND c.course_name LIKE ? "); args.add("%" + kw + "%"); }
        if (!industry.isEmpty()) { w.append(" AND c.industry=? "); args.add(industry); }
        if (!post.isEmpty()) { w.append(" AND FIND_IN_SET(?, c.post)>0 "); args.add(post); }
        if (!cats.isEmpty()) {
            for (String c : cats.split(",")) {
                if (!c.trim().isEmpty()) { w.append(" AND FIND_IN_SET(?, c.category)>0 "); args.add(c.trim()); }
            }
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM training_course c" + w, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT c.id, c.course_name, c.hours, c.category, c.industry, c.post, c.duration, " +
                        " c.source, c.scope, c.paper_id, c.video_desc, c.status, c.created_at, " +
                        " p.paper_name, (SELECT COUNT(*) FROM training_course_video cv WHERE cv.course_id=c.id AND cv.deleted=0) AS video_count " +
                        " FROM training_course c LEFT JOIN training_paper p ON p.id=c.paper_id " +
                        w + " ORDER BY c.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", page);
        out.put("pageSize", size);
        out.put("records", list);
        return out;
    }

    /** 课程详情：视频列表 + 绑定卷子题目 */
    public Map<String, Object> course(long id) {
        Map<String, Object> c = row("SELECT c.*, p.paper_name FROM training_course c " +
                "LEFT JOIN training_paper p ON p.id=c.paper_id WHERE c.id=? AND c.deleted=0", id);
        if (c == null) throw new BizException("课程不存在");
        List<Map<String, Object>> vids = jdbc.queryForList(
                "SELECT v.id, v.video_name, v.video_desc, v.video_url, v.duration_sec, cv.sort_order " +
                        "FROM training_course_video cv JOIN training_video v ON v.id=cv.video_id " +
                        "WHERE cv.course_id=? AND cv.deleted=0 ORDER BY cv.sort_order", id);
        c.put("videos", vids);
        if (c.get("paper_id") != null) {
            c.put("questions", questions(l(c.get("paper_id")), false));
        } else {
            c.put("questions", List.of());
        }
        return c;
    }

    @Transactional
    public long saveCourse(Map<String, Object> b) {
        long id = l(b.get("id"));
        String name = s(b.get("courseName")).trim();
        if (name.isEmpty()) throw new BizException("请填写课程名称");
        String category = joinList(b.get("category"));
        String post = joinList(b.get("post"));
        String industry = s(b.get("industry"));
        String duration = s(b.get("duration"));
        String source = s(b.get("source"));
        String scope = s(b.get("scope"));
        String desc = s(b.get("videoDesc"));
        int hours = i(b.get("hours"), 1);
        Long paperId = b.get("paperId") == null || s(b.get("paperId")).isEmpty() ? null : l(b.get("paperId"));
        if (id == 0L) {
            id = IdWorker.getId();
            jdbc.update("INSERT INTO training_course (id,tenant_id,course_name,hours,category,industry,post,duration," +
                            "source,scope,paper_id,video_desc,status,created_by,deleted) VALUES (?,0,?,?,?,?,?,?,?,?,?,?,'published',?,0)",
                    id, name, hours, category, industry, post, duration, source, scope, paperId, desc, uid());
        } else {
            jdbc.update("UPDATE training_course SET course_name=?,hours=?,category=?,industry=?,post=?,duration=?," +
                            "source=?,scope=?,paper_id=?,video_desc=?,updated_at=NOW() WHERE id=? AND tenant_id=0",
                    name, hours, category, industry, post, duration, source, scope, paperId, desc, id);
        }
        // 课程视频关联：全量覆盖
        Object vidsObj = b.get("videoIds");
        if (vidsObj != null) {
            jdbc.update("UPDATE training_course_video SET deleted=1 WHERE course_id=?", id);
            List<Object> vids = (vidsObj instanceof List<?> ls) ? new ArrayList<>(ls) : List.of();
            int order = 1;
            for (Object v : vids) {
                long vid = l(v);
                if (vid == 0L) continue;
                jdbc.update("INSERT INTO training_course_video (id,tenant_id,course_id,video_id,sort_order,created_by,deleted) " +
                        "VALUES (?,0,?,?,?,?,0)", IdWorker.getId(), id, vid, order++, uid());
            }
            // 总时长展示按视频秒数汇总
            Integer sec = jdbc.queryForObject("SELECT COALESCE(SUM(v.duration_sec),0) FROM training_course_video cv " +
                    "JOIN training_video v ON v.id=cv.video_id WHERE cv.course_id=? AND cv.deleted=0", Integer.class, id);
            if (sec != null && sec > 0) {
                jdbc.update("UPDATE training_course SET duration=? WHERE id=?",
                        String.format("%02d:%02d", sec / 60, sec % 60), id);
            }
        }
        return id;
    }

    private String joinList(Object o) {
        if (o == null) return "";
        if (o instanceof List<?> ls) {
            return ls.stream().map(String::valueOf).filter(x -> !x.isBlank()).collect(Collectors.joining(","));
        }
        return s(o);
    }

    @Transactional
    public void deleteCourse(long id) {
        jdbc.update("UPDATE training_course SET deleted=1 WHERE id=? AND tenant_id=0", id);
        jdbc.update("UPDATE training_course_video SET deleted=1 WHERE course_id=?", id);
    }

    // =====================================================================
    // 三、PC：试题管理（对齐原型 pc/video_exam.html 试题管理 tab）
    // =====================================================================

    public List<Map<String, Object>> papers() {
        return jdbc.queryForList(
                "SELECT p.id, p.paper_name, p.question_count, p.status, p.created_at, " +
                        " (SELECT COUNT(*) FROM training_course c WHERE c.paper_id=p.id AND c.deleted=0) AS bind_count " +
                        " FROM training_paper p WHERE p.tenant_id=0 AND p.deleted=0 ORDER BY p.id");
    }

    /** 题目列表；withAnswer=true 用于 PC 查看，false 用于小程序考试（不泄露答案） */
    public List<Map<String, Object>> questions(long paperId, boolean withAnswer) {
        String cols = "id, paper_id, question_type, stem, options, difficulty, sort_order" +
                (withAnswer ? ", answer, analysis" : "");
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT " + cols + " FROM training_question WHERE paper_id=? AND tenant_id=0 AND deleted=0 " +
                        "ORDER BY sort_order, id", paperId);
        for (Map<String, Object> q : list) {
            q.put("options", parseOptions(s(q.get("options"))));
        }
        return list;
    }

    private Object parseOptions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(raw);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int k = 0; k < arr.size(); k++) {
                JSONObject o = arr.getJSONObject(k);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", o.getStr("key"));
                m.put("text", o.getStr("text"));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> paper(long id) {
        Map<String, Object> p = row("SELECT * FROM training_paper WHERE id=? AND deleted=0", id);
        if (p == null) throw new BizException("考试卷不存在");
        p.put("questions", questions(id, true));
        return p;
    }

    public long savePaper(Map<String, Object> b) {
        String name = s(b.get("paperName")).trim();
        if (name.isEmpty()) throw new BizException("请填写卷子名称");
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO training_paper (id,tenant_id,paper_name,question_count,status,created_by,deleted) " +
                "VALUES (?,0,?,0,'published',?,0)", id, name, uid());
        return id;
    }

    @Transactional
    public void deletePaper(long id) {
        jdbc.update("UPDATE training_paper SET deleted=1 WHERE id=?", id);
        jdbc.update("UPDATE training_question SET deleted=1 WHERE paper_id=?", id);
        jdbc.update("UPDATE training_course SET paper_id=NULL WHERE paper_id=?", id);
    }

    @Transactional
    public long addQuestion(Map<String, Object> b) {
        long paperId = l(b.get("paperId"));
        String stem = s(b.get("stem")).trim();
        String answer = s(b.get("answer")).trim();
        String type = s(b.get("questionType"));
        if (paperId == 0L || stem.isEmpty() || answer.isEmpty()) throw new BizException("请填写题干与正确答案");
        Object opts = b.get("options");
        String optionsJson = opts == null ? "[]" : json(opts);
        long id = IdWorker.getId();
        Integer maxOrder = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order),0) FROM training_question WHERE paper_id=? AND deleted=0",
                Integer.class, paperId);
        jdbc.update("INSERT INTO training_question (id,tenant_id,paper_id,question_type,stem,options,answer," +
                        "analysis,difficulty,sort_order,created_by,deleted) VALUES (?,0,?,?,?,?,?,?,?,?,?,0)",
                id, paperId, type, stem, optionsJson, answer, s(b.get("analysis")),
                s(b.get("difficulty")).isEmpty() ? "中" : s(b.get("difficulty")),
                (maxOrder == null ? 0 : maxOrder) + 1, uid());
        jdbc.update("UPDATE training_paper SET question_count=(SELECT COUNT(*) FROM training_question " +
                "WHERE paper_id=? AND deleted=0) WHERE id=?", paperId, paperId);
        return id;
    }

    @Transactional
    public void deleteQuestion(long id) {
        Long pid = jdbc.queryForObject("SELECT paper_id FROM training_question WHERE id=?", Long.class, id);
        jdbc.update("UPDATE training_question SET deleted=1 WHERE id=?", id);
        if (pid != null) {
            jdbc.update("UPDATE training_paper SET question_count=(SELECT COUNT(*) FROM training_question " +
                    "WHERE paper_id=? AND deleted=0) WHERE id=?", pid, pid);
        }
    }

    // =====================================================================
    // 四、PC：岗中月度计划 + 下发人员（对齐原型 pc/training.html）
    // =====================================================================

    public List<Map<String, Object>> plans() {
        long t = entId();
        return jdbc.queryForList(
                "SELECT p.*, c.course_name, pp.paper_name, " +
                        " (SELECT COUNT(*) FROM training_plan_video pv WHERE pv.plan_id=p.id AND pv.deleted=0) AS video_count_real, " +
                        " (SELECT COUNT(*) FROM training_assign a WHERE a.plan_id=p.id AND a.deleted=0) AS assign_count, " +
                        " (SELECT COUNT(*) FROM training_assign a WHERE a.plan_id=p.id AND a.deleted=0 AND a.status='completed') AS done_count " +
                        " FROM training_plan p LEFT JOIN training_course c ON c.id=p.course_id " +
                        " LEFT JOIN training_paper pp ON pp.id=p.paper_id " +
                        " WHERE p.tenant_id=? AND p.deleted=0 AND p.plan_type='on_job' " +
                        " ORDER BY p.year DESC, p.month DESC, p.id DESC", t);
    }

    public Map<String, Object> plan(long id) {
        Map<String, Object> p = row("SELECT p.*, c.course_name, pp.paper_name FROM training_plan p " +
                "LEFT JOIN training_course c ON c.id=p.course_id LEFT JOIN training_paper pp ON pp.id=p.paper_id " +
                "WHERE p.id=? AND p.deleted=0", id);
        if (p == null) throw new BizException("培训计划不存在");
        p.put("videos", planVideos(id));
        p.put("assigns", assigns(id));
        return p;
    }

    private List<Map<String, Object>> planVideos(long planId) {
        return jdbc.queryForList(
                "SELECT v.id, v.video_name, v.video_desc, v.video_url, v.duration_sec, pv.sort_order " +
                        "FROM training_plan_video pv JOIN training_video v ON v.id=pv.video_id " +
                        "WHERE pv.plan_id=? AND pv.deleted=0 ORDER BY pv.sort_order", planId);
    }

    private List<Map<String, Object>> assigns(long planId) {
        return jdbc.queryForList(
                "SELECT a.id, a.user_id, a.status, a.watch_minutes, a.watch_progress, a.created_at, " +
                        " pe.name AS person_name, pe.post_ids, " +
                        " (SELECT COUNT(*) FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0) AS exam_times, " +
                        " (SELECT e.score FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0 ORDER BY e.id DESC LIMIT 1) AS last_score, " +
                        " (SELECT e.result FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0 ORDER BY e.id DESC LIMIT 1) AS last_result " +
                        " FROM training_assign a LEFT JOIN person pe ON pe.id=a.user_id " +
                        " WHERE a.plan_id=? AND a.deleted=0 ORDER BY a.id", planId);
    }

    /** 可下发人员（企业人员名册） */
    public List<Map<String, Object>> persons() {
        return jdbc.queryForList(
                "SELECT id, name, telephone, post_ids FROM person WHERE tenant_id=? AND deleted=0 ORDER BY id", entId());
    }

    @Transactional
    public long savePlan(Map<String, Object> b) {
        long id = l(b.get("id"));
        String name = s(b.get("planName")).trim();
        int year = i(b.get("year"), LocalDateTime.now().getYear());
        int month = i(b.get("month"), LocalDateTime.now().getMonthValue());
        long courseId = l(b.get("courseId"));
        long paperId = l(b.get("paperId"));
        int passScore = i(b.get("passScore"), 60);
        int maxRetry = i(b.get("maxRetry"), 3);
        int forceLearn = i(b.get("forceLearn"), 0);
        int learnHours = i(b.get("learnHours"), 2);
        String desc = s(b.get("description"));
        if (name.isEmpty()) throw new BizException("请填写计划名称");
        if (courseId == 0L) throw new BizException("请选择课程");
        if (paperId == 0L) throw new BizException("请选择考试卷");

        if (id == 0L) {
            id = IdWorker.getId();
            jdbc.update("INSERT INTO training_plan (id,tenant_id,plan_name,plan_type,cargo_type,year,month,learn_hours," +
                            "force_learn,pass_score,max_retry,course_id,paper_id,description,status,created_by,deleted) " +
                            "VALUES (?,?,?,'on_job','general',?,?,?,?,?,?,?,?,?,'draft',?,0)",
                    id, entId(), name, year, month, learnHours, forceLearn, passScore, maxRetry,
                    courseId, paperId, desc, uid());
        } else {
            jdbc.update("UPDATE training_plan SET plan_name=?,year=?,month=?,learn_hours=?,force_learn=?," +
                            "pass_score=?,max_retry=?,course_id=?,paper_id=?,description=?,updated_at=NOW() " +
                            "WHERE id=? AND tenant_id=?",
                    name, year, month, learnHours, forceLearn, passScore, maxRetry, courseId, paperId, desc,
                    id, entId());
        }
        // 计划视频 = 所选课程视频
        jdbc.update("UPDATE training_plan_video SET deleted=1 WHERE plan_id=?", id);
        List<Map<String, Object>> cv = jdbc.queryForList(
                "SELECT video_id FROM training_course_video WHERE course_id=? AND deleted=0 ORDER BY sort_order", courseId);
        int order = 1;
        for (Map<String, Object> x : cv) {
            jdbc.update("INSERT INTO training_plan_video (id,tenant_id,plan_id,video_id,month,sort_order,created_by,deleted) " +
                    "VALUES (?,?,?,?,?,?,?,0)", IdWorker.getId(), entId(), id, l(x.get("video_id")), month, order++, uid());
        }
        jdbc.update("UPDATE training_plan SET video_count=? WHERE id=?", cv.size(), id);
        return id;
    }

    @Transactional
    public void publishPlan(long id) {
        jdbc.update("UPDATE training_plan SET status='published',updated_at=NOW() WHERE id=? AND tenant_id=?",
                id, entId());
    }

    @Transactional
    public void setForceLearn(long id, int force) {
        jdbc.update("UPDATE training_plan SET force_learn=?,updated_at=NOW() WHERE id=? AND tenant_id=?",
                force, id, entId());
    }

    /** 下发：把人员绑定进 training_assign（已存在的不重复） */
    @Transactional
    public int assign(long planId, Object personIdsObj) {
        List<?> ids = personIdsObj instanceof List<?> ls ? ls : List.of();
        if (ids.isEmpty()) throw new BizException("请选择要下发的人员");
        Map<String, Object> p = row("SELECT tenant_id FROM training_plan WHERE id=? AND deleted=0", planId);
        if (p == null) throw new BizException("培训计划不存在");
        long t = l(p.get("tenant_id"));
        int added = 0;
        for (Object o : ids) {
            long personId = l(o);
            if (personId == 0L) continue;
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM training_assign WHERE plan_id=? AND user_id=? AND deleted=0",
                    Integer.class, planId, personId);
            if (exists != null && exists > 0) continue;
            jdbc.update("INSERT INTO training_assign (id,tenant_id,plan_id,user_id,user_type,status," +
                            "watch_minutes,created_by,deleted) VALUES (?,?,?,?,'从业人员','not_started',0,?,0)",
                    IdWorker.getId(), t, planId, personId, uid());
            added++;
        }
        jdbc.update("UPDATE training_plan SET status='published',updated_at=NOW() WHERE id=?", planId);
        return added;
    }

    // =====================================================================
    // 五、PC：学习情况统计（对齐原型 pc/train_stat.html）
    // =====================================================================

    public List<Map<String, Object>> stats(Long planId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.id AS assign_id, a.user_id, a.status, a.watch_minutes, a.watch_progress, " +
                        " pe.name AS person_name, p.plan_name, p.year, p.month, p.force_learn, p.pass_score, " +
                        " (SELECT COUNT(*) FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0) AS exam_times, " +
                        " (SELECT e.score FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0 ORDER BY e.id DESC LIMIT 1) AS score, " +
                        " (SELECT e.result FROM training_exam e WHERE e.assign_id=a.id AND e.deleted=0 ORDER BY e.id DESC LIMIT 1) AS exam_result " +
                        " FROM training_assign a JOIN training_plan p ON p.id=a.plan_id " +
                        " LEFT JOIN person pe ON pe.id=a.user_id " +
                        " WHERE a.tenant_id=? AND a.deleted=0 AND p.deleted=0 AND p.plan_type='on_job' ");
        List<Object> args = new ArrayList<>();
        args.add(entId());
        if (planId != null && planId > 0) { sql.append(" AND a.plan_id=? "); args.add(planId); }
        if (status != null && !status.isBlank()) { sql.append(" AND a.status=? "); args.add(status); }
        sql.append(" ORDER BY p.year DESC, p.month DESC, a.id ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // =====================================================================
    // 六、小程序：学习中心（对齐原型 mini/study.html）
    // =====================================================================

    /** 可切换的学员（已下发人员），便于在演示环境逐人测试 */
    public List<Map<String, Object>> learners() {
        return jdbc.queryForList(
                "SELECT DISTINCT a.user_id, pe.name FROM training_assign a " +
                        "JOIN training_plan p ON p.id=a.plan_id " +
                        "LEFT JOIN person pe ON pe.id=a.user_id " +
                        "WHERE a.tenant_id=? AND a.deleted=0 AND p.deleted=0 AND p.plan_type='on_job' " +
                        "ORDER BY a.user_id", entId());
    }

    /** 学习中心首页：本月进度、继续学习、全部课程（按计划视频展开） */
    public Map<String, Object> mobileHome(long personId, int year, int month) {
        long t = entId();
        Map<String, Object> out = new LinkedHashMap<>();
        // 计划选择：优先「该学员已被下发的当月计划」，避免企业新建了其他计划后
        // 学员被挂到与己无关的计划上；没有则回退到当月最新已发布计划 / 最近一期计划。
        Map<String, Object> plan = null;
        if (personId > 0L) {
            plan = row("SELECT p.* FROM training_plan p JOIN training_assign a ON a.plan_id=p.id " +
                            "WHERE p.tenant_id=? AND p.plan_type='on_job' AND p.status='published' " +
                            "AND p.deleted=0 AND p.year=? AND p.month=? AND a.user_id=? AND a.deleted=0 " +
                            "ORDER BY p.id DESC LIMIT 1", t, year, month, personId);
        }
        if (plan == null) {
            plan = row("SELECT * FROM training_plan WHERE tenant_id=? AND plan_type='on_job' AND status='published' " +
                    "AND deleted=0 AND year=? AND month=? ORDER BY id DESC LIMIT 1", t, year, month);
        }
        if (plan == null) {
            plan = row("SELECT * FROM training_plan WHERE tenant_id=? AND plan_type='on_job' " +
                    "AND status='published' AND deleted=0 ORDER BY year DESC, month DESC, id DESC LIMIT 1", t);
        }
        if (plan == null) {
            out.put("plan", null);
            out.put("courses", List.of());
            out.put("progress", 0);
            out.put("assignId", null);
            out.put("exam", Map.of());
            return out;
        }
        long planId = l(plan.get("id"));
        // 该学员的 assign（未下发则按需创建，方便直接打开小程序测试）
        Map<String, Object> assign = row(
                "SELECT * FROM training_assign WHERE plan_id=? AND user_id=? AND deleted=0 ORDER BY id LIMIT 1",
                planId, personId);
        if (assign == null) {
            long aid = IdWorker.getId();
            jdbc.update("INSERT INTO training_assign (id,tenant_id,plan_id,user_id,user_type,status," +
                            "watch_minutes,created_by,deleted) VALUES (?,?,?,?,'从业人员','not_started',0,?,0)",
                    aid, t, planId, personId, uid());
            assign = row("SELECT * FROM training_assign WHERE id=?", aid);
        }
        long assignId = l(assign.get("id"));
        Map<String, Object> progress = watchProgress(assign);

        List<Map<String, Object>> vids = planVideos(planId);
        int total = vids.size();
        int learned = 0;
        for (Map<String, Object> v : vids) {
            long vid = l(v.get("video_id") != null ? v.get("video_id") : v.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> pv = (Map<String, Object>) progress.getOrDefault(String.valueOf(vid), Map.of());
            int pos = i(pv.get("position"), 0);
            int dur = i(v.get("duration_sec"), 0);
            v.put("videoId", vid);
            v.put("position", pos);
            v.put("finished", dur > 0 && pos >= dur - 1);
            v.put("percent", dur > 0 ? Math.min(100, pos * 100 / dur) : 0);
            if (dur > 0 && pos >= dur - 1) learned++;
        }
        int percent = total == 0 ? 0 : learned * 100 / total;

        // 考核状态
        Map<String, Object> lastExam = row("SELECT id,score,result,submit_time FROM training_exam " +
                "WHERE assign_id=? AND deleted=0 ORDER BY id DESC LIMIT 1", assignId);
        Map<String, Object> exam = new LinkedHashMap<>();
        exam.put("passed", lastExam != null && "pass".equals(s(lastExam.get("result"))));
        exam.put("score", lastExam == null ? null : lastExam.get("score"));
        exam.put("times", jdbc.queryForObject("SELECT COUNT(*) FROM training_exam WHERE assign_id=? AND deleted=0",
                Integer.class, assignId));
        exam.put("maxRetry", i(plan.get("max_retry"), 3));
        exam.put("passScore", i(plan.get("pass_score"), 60));
        exam.put("forceLearn", i(plan.get("force_learn"), 0));
        exam.put("locked", i(plan.get("force_learn"), 0) == 1 && learned < total);

        // 计划信息里补课程/卷名；exam.paperName 必须在此之后取，否则恒为空
        Map<String, Object> extra = row("SELECT c.course_name, pp.paper_name FROM training_plan p " +
                "LEFT JOIN training_course c ON c.id=p.course_id LEFT JOIN training_paper pp ON pp.id=p.paper_id " +
                "WHERE p.id=?", planId);
        if (extra != null) plan.putAll(extra);
        exam.put("paperName", s(plan.get("paper_name")));

        out.put("plan", plan);
        out.put("assignId", assignId);
        out.put("courses", vids);
        out.put("progress", percent);
        out.put("learnedCount", learned);
        out.put("totalCount", total);
        out.put("exam", exam);
        out.put("status", s(assign.get("status")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> watchProgress(Map<String, Object> assign) {
        String raw = s(assign.get("watch_progress"));
        if (raw.isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(JSONUtil.parseObj(raw));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 上报观看进度：按视频记录 position / 时长，累计 watch_minutes，推进状态机 */
    @Transactional
    public Map<String, Object> reportProgress(Map<String, Object> b) {
        long assignId = l(b.get("assignId"));
        long videoId = l(b.get("videoId"));
        int position = Math.max(0, i(b.get("position"), 0));
        int duration = Math.max(0, i(b.get("duration"), 0));
        Map<String, Object> assign = row("SELECT * FROM training_assign WHERE id=? AND deleted=0", assignId);
        if (assign == null) throw new BizException("学习任务不存在");
        Map<String, Object> progress = watchProgress(assign);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("position", position);
        one.put("duration", duration);
        one.put("time", LocalDateTime.now().toString());
        progress.put(String.valueOf(videoId), one);

        int totalSec = 0;
        for (Object v : progress.values()) {
            if (v instanceof Map<?, ?> m) totalSec += i(m.get("position"), 0);
        }
        int minutes = (int) Math.ceil(totalSec / 60.0);

        String status = s(assign.get("status"));
        if ("not_started".equals(status)) status = "learning";

        // 学时开关开启：全部视频学完 → 学时达标（待考核）
        Map<String, Object> plan = row("SELECT force_learn, id FROM training_plan WHERE id=?", l(assign.get("plan_id")));
        if (plan != null && i(plan.get("force_learn"), 0) == 1 && !"completed".equals(status)) {
            List<Map<String, Object>> vids = planVideos(l(plan.get("id")));
            boolean allDone = true;
            for (Map<String, Object> v : vids) {
                long vid = l(v.get("video_id") != null ? v.get("video_id") : v.get("id"));
                Object pv = progress.get(String.valueOf(vid));
                int dur = i(v.get("duration_sec"), 0);
                int pos = pv instanceof Map<?, ?> m ? i(m.get("position"), 0) : 0;
                if (dur > 0 && pos < dur - 1) { allDone = false; break; }
            }
            if (allDone && !vids.isEmpty()) status = "qualified";
        }

        jdbc.update("UPDATE training_assign SET watch_progress=?, watch_minutes=?, status=?, updated_at=NOW() WHERE id=?",
                json(progress), minutes, status, assignId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watchMinutes", minutes);
        out.put("status", status);
        out.put("progress", progress);
        return out;
    }

    // =====================================================================
    // 七、小程序：在线考核（组卷 → 自动判分 → 结果留痕）
    // =====================================================================

    /** 组卷：按计划绑定卷子抽题，不返回答案；学时开关开启时校验学满 */
    public Map<String, Object> examPaper(long assignId) {
        Map<String, Object> assign = row("SELECT * FROM training_assign WHERE id=? AND deleted=0", assignId);
        if (assign == null) throw new BizException("学习任务不存在");
        Map<String, Object> plan = row("SELECT * FROM training_plan WHERE id=?", l(assign.get("plan_id")));
        if (plan == null) throw new BizException("培训计划不存在");
        int force = i(plan.get("force_learn"), 0);
        if (force == 1) {
            Map<String, Object> progress = watchProgress(assign);
            List<Map<String, Object>> vids = planVideos(l(plan.get("id")));
            for (Map<String, Object> v : vids) {
                long vid = l(v.get("video_id") != null ? v.get("video_id") : v.get("id"));
                Object pv = progress.get(String.valueOf(vid));
                int dur = i(v.get("duration_sec"), 0);
                int pos = pv instanceof Map<?, ?> m ? i(m.get("position"), 0) : 0;
                if (dur > 0 && pos < dur - 1) {
                    throw new BizException("请先学完本月全部课程视频，再参加考核");
                }
            }
        }
        int maxRetry = i(plan.get("max_retry"), 3);
        Integer times = jdbc.queryForObject("SELECT COUNT(*) FROM training_exam WHERE assign_id=? AND deleted=0",
                Integer.class, assignId);
        if (times != null && times >= maxRetry) {
            throw new BizException("已达最大考试次数（" + maxRetry + " 次），请联系安全员");
        }
        long paperId = l(plan.get("paper_id"));
        List<Map<String, Object>> qs = questions(paperId, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignId", assignId);
        out.put("paperId", paperId);
        out.put("passScore", i(plan.get("pass_score"), 60));
        out.put("maxRetry", maxRetry);
        out.put("usedTimes", times == null ? 0 : times);
        out.put("questions", qs);
        return out;
    }

    /** 交卷：自动判分（单选/判断直接比，多选按集合比），试题+答案+结果快照留痕 */
    @Transactional
    public Map<String, Object> submitExam(long assignId, Map<String, Object> answers) {
        Map<String, Object> assign = row("SELECT * FROM training_assign WHERE id=? AND deleted=0", assignId);
        if (assign == null) throw new BizException("学习任务不存在");
        Map<String, Object> plan = row("SELECT * FROM training_plan WHERE id=?", l(assign.get("plan_id")));
        if (plan == null) throw new BizException("培训计划不存在");
        long paperId = l(plan.get("paper_id"));
        List<Map<String, Object>> qs = questions(paperId, true);
        if (qs.isEmpty()) throw new BizException("该计划未配置考题");

        int correct = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Map<String, Object> q : qs) {
            String qid = String.valueOf(q.get("id"));
            String right = s(q.get("answer")).trim();
            String userAns = normalizeAns(answers.get(qid));
            boolean ok = right.equalsIgnoreCase(userAns)
                    || ("multi".equals(s(q.get("question_type"))) && sameSet(right, userAns));
            if (ok) correct++;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", q.get("id"));
            d.put("stem", q.get("stem"));
            d.put("type", q.get("question_type"));
            d.put("options", q.get("options"));
            d.put("answer", right);
            d.put("userAnswer", userAns);
            d.put("correct", ok);
            detail.add(d);
        }
        int score = (int) Math.round(correct * 100.0 / qs.size());
        int passScore = i(plan.get("pass_score"), 60);
        String result = score >= passScore ? "pass" : "fail";

        // 快照留痕：试题（含正确答案）+ 用户答案 + 得分 + 结果
        long examId = IdWorker.getId();
        jdbc.update("INSERT INTO training_exam (id,tenant_id,assign_id,user_id,questions,answers,score,result," +
                        "submit_time,created_by,deleted) VALUES (?,?,?,?,?,?,?,?,NOW(),?,0)",
                examId, l(assign.get("tenant_id")), assignId, l(assign.get("user_id")),
                json(detail), json(answers), score, result, uid());
        jdbc.update("UPDATE training_assign SET status=?, updated_at=NOW() WHERE id=?",
                "pass".equals(result) ? "completed" : "failed", assignId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examId", examId);
        out.put("score", score);
        out.put("passScore", passScore);
        out.put("result", result);
        out.put("correct", correct);
        out.put("total", qs.size());
        out.put("detail", detail);
        return out;
    }

    private String normalizeAns(Object o) {
        if (o == null) return "";
        if (o instanceof List<?> ls) {
            return ls.stream().map(String::valueOf).map(String::trim).sorted()
                    .collect(Collectors.joining(","));
        }
        return s(o).trim();
    }

    private boolean sameSet(String a, String b) {
        Set<String> sa = Arrays.stream(a.split("[,，]")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toSet());
        Set<String> sb = Arrays.stream(b.split("[,，]")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toSet());
        return !sa.isEmpty() && sa.equals(sb);
    }

    /** 考核结果（用于返回小程序结果页 / PC 统计详情） */
    public Map<String, Object> examResult(long assignId) {
        Map<String, Object> e = row("SELECT id,score,result,submit_time,questions,answers FROM training_exam " +
                "WHERE assign_id=? AND deleted=0 ORDER BY id DESC LIMIT 1", assignId);
        if (e == null) return Map.of();
        e.put("detail", JSONUtil.isTypeJSON(s(e.get("questions")))
                ? JSONUtil.parseArray(s(e.get("questions"))) : List.of());
        e.remove("questions");
        e.remove("answers");
        return e;
    }
}
