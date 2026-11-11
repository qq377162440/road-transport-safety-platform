package com.safetrans.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.mapper.ArchiveItemMapper;
import com.safetrans.archive.mapper.ArchivePdfMapper;
import com.safetrans.archive.service.ArchiveService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织架构 → 电子档案柜 联动（用户 2026-11-11 需求）：
 *
 * <p>「安全组织架构」「关于成立安全生产领导小组的通知」「人员任命书」三个归档项的内容
 * 直接由组织架构数据（岗位树 / 主要负责人 / 安全员 / 各岗位人员）渲染。组织架构一变，
 * 这三份文件就应自动重新生成；旧版本由 archive_pdf 天然留存，是否下架由调用方
 * （keepHistory 参数）决定，对应前端「历史版本是否需要存档」的提示。
 */
@Service
public class OrgArchiveLinkService {

    /** 归档项标题 → 生成方式（范本 code；null = 实时架构图 PDF） */
    private static final Map<String, String> LINK_TPL = new LinkedHashMap<>() {{
        put("安全组织架构", null);
        put("关于成立安全生产领导小组的通知", "LEAD_NOTICE");
        put("人员任命书", "APPOINT");
    }};

    private static final int BOX_NO = 1;

    private final OrgChartService orgChartService;
    private final ArchiveService archiveService;
    private final ArchiveItemMapper itemMapper;
    private final ArchivePdfMapper pdfMapper;
    private final JdbcTemplate jdbc;

    public OrgArchiveLinkService(OrgChartService orgChartService, ArchiveService archiveService,
                                 ArchiveItemMapper itemMapper, ArchivePdfMapper pdfMapper,
                                 JdbcTemplate jdbc) {
        this.orgChartService = orgChartService;
        this.archiveService = archiveService;
        this.itemMapper = itemMapper;
        this.pdfMapper = pdfMapper;
        this.jdbc = jdbc;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        if (t == null) throw new BizException("未识别当前企业");
        return t;
    }

    /**
     * 三个联动归档项的当前状态（供前端在架构保存后判断是否弹「自动更新」提示）。
     * hasAny = 当前年度是否已有其中任意一份成品。
     */
    public Map<String, Object> status() {
        int year = Year.now().getValue();
        Long tenant = tid();
        Long catalogId = 50000L + BOX_NO;
        List<Map<String, Object>> items = new ArrayList<>();
        boolean hasAny = false;
        for (String title : LINK_TPL.keySet()) {
            ArchiveItem it = itemMapper.selectOne(new LambdaQueryWrapper<ArchiveItem>()
                    .eq(ArchiveItem::getTenantId, tenant)
                    .eq(ArchiveItem::getCatalogId, catalogId)
                    .eq(ArchiveItem::getTitle, title)
                    .eq(ArchiveItem::getArchiveYear, year)
                    .last("limit 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", title);
            row.put("generated", it != null && it.getPdfId() != null);
            row.put("pdfId", it == null ? null : it.getPdfId());
            if (it != null && it.getPdfId() != null) {
                ArchivePdf pdf = pdfMapper.selectById(it.getPdfId());
                row.put("genTime", pdf == null ? null : pdf.getGenTime());
                hasAny = true;
            }
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("items", items);
        out.put("hasAny", hasAny);
        return out;
    }

    /**
     * 按当前组织架构重新生成三份档案柜文件。
     *
     * @param keepHistory true = 旧版本继续留存（推荐，历史版本可下载对照）；
     *                    false = 本年度旧版本立即下架（deleted=1）
     */
    @Transactional
    public Map<String, Object> regenerate(boolean keepHistory) {
        int year = Year.now().getValue();
        Long tenant = tid();
        List<Long> newPdfIds = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map.Entry<String, String> e : LINK_TPL.entrySet()) {
            String title = e.getKey();
            String tplCode = e.getValue();
            ArchivePdf pdf;
            if (tplCode == null) {
                // 架构图：与「下载 A4 横向架构图 PDF」完全同源
                pdf = archiveService.generateByBytes(title, BOX_NO, year,
                        orgChartService.pdfBytes(), "ORG_CHART");
            } else {
                pdf = archiveService.generateByTemplate(tplCode, title, BOX_NO, year, "主要负责人");
            }
            newPdfIds.add(pdf.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", title);
            row.put("pdfId", pdf.getId());
            row.put("fileSize", pdf.getFileSize());
            rows.add(row);
        }

        long archived = 0;
        if (!keepHistory) {
            // 把三份归档项本年度除最新外的旧成品下架；true 时旧记录原样保留
            for (String title : LINK_TPL.keySet()) {
                archived += jdbc.update(
                        "UPDATE `archive_pdf` SET `deleted`=1, `updated_at`=NOW() " +
                        "WHERE `tenant_id`=? AND `archive_year`=? AND `deleted`=0 " +
                        "AND `template_code` IN ('ORG_CHART','LEAD_NOTICE','APPOINT') " +
                        "AND `file_name` LIKE CONCAT(?, '%') AND `id` NOT IN (" +
                        "  SELECT `pdf_id` FROM `archive_item` WHERE `tenant_id`=? AND `catalog_id`=? " +
                        "  AND `title`=? AND `archive_year`=? AND `pdf_id` IS NOT NULL)",
                        tenant, year, title + "_", tenant, 50000L + BOX_NO, title, year);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("generated", rows);
        out.put("keepHistory", keepHistory);
        out.put("archivedOld", keepHistory ? 0 : archived);
        return out;
    }
}
