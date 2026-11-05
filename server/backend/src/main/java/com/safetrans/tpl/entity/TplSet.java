package com.safetrans.tpl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 范本套（tpl_set）。服务商侧全局范本套，tenant_id=0，所有企业可调用。
 */
@Data
@TableName("tpl_set")
public class TplSet {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 范本套编码 */
    private String code;

    /** 范本套名称 */
    private String name;

    /** 运输类型：GENERAL / DANGER / PASSENGER */
    private String bizType;

    /** 区域：ALL=全区域通用 */
    private String region;

    /** 是否默认套 */
    private Integer isDefault;

    /** desc 为 MySQL 保留字，需反引号转义 */
    @TableField("`desc`")
    private String desc;

    /** 1启用 0停用 */
    private Integer status;

    /** 排序 */
    private Integer sortNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
