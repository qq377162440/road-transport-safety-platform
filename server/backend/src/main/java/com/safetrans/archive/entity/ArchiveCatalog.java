package com.safetrans.archive.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 档案盒目录（archive_catalog）。11 盒 + 二级归档子项。
 */
@Data
@TableName("archive_catalog")
public class ArchiveCatalog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 营运类型：GENERAL普货 / DANGER危货 */
    private String bizType;

    /** level 为 MySQL 保留字，需反引号转义：1=一级档案盒，2=二级归档子项 */
    @TableField("`level`")
    private Integer level;

    private Integer sortNo;

    /** 稳定编码 */
    private String code;

    /** 档案盒/子项名称 */
    private String name;

    /** 二级子项指向一级盒 */
    private Long parentId;

    /** 是否本身就是一份可打印资料 */
    private Integer hasPdf;

    private String renewFreq;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
