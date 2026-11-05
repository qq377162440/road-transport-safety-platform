package com.safetrans.archive.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 归档 PDF 产物（archive_pdf）。
 */
@Data
@TableName("archive_pdf")
public class ArchivePdf {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 关联归档项 */
    private Long itemId;

    /** 归档年度 */
    private Integer archiveYear;

    private String fileName;

    /** MinIO/OSS 存储路径 */
    private String filePath;

    private Long fileSize;

    private Integer pageCount;

    /** 使用的 PDF 模板 */
    private String templateCode;

    /** 二维码内容（追溯） */
    private String qrContent;

    private LocalDateTime genTime;

    /** 文件哈希（防篡改） */
    private String hash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
