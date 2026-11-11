package com.safetrans.archive.controller;

import com.safetrans.archive.entity.ArchiveItem;
import com.safetrans.archive.entity.ArchivePdf;
import com.safetrans.archive.service.ArchiveService;
import com.safetrans.archive.service.CrewArchiveService;
import com.safetrans.archive.service.ManagerArchiveService;
import com.safetrans.archive.service.MockArchiveService;
import com.safetrans.common.auth.RequireRole;
import com.safetrans.common.storage.StorageService;
import com.safetrans.common.web.Result;
import com.safetrans.tpl.service.TplLibraryService;
import com.safetrans.tpl.service.TplPersonResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 归档接口（盒二安全生产责任制）。
 */
@RestController
@RequestMapping("/api/archive")
@RequireRole({"enterprise", "provider", "admin"})
public class ArchiveController {

    private final ArchiveService archiveService;
    private final TplLibraryService tplLibraryService;
    private final ManagerArchiveService managerArchiveService;
    private final MockArchiveService mockArchiveService;
    private final CrewArchiveService crewArchiveService;
    private final StorageService storage;

    public ArchiveController(ArchiveService archiveService, TplLibraryService tplLibraryService,
                             ManagerArchiveService managerArchiveService,
                             MockArchiveService mockArchiveService,
                             CrewArchiveService crewArchiveService,
                             StorageService storage) {
        this.archiveService = archiveService;
        this.tplLibraryService = tplLibraryService;
        this.managerArchiveService = managerArchiveService;
        this.mockArchiveService = mockArchiveService;
        this.crewArchiveService = crewArchiveService;
        this.storage = storage;
    }

    /**
     * 一键生成演示模拟数据（异步）：补齐人员 / 车辆，并让所有人档（14 项）与车档（C01~C13 + 证照）
     * 都有数据；每个归档项生成一张真实样例 PDF（「身份证样例」等）上传 MinIO，file_url 可下载可预览。
     * 立即返回 taskId，客户端用 /mock/status?taskId= 轮询进度。幂等且支持增量补齐。
     */
    @PostMapping("/mock/seed")
    public Result<Map<String, Object>> mockSeed() {
        return Result.ok(mockArchiveService.seed());
    }

    /** 查询模拟数据生成进度（不传 taskId 取最近一次） */
    @GetMapping("/mock/status")
    public Result<Map<String, Object>> mockStatus(
            @RequestParam(value = "taskId", required = false) String taskId) {
        return Result.ok(mockArchiveService.seedStatus(taskId));
    }

    /**
     * 盒一「管理人员档案」总览：按人分类的一人一档 PDF 集合。
     * 人员来源是人员基础信息里 person_type=manager 的管理人员，每人带 13 项归档目录、
     * 岗前培训 / 考核记录 / 三书 / 任命书分组统计，以及本年度档案册 PDF 状态。
     */
    @GetMapping("/manager/overview")
    public Result<Map<String, Object>> managerOverview(
            @RequestParam(value = "year", required = false) Integer year) {
        return Result.ok(managerArchiveService.overview(
                year == null ? java.time.Year.now().getValue() : year));
    }

    /**
     * 生成管理人员档案册 PDF：body.personId 为空则全员生成，否则只生成该人。
     * 归档标题带姓名、archive_item.person_id 落到本人，档案柜按人分类可查。
     */
    @PostMapping("/manager/generate")
    public Result<List<Map<String, Object>>> managerGenerate(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        int year = b.get("year") == null ? java.time.Year.now().getValue()
                : Integer.parseInt(String.valueOf(b.get("year")));
        Long personId = null;
        if (b.get("personId") != null && !String.valueOf(b.get("personId")).isBlank()
                && !"0".equals(String.valueOf(b.get("personId")))) {
            personId = Long.parseLong(String.valueOf(b.get("personId")));
        }
        return Result.ok(managerArchiveService.generate(year, personId));
    }

    /**
     * 盒十一「从业人员一人一档」总览：驾驶员 / 押运员等一线从业人员逐人列出其一人一档 14 项
     * （person_archive_item，带 person_archive_dict 名称）与证照（person_doc），每项含 file_url。
     */
    @GetMapping("/employee-archive/overview")
    public Result<Map<String, Object>> employeeArchiveOverview() {
        return Result.ok(crewArchiveService.employeeOverview());
    }

    /**
     * 盒十一「一车一档」总览：车辆逐辆列出其一车一档 13 项（vehicle_archive_item）与车辆证照
     * （vehicle_doc），每项含 file_url。
     */
    @GetMapping("/vehicle-archive/overview")
    public Result<Map<String, Object>> vehicleArchiveOverview() {
        return Result.ok(crewArchiveService.vehicleOverview());
    }

    /** 责任制归档项列表 */
    @GetMapping("/responsibility/items")
    public Result<List<ArchiveItem>> items() {
        return Result.ok(archiveService.listResponsibilityItems());
    }

