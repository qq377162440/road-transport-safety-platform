package com.safetrans.archive.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 归档子项（archive_item）。业务模块产生的"可归档成果物"。
 */
@Data
@TableName("archive_item")
public class ArchiveItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 归属的档案盒/子项（二级） */
    private Long catalogId;

    /** 资料类别：1静态 2动态 3证件 */
    private Integer dataType;

    /** 生成方式：1真实流程 2先台账后执行 3上传留存 */
    private Integer genMode;

    private Long serviceId;

    /** 来源模块 */
    private String bizModule;

    private Long bizId;

    /** 归档项标题 */
    private String title;

    private LocalDate occurDate;

    /** 签署年度 */
    private Integer signedYear;

    /** 归档年度（自然年度） */
    private Integer archiveYear;

    private Long personId;

    private Long vehicleId;

    /** 0待生成 1已生成 2已打印 3已归档 */
    private Integer status;

    /** 关联成品 PDF */
    private Long pdfId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
