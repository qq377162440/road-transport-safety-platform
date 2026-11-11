package com.safetrans.archive.service;

import cn.hutool.core.util.IdUtil;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一人一档 / 一车一档「模拟数据」一键生成（用户 2026-11-11 需求）：
 *
 * <p>补齐演示企业的人员与车辆，并让<b>所有</b>人档（14 项）与车档（C01~C13 + 证照）
 * 都有数据：每个归档项都生成一张真实 PDF 样例（页面写明「××样例」，例如「身份证样例」），
 * 上传 MinIO 后把 file_url 落到状态表，保证前端可预览可下载。
 *
 * <p>性能与稳定性：
 * <ul>
 *   <li><b>样例 PDF 标题级缓存</b>：同种样例（如「身份证样例」）只渲染 + 上传一次，
 *       后续所有人/车复用同一个 MinIO 对象名，渲染次数从 ~280 降到 ~43；</li>
 *   <li><b>异步执行</b>：seed() 捕获租户后在后台线程执行，立即返回 taskId，
 *       客户端用 seedStatus(taskId) 轮询进度，彻底摆脱 HTTP 同步超时；</li>
 *   <li><b>幂等 + 增量补齐</b>：人员/车辆按主键 IGNORE 插入；归档项已有 file_url 则跳过；
 *       中途失败再次调用只补齐缺失项。</li>
 * </ul>
 */
@Service
public class MockArchiveService {

    /** 一车一档 13 项（与 VehicleFlowController.ITEMS 同源，用于生成样例文件名） */
    private static final String[][] VEHICLE_ITEMS = {
            {"C01", "车辆基础信息登记表"}, {"C02", "道路运输证"}, {"C03", "行驶证"},
            {"C04", "检验报告"}, {"C05", "机动车登记证（绿本）"}, {"C06", "随车设备清单"},
            {"C07", "保险（交强险/商业险）"}, {"C08", "车辆技术档案"},
            {"C09", "智能视频动态监控/GPS 安装证明"}, {"C10", "车辆等级评定登记表"},
            {"C11", "车辆维护和维修登记表"}, {"C12", "车辆主要部件更换登记表"},
            {"C13", "车辆行驶里程登记表"},
    };

    /** 每人 3 本证照（doc_code → 名称） */
    private static final String[][] PERSON_DOCS = {
            {"ID_CARD", "身份证复印件"}, {"DRIVE_LICENSE", "机动车驾驶证"},
            {"QUALIFICATION", "道路运输从业人员从业资格证"},
    };

    private final JdbcTemplate jdbc;
    private final PdfService pdfService;
    private final StorageService storage;

    /** taskId -> 进度（运行态，跨请求保留） */
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskSeq = new AtomicInteger(0);
    private volatile String latestTaskId = null;

    /** 样例 PDF 标题级缓存：标题 -> MinIO 对象名（同种只渲染一次） */
    private final Map<String, String> pdfCache = new ConcurrentHashMap<>();
    /** 后台线程执行时的租户（ThreadLocal 不跨线程传播，故显式捕获） */
    private final ThreadLocal<Long> runTenant = new ThreadLocal<>();