    /** 登记 + 生成责任制 PDF（一步完成，旧链路保留兼容）。范本生成为服务商 / 超管增值服务。 */
    @PostMapping("/responsibility/generate")
    @RequireRole({"provider", "admin"})
    public Result<ArchivePdf> generate() {
        return Result.ok(archiveService.registerAndGenerate());
    }

    /**
     * 按「台账范本管理」当前应用范本生成/重新生成台账 PDF。
     * 改了范本后再次调用本接口，产出的 PDF 即为新范本内容。
     * 权限：仅服务商 / 超管（增值服务）；平台账号须用 X-Target-Tenant 指定目标企业，
     * 成品按目标企业的范本套 / 岗位人名 / 公司章渲染并归档到该企业的档案柜。
     */
    @PostMapping("/template/generate")
    @RequireRole({"provider", "admin"})
    public Result<ArchivePdf> generateByTemplate(@RequestBody Map<String, Object> body) {
        String code = body.get("code") == null ? "" : String.valueOf(body.get("code"));
        String itemName = body.get("itemName") == null ? "" : String.valueOf(body.get("itemName"));
        String post = body.get("post") == null ? "" : String.valueOf(body.get("post"));
        int boxNo = body.get("boxNo") == null ? 1 : Integer.parseInt(String.valueOf(body.get("boxNo")));
        int year = body.get("year") == null ? java.time.Year.now().getValue()
                : Integer.parseInt(String.valueOf(body.get("year")));
        return Result.ok(archiveService.generateByTemplate(code, itemName, boxNo, year, post));
    }

    /** 生成前检查：该范本在当前岗位下读不到哪些人名（前端据此提醒，而不是静默留白） */
    @GetMapping("/template/check")
    public Result<List<TplPersonResolver.Missing>> checkPersons(@RequestParam("code") String code,
                                                                @RequestParam(value = "post", required = false) String post) {
        return Result.ok(tplLibraryService.checkMissing(code, post));
    }

    /** 岗位人员名册（档案柜「补录人名」弹窗用） */
    @GetMapping("/template/roster")
    public Result<List<Map<String, Object>>> roster() {
        return Result.ok(tplLibraryService.roster());
    }

    /** 补录岗位人名：写入 org_member，之后生成台账即可自动读到 */
    @PostMapping("/template/assign")
    public Result<String> assign(@RequestBody Map<String, Object> body) {
        String post = body.get("post") == null ? "" : String.valueOf(body.get("post"));
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        return Result.ok(tplLibraryService.assignPerson(post, name));
    }

    /** 下载 PDF / 上传留存文件（图片、扫描件等）：按文件名后缀返回正确 Content-Type */
    @GetMapping("/pdf/{pdfId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long pdfId) {
        ArchivePdf pdf = archiveService.pdfMeta(pdfId);
        byte[] bytes = archiveService.download(pdfId);
        String fileName = pdf.getFileName() == null ? "download" : pdf.getFileName();
        // 中文原名不能直接放进 HTTP header（ISO-8859-1），会被容器丢弃导致前端拿不到文件名。
        // 用 RFC 5987 filename* 传 UTF-8 原名，同时保留纯 ASCII 的 filename 兜底。
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .contentType(resolveMediaType(fileName))
                .body(bytes);
    }

    /** 依据文件名后缀推断响应 Content-Type（上传留存可能是图片/扫描件，不能一律当 PDF） */
    private MediaType resolveMediaType(String fileName) {
        if (fileName == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.valueOf("image/bmp");
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * 按 MinIO 对象路径直接下载文件（用于盒十一「从业人员一人一档 / 一车一档」归档项预览）。
     * file_url 即 MinIO 对象名（如 5001/mock/xxx.pdf），前端点击文件名即拉取该对象并内嵌预览。
     */
    @GetMapping("/file")
    public ResponseEntity<byte[]> fileByPath(@RequestParam("path") String path) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
            return ResponseEntity.badRequest().body(new byte[0]);
        }
        byte[] bytes = storage.download(path);
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .contentType(resolveMediaType(fileName))
                .body(bytes);
    }

    /** 上传留存：把证照/保单/台账扫描件等上传至档案盒（boxNo 为数字盒号，itemTitle 为归档项标题，year 为归档年度） */
    @PostMapping("/upload")
    public Result<ArchivePdf> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("boxNo") Integer boxNo,
                                    @RequestParam("itemTitle") String itemTitle,
                                    @RequestParam("year") Integer year) {
        return Result.ok(archiveService.uploadFile(file, boxNo, itemTitle, year));
    }

    /** 某盒某年度的上传留存归档项列表 */
    @GetMapping("/items")
    public Result<List<ArchiveItem>> items(@RequestParam("boxNo") Integer boxNo,
                                          @RequestParam("year") Integer year) {
        return Result.ok(archiveService.listUploadItems(boxNo, year));
    }
}
