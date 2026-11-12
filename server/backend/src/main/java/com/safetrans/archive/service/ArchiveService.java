package com.safetrans.archive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.mapper.ArchiveItemMapper;
import com.safetrans.archive.mapper.ArchivePdfMapper;
import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.tpl.entity.TplTemplate;
import com.safetrans.tpl.service.ResponsibilityService;
import com.safetrans.tpl.service.TplLibraryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HexFormat;
import java.util.List;

/**
 * 归档服务：责任制归档项登记 → PDF 生成 → 存 MinIO → 落 archive_pdf → 下载。
 */
@Service
public class ArchiveService {

    /** 安全生产责任制子项 catalog_id（V5 统一目录后的固定 id，见 gen_biz_meta.py） */
    private static final Long CATALOG_RESPONSIBILITY = 40099L;

    /** 上传留存归档项使用的 catalog 基址（盒号 boxNo 映射到 50000 + boxNo，避免与 40099 冲突） */
    private static final long BOX_CATALOG_BASE = 50000L;

    private final ArchiveItemMapper itemMapper;
    private final ArchivePdfMapper pdfMapper;
    private final ResponsibilityService responsibilityService;
    private final TplLibraryService tplLibraryService;
    private final PdfService pdfService;
    private final StorageService storageService;

    public ArchiveService(ArchiveItemMapper itemMapper, ArchivePdfMapper pdfMapper,
                          ResponsibilityService responsibilityService,
                          TplLibraryService tplLibraryService,
                          PdfService pdfService, StorageService storageService) {
        this.itemMapper = itemMapper;
        this.pdfMapper = pdfMapper;
        this.responsibilityService = responsibilityService;
        this.tplLibraryService = tplLibraryService;
        this.pdfService = pdfService;
        this.storageService = storageService;
    }

    private Long tid() {
        Long t = TenantContextHolder.tenantId();
        if (t == null) throw new BizException("未识别当前企业");
        return t;
    }

    private int currentYear() {
        return Year.now().getValue();
    }

    /** 登记一条「安全生产责任制」归档项（status=0 待生成） */
    @Transactional
    public ArchiveItem registerResponsibility() {
        ArchiveItem item = new ArchiveItem();
        item.setTenantId(tid());
        item.setCatalogId(CATALOG_RESPONSIBILITY);
        item.setDataType(1);          // 静态资料
        item.setGenMode(1);           // 真实流程
        item.setBizModule("tpl");
        item.setTitle("安全生产责任制");
        item.setSignedYear(currentYear());
        item.setArchiveYear(currentYear());
        item.setStatus(0);            // 待生成
        itemMapper.insert(item);
        return item;
    }

    /** 生成责任制 PDF 并归档：生成 → 存 MinIO → 写 archive_pdf → 更新 item 状态 */
    @Transactional
    public ArchivePdf generateResponsibility(Long itemId) {
        ArchiveItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getTenantId().equals(tid())) {
            throw new BizException("归档项不存在");
        }

        // 1. 渲染 PDF
        String html = responsibilityService.previewHtml();
        byte[] pdfBytes = pdfService.render(html);

        // 2. 计算哈希
        String hash = sha256(pdfBytes);

        // 3. 存 MinIO（对象名：租户/年度/归档项/文件名）
        String objectName = buildObjectName(item, hash);
        storageService.upload(objectName, pdfBytes, "application/pdf");

        // 4. 写 archive_pdf
        ArchivePdf pdf = new ArchivePdf();
        pdf.setTenantId(tid());
        pdf.setItemId(item.getId());
        pdf.setArchiveYear(item.getArchiveYear());
        pdf.setFileName("安全生产责任制_" + item.getArchiveYear() + ".pdf");
        pdf.setFilePath(objectName);
        pdf.setFileSize((long) pdfBytes.length);
        pdf.setTemplateCode("RESPONSIBILITY");
        pdf.setGenTime(LocalDateTime.now());
        pdf.setHash(hash);
        pdfMapper.insert(pdf);