    public MockArchiveService(JdbcTemplate jdbc, PdfService pdfService, StorageService storage) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.storage = storage;
    }

    private long tid() {
        Long t = TenantContextHolder.tenantId();
        return (t == null || t == 0L) ? 5001L : t;
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // 异步入口：立即返回 taskId，后台线程跑实际生成；客户端轮询 seedStatus
    // ------------------------------------------------------------------
    public Map<String, Object> seed() {
        long t = tid();
        String taskId = "seed-" + System.currentTimeMillis() + "-" + taskSeq.incrementAndGet();
        Map<String, Object> task = new ConcurrentHashMap<>();
        task.put("taskId", taskId);
        task.put("status", "running");
        task.put("step", "init");
        task.put("startedAt", LocalDateTime.now().toString());
        task.put("persons", 0);
        task.put("vehicles", 0);
        task.put("personArchiveItems", 0);
        task.put("personDocs", 0);
        task.put("vehicleArchiveItems", 0);
        task.put("vehicleDocs", 0);
        tasks.put(taskId, task);
        latestTaskId = taskId;
        runTenant.set(t);
        CompletableFuture.runAsync(() -> runSeed(taskId, t));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("status", "started");
        return out;
    }

    /** 进度查询：不传 taskId 取最近一次 */
    public Map<String, Object> seedStatus(String taskId) {
        String id = (taskId == null || taskId.isBlank()) ? latestTaskId : taskId;
        Map<String, Object> t = (id == null) ? null : tasks.get(id);
        if (t == null) {
            Map<String, Object> nf = new LinkedHashMap<>();
            nf.put("taskId", id);
            nf.put("status", "not_found");
            return nf;
        }
        return new LinkedHashMap<>(t);
    }

    private void runSeed(String taskId, long t) {
        Map<String, Object> task = tasks.get(taskId);
        try {
            runTenant.set(t);
            task.put("step", "seedPersons");
            task.put("persons", seedPersons(t));
            task.put("step", "seedVehicles");
            task.put("vehicles", seedVehicles(t));
            task.put("step", "seedPersonArchive");
            task.put("personArchiveItems", seedPersonArchive(t));
            task.put("step", "seedPersonDocs");
            task.put("personDocs", seedPersonDocs(t));
            task.put("step", "seedVehicleArchive");
            task.put("vehicleArchiveItems", seedVehicleArchive(t));
            task.put("step", "seedVehicleDocs");
            task.put("vehicleDocs", seedVehicleDocs(t));
            int sp = ((Number) task.get("personArchiveItems")).intValue()
                    + ((Number) task.get("personDocs")).intValue()
                    + ((Number) task.get("vehicleArchiveItems")).intValue()
                    + ((Number) task.get("vehicleDocs")).intValue();
            task.put("samplePdfs", sp);
            task.put("status", "done");
            task.put("finishedAt", LocalDateTime.now().toString());
        } catch (Exception e) {
            task.put("status", "error");
            task.put("error", e.getMessage());
            task.put("finishedAt", LocalDateTime.now().toString());
        } finally {
            runTenant.remove();
        }
    }

    /**
     * 生成一张样例 PDF（大字标题 + 模拟说明），上传 MinIO 返回对象名。
     * 标题级缓存：同种样例只渲染一次，后续复用对象名（mock 数据无需每人一版）。
     */
    private String samplePdf(String sampleTitle, String owner, String extra) {
        String cacheKey = sampleTitle;
        String cached = pdfCache.get(cacheKey);
        if (cached != null) return cached;

        String html = "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"/><style>"
                + "@page{size:A4;margin:0;}"
                + "body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',sans-serif;margin:0;}"
                + ".pg{height:297mm;display:flex;flex-direction:column;align-items:center;justify-content:center;"
                + "border:14px solid #b91c1c;box-sizing:border-box;background:#fffdfd;}"
                + ".t{font-size:52pt;font-weight:bold;color:#1a1a1a;letter-spacing:10px;}"
                + ".o{font-size:20pt;margin-top:34px;color:#333;}"
                + ".e{font-size:13pt;margin-top:26px;color:#666;}"
                + ".w{font-size:11pt;color:#999;margin-top:60px;}"
                + "</style></head><body><div class=\"pg\">"
                + "<div class=\"t\">" + esc(sampleTitle) + "</div>"
                + (owner == null || owner.isBlank() ? "" : "<div class=\"o\">持有人 / 归属：" + esc(owner) + "</div>")
                + (extra == null || extra.isBlank() ? "" : "<div class=\"e\">" + esc(extra) + "</div>")
                + "<div class=\"w\">系统模拟数据 · 仅用于功能演示与档案预览 · " + LocalDate.now() + "</div>"
                + "</div></body></html>";
        byte[] bytes = pdfService.render(html);
        long t = (runTenant.get() == null) ? tid() : runTenant.get();
        String obj = String.format("%d/mock/%d_%s.pdf", t, System.nanoTime(),
                sampleTitle.replaceAll("\\s+", ""));
        // MinIO 偶发 Connection reset（okhttp keep-alive 被服务端关闭），失败重试 3 次
        IllegalStateException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                storage.upload(obj, bytes, "application/pdf");
                pdfCache.put(cacheKey, obj);
                return obj;
            } catch (IllegalStateException e) {
                last = e;
                try {
                    Thread.sleep(300L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------
    // 人员：在既有管理人员基础上补 4 名一线人员（押运员 / 装卸管理员 / 2 名驾驶员）
    // ------------------------------------------------------------------
    private int seedPersons(long t) {
        String[][] people = {
                {"8808000000000014", "李红梅", "0", "employee", "13900001014", "440981199210180044", "7011"},
                {"8808000000000015", "钱国栋", "1", "employee", "13900001015", "440981198703050055", "7012"},
                {"8808000000000016", "孙丽娜", "0", "employee", "13900001016", "440981199505220066", "7010"},
                {"8808000000000017", "马建军", "1", "employee", "13900001017", "440981199109100077", "7010"},
        };
        int n = 0;
        for (String[] p : people) {
            int ins = jdbc.update(
                    "INSERT IGNORE INTO `person` " +
                    "(`id`,`tenant_id`,`person_type`,`name`,`telephone`,`gender`,`politic`,`id_card`,`post_ids`,`created_by`,`deleted`) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,0,0)",
                    Long.parseLong(p[0]), t, p[3], p[1], p[4], Integer.parseInt(p[2]), "群众", p[5], p[6]);
            jdbc.update("INSERT IGNORE INTO `org_member` " +
                    "(`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`) VALUES (?,?,?,?,0,0)",
                    IdUtil.getSnowflakeNextId(), t, Long.parseLong(p[0]), Long.parseLong(p[6]));
            n += ins;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 车辆：补 2 辆厢式货车（字段填齐，保证 C01 主表齐全判定通过）
    // ------------------------------------------------------------------
    private int seedVehicles(long t) {
        String[][] vehicles = {
                {"8807000000000003", "粤E·24680", "蓝", "freight", "厢式货车", "解放J6L", "LFNA4LBC9P1A30003",
                        "佛山市高明高洲燃气有限公司", "2025-03-18", "粤E123456", "4600"},
                {"8807000000000004", "粤E·13579", "黄", "freight", "厢式货车", "东风天锦KR", "LGAX2A109M2B50007",
                        "佛山市高明高洲燃气有限公司", "2025-06-02", "粤E654321", "6800"},
        };
        int n = 0;
        for (String[] v : vehicles) {
            n += jdbc.update(
                    "INSERT IGNORE INTO `vehicle` " +
                    "(`id`,`tenant_id`,`plate_no`,`plate_color`,`biz_type`,`vehicle_type`,`brand_model`,`vin`," +
                    " `owner`,`reg_date`,`license_no`,`curb_weight`,`use_nature`,`energy_type`,`business_status`,`created_by`,`deleted`) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, '柴油', 'normal', 0, 0)",
                    Long.parseLong(v[0]), t, v[1], v[2], v[3], v[4], v[5], v[6],
                    v[7], LocalDate.parse(v[8]), v[9], Integer.parseInt(v[10]), "货运");
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 人档：所有人员的 14 项归档目录全部 status=1，file_url 指向真实样例 PDF
    // ------------------------------------------------------------------
    private int seedPersonArchive(long t) {
        List<Map<String, Object>> persons = jdbc.queryForList(
                "SELECT `id`,`name` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t);
        List<Map<String, Object>> dict = jdbc.queryForList(
                "SELECT `item_code`,`item_name` FROM `person_archive_dict` WHERE `deleted`=0 ORDER BY `sort_no`");
        int n = 0;
        for (Map<String, Object> p : persons) {
            long pid = ((Number) p.get("id")).longValue();
            String name = String.valueOf(p.get("name"));
            for (Map<String, Object> d : dict) {
                String code = String.valueOf(d.get("item_code"));
                if (hasPersonItem(t, pid, code)) continue; // 已有有效数据则跳过，增量补齐
                String url = samplePdf(String.valueOf(d.get("item_name")) + "样例", name,
                        "一人一档归档项 " + code);
                n += upsertPersonItem(t, pid, code, url);
            }
        }
        return n;
    }

    /** 该归档项是否已有非空 file_url（有效即跳过，避免重复渲染 PDF） */
    private boolean hasPersonItem(long t, long pid, String code) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? " +
                        "AND `deleted`=0 AND `file_url` IS NOT NULL AND `file_url`<>''",
                Integer.class, pid, code, t);
        return c != null && c > 0;
    }

    private int upsertPersonItem(long t, long pid, String code, String url) {
        List<Long> ids = jdbc.queryForList(
                "SELECT `id` FROM `person_archive_item` WHERE `person_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                Long.class, pid, code, t);
        if (ids.isEmpty()) {
            return jdbc.update("INSERT INTO `person_archive_item` " +
                    "(`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                    "VALUES (?,?,?,?,NULL,NULL,?,NULL,1,NOW(),0,0)",
                    IdUtil.getSnowflakeNextId(), t, pid, code, url);
        }
        return jdbc.update("UPDATE `person_archive_item` SET `file_url`=?, `status`=1, `updated_at`=NOW() WHERE `id`=?",
                url, ids.get(0));
    }

    // ------------------------------------------------------------------
    // 人证：每人 3 本证照 + 样例 PDF
    // ------------------------------------------------------------------
    private int seedPersonDocs(long t) {
        List<Map<String, Object>> persons = jdbc.queryForList(
                "SELECT `id`,`name` FROM `person` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t);
        int n = 0;
        for (Map<String, Object> p : persons) {
            long pid = ((Number) p.get("id")).longValue();
            String name = String.valueOf(p.get("name"));
            for (String[] d : PERSON_DOCS) {
                Integer c = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM `person_doc` WHERE `person_id`=? AND `doc_code`=? AND `tenant_id`=? " +
                                "AND `deleted`=0 AND `file_url` IS NOT NULL AND `file_url`<>''",
                        Integer.class, pid, d[0], t);
                if (c != null && c > 0) continue; // 已有有效数据则跳过
                String url = samplePdf(d[1] + "样例", name, "证照编号：4409**" + (pid % 1000));
                List<Long> ids = jdbc.queryForList(
                        "SELECT `id` FROM `person_doc` WHERE `person_id`=? AND `doc_code`=? AND `tenant_id`=? AND `deleted`=0",
                        Long.class, pid, d[0], t);
                if (ids.isEmpty()) {
                    n += jdbc.update("INSERT INTO `person_doc` " +
                            "(`id`,`tenant_id`,`person_id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,'2025-01-10','2035-01-09',1,NOW(),0,0)",
                            IdUtil.getSnowflakeNextId(), t, pid, d[0], d[1], url);
                } else {
                    n += jdbc.update("UPDATE `person_doc` SET `file_url`=?, `status`=1, `updated_at`=NOW() WHERE `id`=?",
                            url, ids.get(0));
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 车档：所有车辆的 C01~C13 全部 status=1 + 样例 PDF
    // ------------------------------------------------------------------
    private int seedVehicleArchive(long t) {
        List<Map<String, Object>> vehicles = jdbc.queryForList(
                "SELECT `id`,`plate_no` FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t);
        int n = 0;
        for (Map<String, Object> v : vehicles) {
            long vid = ((Number) v.get("id")).longValue();
            String plate = String.valueOf(v.get("plate_no"));
            for (String[] it : VEHICLE_ITEMS) {
                String url = samplePdf(it[1] + "样例", plate, "一车一档归档项 " + it[0]);
                List<Long> ids = jdbc.queryForList(
                        "SELECT `id` FROM `vehicle_archive_item` WHERE `vehicle_id`=? AND `item_code`=? AND `tenant_id`=? AND `deleted`=0",
                        Long.class, vid, it[0], t);
                if (ids.isEmpty()) {
                    n += jdbc.update("INSERT INTO `vehicle_archive_item` " +
                            "(`id`,`tenant_id`,`vehicle_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,NULL,NULL,?,NULL,1,NOW(),0,0)",
                            IdUtil.getSnowflakeNextId(), t, vid, it[0], url);
                } else {
                    n += jdbc.update("UPDATE `vehicle_archive_item` SET `file_url`=?, `status`=1, `updated_at`=NOW() WHERE `id`=?",
                            url, ids.get(0));
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 车证：每车按 vehicle_doc_dict 全量 + 样例 PDF
    // ------------------------------------------------------------------
    private int seedVehicleDocs(long t) {
        List<Map<String, Object>> dict;
        try {
            dict = jdbc.queryForList(
                    "SELECT `doc_code`,`doc_name` FROM `vehicle_doc_dict` WHERE `deleted`=0 ORDER BY `doc_code`");
        } catch (Exception e) {
            return 0;
        }
        if (dict.isEmpty()) return 0;
        List<Map<String, Object>> vehicles = jdbc.queryForList(
                "SELECT `id`,`plate_no` FROM `vehicle` WHERE `tenant_id`=? AND `deleted`=0 ORDER BY `id`", t);
        int n = 0;
        for (Map<String, Object> v : vehicles) {
            long vid = ((Number) v.get("id")).longValue();
            String plate = String.valueOf(v.get("plate_no"));
            for (Map<String, Object> d : dict) {
                String code = String.valueOf(d.get("doc_code"));
                String url = samplePdf(String.valueOf(d.get("doc_name")) + "样例", plate, "车辆证照 " + code);
                List<Long> ids = jdbc.queryForList(
                        "SELECT `id` FROM `vehicle_doc` WHERE `vehicle_id`=? AND `doc_code`=? AND `tenant_id`=? AND `deleted`=0",
                        Long.class, vid, code, t);
                if (ids.isEmpty()) {
                    n += jdbc.update("INSERT INTO `vehicle_doc` " +
                            "(`id`,`tenant_id`,`vehicle_id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status`,`created_at`,`created_by`,`deleted`) " +
                            "VALUES (?,?,?,?,?,?,'2025-01-10','2030-01-09',1,NOW(),0,0)",
                            IdUtil.getSnowflakeNextId(), t, vid, code,
                            String.valueOf(d.get("doc_name")), url);
                } else {
                    n += jdbc.update("UPDATE `vehicle_doc` SET `file_url`=?, `status`=1, `updated_at`=NOW() WHERE `id`=?",
                            url, ids.get(0));
                }
            }
        }
        return n;
    }
}
