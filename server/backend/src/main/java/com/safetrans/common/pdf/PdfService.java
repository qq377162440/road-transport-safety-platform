package com.safetrans.common.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.safetrans.common.config.PdfProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * PDF 渲染引擎：openhtmltopdf + PDFBox，注册 TTF 中文字体（文泉驿正黑）。
 * 注意：PDFBox 2.0.24 不支持 CFF/OTF 字体子集化，必须用 TrueType（glyf 表）字体，
 * 因此选用文泉驿正黑（TTF）作为中文正文字体。
 */
@Slf4j
@Service
public class PdfService {

    private final PdfProperties props;

    /** 字体文件（classpath）-> family 名 */
    private static final String[][] FONTS = {
            {"fonts/wqy-zenhei.ttf", "WenQuanYi Zen Hei"},
            {"fonts/wqy-microhei.ttf", "WenQuanYi Micro Hei"}
    };

    public PdfService(PdfProperties props) {
        this.props = props;
    }

    /**
     * 渲染 HTML 为 PDF 字节流。
     *
     * @param html 完整 HTML 文档（含 <html><head><body>）
     * @return PDF 字节
     */
    public byte[] render(String html) {
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // openhtmltopdf 用 XML(SAX) 解析 HTML，HTML 命名实体（&nbsp; 等）未声明会直接抛
            // SAXParseException，必须转换为 XML 合法的数值字符引用后再交给渲染器。
            builder.withHtmlContent(toXhtml(html), null);
            registerFonts(builder);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("PDF 渲染失败", e);
            throw new IllegalStateException("PDF 生成失败：" + e.getMessage(), e);
        }
    }

    /** 把 HTML 命名实体转成 XML 合法的数值字符引用（amp/lt/gt/quot/apos 为 XML 预定义，保持原样） */
    private String toXhtml(String html) {
        if (html == null) return "";
        return html.replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;")
                .replace("&mdash;", "&#8212;")
                .replace("&ndash;", "&#8211;")
                .replace("&hellip;", "&#8230;");
    }

    /** 注册中文字体（从 classpath 提取 TTF 到临时文件并注册） */
    private void registerFonts(PdfRendererBuilder builder) {
        for (String[] font : FONTS) {
            String classpath = font[0];
            String family = font[1];
            try {
                ClassPathResource resource = new ClassPathResource(classpath);
                if (!resource.exists()) {
                    log.warn("字体不存在: {}", classpath);
                    continue;
                }
                // 提取到临时文件（openhtmltopdf useFont 需要 File）
                File tmp = File.createTempFile("safetrans-font-", ".ttf");
                tmp.deleteOnExit();
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                builder.useFont(tmp, family);
                log.info("注册字体: {} -> {}", classpath, family);
            } catch (Exception e) {
                log.warn("注册字体失败 {}: {}", classpath, e.getMessage());
            }
        }
    }
}
