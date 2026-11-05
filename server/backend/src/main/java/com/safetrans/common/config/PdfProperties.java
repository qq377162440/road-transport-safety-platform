package com.safetrans.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PDF 相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "safetrans.pdf")
public class PdfProperties {
    /** 中文字体目录（classpath:fonts 或系统绝对路径） */
    private String fontDir = "classpath:fonts";
    /** 正文字体（宋体风格） */
    private String serifFont = "Noto Serif CJK SC";
    /** 标题字体 */
    private String sansFont = "Noto Sans CJK SC";
}