        // 5. 更新 item 状态
        item.setStatus(1); // 已生成
        item.setPdfId(pdf.getId());
        itemMapper.updateById(item);

        return pdf;
    }

    /** 登记 + 生成（一步完成） */
    @Transactional
    public ArchivePdf registerAndGenerate() {
        ArchiveItem item = registerResponsibility();
        return generateResponsibility(item.getId());
    }

    /**
     * 按「台账范本」生成 PDF 并归档 —— 电子档案柜 与 台账范本管理 的联动核心。
     *
     * <p>范本正文取自 tpl_template 当前生效版本（企业定制优先、服务商全局兜底）。
     * 因此在范本管理里保存修改后，档案柜「生成 / 重新生成」会立即产出新内容，
     * 不再使用任何手写范文或占位文本。
     *
     * @param code     指定范本编码；为空时按归档项名称自动匹配
     * @param itemName 档案柜归档项名称（同时作为 PDF 标题）
     * @param boxNo    档案盒序号
     * @param year     归档年度
     * @param post     岗位（决定责任书类范本 {% if post %} 条件段走向）
     */
    @Transactional
    public ArchivePdf generateByTemplate(String code, String itemName, int boxNo, int year, String post) {
        TplTemplate tpl = (code != null && !code.isBlank()) ? tplLibraryService.effective(code) : null;
        if (tpl == null) {
            List<TplTemplate> matched = tplLibraryService.forItem(itemName, boxNo);
            if (matched.isEmpty()) {
                throw new BizException("该归档项暂无可用台账范本，请使用「上传」留存佐证材料");
            }
            tpl = matched.get(0);
        }
        String title = (itemName == null || itemName.isBlank()) ? tpl.getName() : itemName;
        String tplCode = tpl.getCode();

        // 1. 用当前应用范本渲染 PDF（变量 + 条件段 + 统一 A4 版式）
        byte[] pdfBytes = tplLibraryService.renderPdf(tplCode, title, year, post);
        String hash = sha256(pdfBytes);

        // 2. 存 MinIO
        Long tenant = tid();
        String safeTitle = title.replaceAll("[\\\\/?:*\"<>|]", "_");
        String objectName = String.format("%d/%d/%d/%s_%s.pdf",
                tenant, year, boxNo, hash.substring(0, 12), safeTitle);
        storageService.upload(objectName, pdfBytes, "application/pdf");

        // 3. 写 archive_pdf（记录实际使用的范本编码，便于追溯是哪版范本生成的）
        ArchivePdf pdf = new ArchivePdf();
        pdf.setTenantId(tenant);
        pdf.setArchiveYear(year);
        pdf.setFileName(safeTitle + "_" + year + ".pdf");
        pdf.setFilePath(objectName);
        pdf.setFileSize((long) pdfBytes.length);
        pdf.setTemplateCode(tplCode);
        pdf.setGenTime(LocalDateTime.now());
        pdf.setHash(hash);
        pdfMapper.insert(pdf);

        // 4. 登记/更新归档项：同盒同年度同标题只保留一条，重新生成即刷新 pdfId
        Long catalogId = BOX_CATALOG_BASE + boxNo;
        ArchiveItem item = itemMapper.selectOne(new LambdaQueryWrapper<ArchiveItem>()
                .eq(ArchiveItem::getTenantId, tenant)
                .eq(ArchiveItem::getCatalogId, catalogId)
                .eq(ArchiveItem::getTitle, title)
                .eq(ArchiveItem::getArchiveYear, year)
                .last("limit 1"));
        if (item == null) {
            item = new ArchiveItem();
            item.setTenantId(tenant);
            item.setCatalogId(catalogId);
            item.setDataType(1);      // 静态资料
            item.setGenMode(2);       // 按台账范本生成
            item.setBizModule("tpl");
            item.setTitle(title);
            item.setArchiveYear(year);
            item.setSignedYear(year);
            item.setStatus(1);
            item.setPdfId(pdf.getId());
            itemMapper.insert(item);
        } else {
            item.setStatus(1);
            item.setPdfId(pdf.getId());
            itemMapper.updateById(item);
        }
        pdf.setItemId(item.getId());
        pdfMapper.updateById(pdf);
        return pdf;
    }

    /**
     * 按「外部实时生成的 PDF 字节」归档 —— 组织架构联动等无范本归档项的通道。
     *
     * <p>与 {@link #generateByTemplate} 同一套登记规则：同盒同年度同标题只保留一条
     * archive_item，重新生成即刷新 pdfId；旧 archive_pdf 记录默认保留（历史版本可下载）。
     */
    public ArchivePdf generateByBytes(String title, int boxNo, int year, byte[] pdfBytes, String tplCode) {
        return generateByBytes(title, boxNo, year, pdfBytes, tplCode, null, "tpl");
    }

    /**
     * 按人归档（管理人员档案 / 责任书签订生效件）：
     * 标题里带姓名，因此每人一条 archive_item，person_id 落到实体字段，按人分类可查。
     */
    @Transactional
    public ArchivePdf generateByBytes(String title, int boxNo, int year, byte[] pdfBytes, String tplCode,
                                      Long personId, String bizModule) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new BizException("PDF 内容为空，归档失败");
        }
        Long tenant = tid();
        String hash = sha256(pdfBytes);
        String safeTitle = title.replaceAll("[\\\\/?:*\"<>|]", "_");
        String objectName = String.format("%d/%d/%d/%s_%s.pdf",
                tenant, year, boxNo, hash.substring(0, 12), safeTitle);
        storageService.upload(objectName, pdfBytes, "application/pdf");

        ArchivePdf pdf = new ArchivePdf();
        pdf.setTenantId(tenant);
        pdf.setArchiveYear(year);
        pdf.setFileName(safeTitle + "_" + year + ".pdf");
        pdf.setFilePath(objectName);
        pdf.setFileSize((long) pdfBytes.length);
        pdf.setTemplateCode(tplCode == null || tplCode.isBlank() ? "UPLOAD" : tplCode);
        pdf.setGenTime(LocalDateTime.now());
        pdf.setHash(hash);
        pdfMapper.insert(pdf);

        Long catalogId = BOX_CATALOG_BASE + boxNo;
        ArchiveItem item = itemMapper.selectOne(new LambdaQueryWrapper<ArchiveItem>()
                .eq(ArchiveItem::getTenantId, tenant)
                .eq(ArchiveItem::getCatalogId, catalogId)
                .eq(ArchiveItem::getTitle, title)
                .eq(ArchiveItem::getArchiveYear, year)
                .last("limit 1"));
        if (item == null) {
            item = new ArchiveItem();
            item.setTenantId(tenant);
            item.setCatalogId(catalogId);
            item.setDataType(1);
            item.setGenMode(2);
            item.setBizModule(bizModule == null || bizModule.isBlank() ? "tpl" : bizModule);
            item.setPersonId(personId);
            item.setTitle(title);
            item.setArchiveYear(year);
            item.setSignedYear(year);
            item.setStatus(1);
            item.setPdfId(pdf.getId());
            itemMapper.insert(item);
        } else {
            item.setStatus(1);
            item.setPdfId(pdf.getId());
            if (personId != null) item.setPersonId(personId);
            itemMapper.updateById(item);
        }
        pdf.setItemId(item.getId());
        pdfMapper.updateById(pdf);
        return pdf;
    }

    /** 当前年度责任制归档项列表 */    public List<ArchiveItem> listResponsibilityItems() {
        return itemMapper.selectList(new LambdaQueryWrapper<ArchiveItem>()
                .eq(ArchiveItem::getTenantId, tid())
                .eq(ArchiveItem::getCatalogId, CATALOG_RESPONSIBILITY)
                .orderByDesc(ArchiveItem::getCreatedAt));
    }

    /** 下载 PDF：返回字节 */
    public byte[] download(Long pdfId) {
        ArchivePdf pdf = pdfMapper.selectById(pdfId);
        if (pdf == null || !pdf.getTenantId().equals(tid())) {
            throw new BizException("PDF 不存在");
        }
        return storageService.download(pdf.getFilePath());
    }

    /** 获取 PDF 元数据 */
    public ArchivePdf pdfMeta(Long pdfId) {
        ArchivePdf pdf = pdfMapper.selectById(pdfId);
        if (pdf == null || !pdf.getTenantId().equals(tid())) {
            throw new BizException("PDF 不存在");
        }
        return pdf;
    }

    private String buildObjectName(ArchiveItem item, String hash) {
        return String.format("%d/%d/%d/%s.pdf",
                tid(), item.getArchiveYear(), item.getId(), hash.substring(0, 12));
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }

    /** 上传留存：存 MinIO → 写 archive_pdf + archive_item(genMode=3)，返回 PDF 记录 */
    @Transactional
    public ArchivePdf uploadFile(MultipartFile file, int boxNo, String itemTitle, int year) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要上传的文件");
        Long tid = tid();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
        if (bytes.length == 0) throw new BizException("上传文件为空");

        String hash = sha256(bytes);
        String safeTitle = itemTitle.replaceAll("[\\\\/?:*\"<>|]", "_");
        String objectName = String.format("%d/%d/%d/%s_%s", tid, year, boxNo, hash.substring(0, 12), safeTitle);
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        storageService.upload(objectName, bytes, contentType);

        ArchivePdf pdf = new ArchivePdf();
        pdf.setTenantId(tid);
        pdf.setArchiveYear(year);
        pdf.setFileName(file.getOriginalFilename());
        pdf.setFilePath(objectName);
        pdf.setFileSize(file.getSize());
        pdf.setTemplateCode("UPLOAD");
        pdf.setGenTime(LocalDateTime.now());
        pdf.setHash(hash);
        pdfMapper.insert(pdf);

        ArchiveItem item = new ArchiveItem();
        item.setTenantId(tid);
        item.setCatalogId(BOX_CATALOG_BASE + boxNo);
        item.setDataType(3);       // 证件
        item.setGenMode(3);        // 上传留存
        item.setSource(1);         // 档案柜手动上传
        item.setTitle(itemTitle);
        item.setArchiveYear(year);
        item.setStatus(1);         // 已生成
        item.setPdfId(pdf.getId());
        itemMapper.insert(item);

        return pdf;
    }

    /**
     * 按人子集合业务模块：这些归档项有专属面板（管理人员档案按人查看 / 责任书双签 / 三书签订），
     * 数据由各自面板按 biz_module 直接读取，不应以「零散文件」形式出现在盒内文件列表里。
     * 否则前端「标题未命中目录则补一行」的逻辑会把它们渲染成盒内的逐个按人文件，
     * 导致本该合并为一项的「管理人员档案」炸成 N 条「管理人员档案-张三」之类的条目。
     */
    private static final List<String> PER_PERSON_SUBCOLLECTION =
            List.of("manager_archive", "responsibility_sign", "triple_book_sign");

    /** 查询某盒某年度的上传留存归档项（供前端合并状态展示） */
    public List<ArchiveItem> listUploadItems(int boxNo, int year) {
        // 按人子集合（manager_archive / responsibility_sign / triple_book_sign）由各自专属面板按
        // biz_module 读取，不应以零散文件出现在盒内列表。注意 biz_module 为 NULL 的手动上传件
        // （如营业执照、道路运输经营许可证）必须保留：SQL 中 `biz_module NOT IN (...)` 对 NULL
        // 会得到 UNKNOWN 而把这类行误删，因此用 `biz_module IS NULL OR biz_module NOT IN (...)`。
        return itemMapper.selectList(new LambdaQueryWrapper<ArchiveItem>()
                .eq(ArchiveItem::getTenantId, tid())
                .eq(ArchiveItem::getCatalogId, BOX_CATALOG_BASE + boxNo)
                .eq(ArchiveItem::getArchiveYear, year)
                .and(w -> w.isNull(ArchiveItem::getBizModule)
                        .or().notIn(ArchiveItem::getBizModule, PER_PERSON_SUBCOLLECTION)));
    }
}
