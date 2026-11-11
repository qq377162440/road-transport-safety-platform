package com.safetrans.archive.service;

import com.safetrans.common.pdf.PdfService;
import com.safetrans.common.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 盒十一「从业人员一人一档 + 一车一档」档案册服务（用户 2026-11-11 需求 ③）：
 *
 * <ul>
 *   <li><b>合并版档案册 PDF</b>：把某人 / 某车的分散归档项（一人一档 14 项 + 证照，或一车一档 13 项 + 车辆证照）
 *       从 MinIO 取回后，加一张封面页合并成一本完整档案册，可直接预览 / 下载；</li>
 *   <li><b>一键下载整盒</b>：把盒十一全部从业人员与车辆的归档 PDF 按「从业人员一人一档/姓名」「一车一档/车牌」
 *       目录结构打包成 ZIP 一次下载。</li>
 * </ul>
 *
 * <p>合并用 PDFBox PDFMergerUtility（2.0.24，与 PdfService 同源），ZIP 用 JDK 自带 ZipOutputStream（UTF-8 文件名）。
 */
@Slf4j
@Service
public class CrewAlbumService {

    private final CrewArchiveService crewArchiveService;
    private final StorageService storage;
    private final PdfService pdfService;

    public CrewAlbumService(CrewArchiveService crewArchiveService, StorageService storage, PdfService pdfService) {
        this.crewArchiveService = crewArchiveService;
        this.storage = storage;
        this.pdfService = pdfService;
    }

    // ------------------------------------------------------------------
    // 合并版档案册 PDF
    // ------------------------------------------------------------------
    /** 某从业人员的一人一档档案册：封面 + 全部归档项/证照合并为单本 PDF */
    public byte[] personAlbum(long personId) {
        String name = crewArchiveService.personName(personId);
        List<Map<String, String>> files = crewArchiveService.personFileList(personId);
        return mergeAlbum(name + " · 一人一档档案册", "从业人员档案册 · 生成于 " + LocalDate.now(), files);
    }

    /** 某车辆的一车一档档案册：封面 + 全部归档项/证照合并为单本 PDF */
    public byte[] vehicleAlbum(long vehicleId) {
        String plate = crewArchiveService.vehiclePlate(vehicleId);
        List<Map<String, String>> files = crewArchiveService.vehicleFileList(vehicleId);
        return mergeAlbum(plate + " · 一车一档档案册", "车辆档案册 · 生成于 " + LocalDate.now(), files);
    }

    /** 封面 + 各归档项 PDF 合并成一本；files 为空时仅返回封面页。 */
    private byte[] mergeAlbum(String title, String sub, List<Map<String, String>> files) {
        List<byte[]> parts = new ArrayList<>();
        byte[] cover = pdfService.render(coverHtml(title, sub, files));
        parts.add(cover);
        for (Map<String, String> f : files) {
            try {
                parts.add(storage.download(f.get("url")));
            } catch (Exception e) {
                log.warn("档案册合并跳过缺失文件: {}", f.get("url"), e);
            }
        }
        if (parts.isEmpty()) throw new IllegalStateException("没有可合并的档案文件");
        if (parts.size() == 1) return parts.get(0);

        PDFMergerUtility merger = new PDFMergerUtility();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        merger.setDestinationStream(bos);
        for (byte[] p : parts) {
            merger.addSource(new ByteArrayInputStream(p));
        }
        try {
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("档案册 PDF 合并失败", e);
            throw new IllegalStateException("档案册合并失败：" + e.getMessage(), e);
        }
    }

