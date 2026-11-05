package com.safetrans.tpl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 范本模板（tpl_template）。归属某个范本套（group_id）。
 */
@Data
@TableName("tpl_template")
public class TplTemplate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 所属范本套 id（对齐 tpl_set） */
    private Long groupId;

    /** 企业范本的来源系统范本 id（继承关系） */
    private Long sourceId;

    /** 稳定编码（如 RESPONSIBILITY） */
    private String code;

    /** 范本名称 */
    private String name;

    /** 类型：责任书/承诺书/告知书/任命书/制度/规程... */
    private String category;

    /** 归属档案盒 code（对齐 archive_catalog） */
    private String archiveCode;

    /** 适用岗位（逗号分隔，空=不区分岗位） */
    private String postScope;

    /** 范本内容（HTML + {{变量}}） */
    private String content;

    /** 是否盖章 */
    private Integer needSeal;

    /** 版本号 */
    private Integer version;

    /** 草稿/启用/停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
