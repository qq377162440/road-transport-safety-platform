package com.safetrans.org.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 岗位（org_post）。
 */
@Data
@TableName("org_post")
public class OrgPost {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 上级岗位 ID（0=直属公司顶层） */
    private Long parentId;

    private Long dictId;

    /** 系统职能编码（gm/safe/...，企业侧不可改） */
    private String funcKey;

    /** 兼并的其他岗位职能编码（逗号分隔） */
    private String mergedFuncIds;

    /** 显示名（企业可自定义） */
    private String postName;

    private Integer isLeader;

    /** 是否强制岗位 */
    private Integer required;

    /** 是否启用（0禁用） */
    private Integer enabled;

    private String permissions;

    /** function 为 MySQL 保留字，需反引号转义 */
    @TableField("`function`")
    private String function;

    private String otherFunc;

    private String goals;

    private String duties;

    private String assessStd;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