    /** 档案册封面：标题 + 副标题 + 目录（归档项清单） */
    private String coverHtml(String title, String sub, List<Map<String, String>> files) {
        StringBuilder toc = new StringBuilder();
        if (files != null && !files.isEmpty()) {
            for (Map<String, String> f : files) {
                toc.append("<div class=\"li\">").append(esc(f.get("name"))).append("</div>");
            }
        } else {
            toc.append("<div class=\"li\">（暂无归档文件）</div>");
        }
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\"/><style>"
                + "@page{size:A4;margin:0;}"
                + "body{font-family:'WenQuanYi Zen Hei','WenQuanYi Micro Hei',sans-serif;margin:0;color:#1a1a1a;}"
                + ".pg{padding:40mm 24mm;box-sizing:border-box;min-height:297mm;}"
                + ".bd{border-top:10px solid #b91c1c;border-bottom:10px solid #b91c1c;padding:10mm 0;}"
                + ".t{font-size:30pt;font-weight:bold;text-align:center;letter-spacing:4px;}"
                + ".s{font-size:13pt;text-align:center;color:#555;margin-top:14px;}"
                + ".toc{margin-top:26mm;}"
                + ".toc-h{font-size:14pt;font-weight:bold;color:#b91c1c;border-left:5px solid #b91c1c;padding-left:10px;}"
                + ".li{font-size:11pt;color:#333;padding:5px 0 5px 14px;border-bottom:1px dashed #E2E8F0;}"
                + ".ft{margin-top:18mm;font-size:10pt;color:#999;text-align:center;}"
                + "</style></head><body><div class=\"pg\"><div class=\"bd\">"
                + "<div class=\"t\">" + esc(title) + "</div>"
                + "<div class=\"s\">" + esc(sub) + "</div></div>"
                + "<div class=\"toc\"><div class=\"toc-h\">档案目录</div>" + toc + "</div>"
                + "<div class=\"ft\">道路运输安全生产电子档案柜 · 系统自动归集，仅用于功能演示</div>"
                + "</div></body></html>";
    }

    // ------------------------------------------------------------------
    // 一键下载整盒：从业人员一人一档 + 一车一档，按目录结构打包 ZIP
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public byte[] downloadAllZip() {
        Map<String, Object> emp = crewArchiveService.employeeOverview();
        Map<String, Object> veh = crewArchiveService.vehicleOverview();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            int empCount = 0, vehCount = 0;
            for (Map<String, Object> p : (List<Map<String, Object>>) emp.get("employees")) {
                long pid = ((Number) p.get("personId")).longValue();
                String name = sanitize(String.valueOf(p.get("name")));
                String post = p.get("postName") == null ? "" : String.valueOf(p.get("postName"));
                String folder = "从业人员一人一档/" + name + (post.isEmpty() ? "" : "(" + sanitize(post) + ")/");
                for (Map<String, String> f : crewArchiveService.personFileList(pid)) {
                    addZipEntry(zos, folder + f.get("name"), f.get("url"));
                    empCount++;
                }
            }
            for (Map<String, Object> v : (List<Map<String, Object>>) veh.get("vehicles")) {
                long vid = ((Number) v.get("vehicleId")).longValue();
                String plate = sanitize(String.valueOf(v.get("plateNo")));
                String folder = "一车一档/" + plate + "/";
                for (Map<String, String> f : crewArchiveService.vehicleFileList(vid)) {
                    addZipEntry(zos, folder + f.get("name"), f.get("url"));
                    vehCount++;
                }
            }
            log.info("盒十一整盒打包完成：从业人员文件 {} 份，车辆文件 {} 份", empCount, vehCount);
        } catch (Exception e) {
            log.error("盒十一整盒打包失败", e);
            throw new IllegalStateException("整盒打包失败：" + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zos, String entryName, String objectName) throws Exception {
        try {
            byte[] data = storage.download(objectName);
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(data);
            zos.closeEntry();
        } catch (Exception e) {
            log.warn("整盒打包跳过缺失文件: {}", objectName, e);
        }
    }

    private static String sanitize(String v) {
        if (v == null) return "未知";
        return v.replaceAll("[\\\\/:*?\"<>|]", "_").trim().isEmpty() ? "未知" : v.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
