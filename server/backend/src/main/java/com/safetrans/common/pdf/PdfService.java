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

    /**
     * 把 HTML 转成 openhtmltopdf 的 SAX 解析器能接受的 XHTML：
     * 命名实体、重复属性、未自闭合的空元素都会让解析直接抛异常（表现为 PDF 生成 500）。
     */
    private String toXhtml(String html) {
        if (html == null) return "";
        String s = html.replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;")
                .replace("&mdash;", "&#8212;")
                .replace("&ndash;", "&#8211;")
                .replace("&hellip;", "&#8230;");
        // 演示页脚本不应进入 PDF 正文
        s = s.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
        // 交互控件不属于打印正文（input 内容本身也不是合法 XHTML 空元素写法）
        s = s.replaceAll("(?is)<input\\b[^>]*>", "");
        // 合并同一标签上重复的 class 属性（用户范本常见 <div class="a" class="b">）
        java.util.regex.Matcher tag = java.util.regex.Pattern.compile("<[a-zA-Z][^>]*>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (tag.find()) {
            String t = tag.group();
            java.util.regex.Matcher c = java.util.regex.Pattern
                    .compile("class\\s*=\\s*\"([^\"]*)\"").matcher(t);
            java.util.List<int[]> spans = new java.util.ArrayList<>();
            java.util.List<String> vals = new java.util.ArrayList<>();
            while (c.find()) { spans.add(new int[]{c.start(), c.end()}); vals.add(c.group(1)); }
            if (spans.size() > 1) {
                String all = String.join(" ", vals).replaceAll("\\s+", " ").trim();
                StringBuilder b = new StringBuilder(t);
                for (int i = spans.size() - 1; i >= 1; i--) b.delete(spans.get(i)[0], spans.get(i)[1]);
                b.replace(spans.get(0)[0], spans.get(0)[1], "class=\"" + all + "\"");
                t = b.toString().replaceAll("\\s{2,}", " ");
            }
            tag.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(t));
        }
        tag.appendTail(sb);
        // XML 要求空元素自闭合
        return sb.toString().replaceAll("<(br|hr|img|meta|link)([^>]*?)\\s*/?>", "<$1$2 />");
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
