package com.safetrans.tpl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 企业范本（ent_tpl_set）：当前应用范本 + 我的范本变体。
 * 同一企业仅一个 is_current=1。
 */
@Data
@TableName("ent_tpl_set")
public class EntTplSet {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 企业租户 ID */
    private Long tenantId;

    /** 来源服务商范本套 id（实例化关系） */
    private Long sourceSetId;

    /** 范本名称 */
    private String name;

    /** 运输类型 */
    private String bizType;

    /** 区域 */
    private String region;

    /** 是否企业当前应用范本（同一企业仅一个为 1） */
    private Integer isCurrent;

    /** 是否已定制（0未改 1已改/已另存） */
    private Integer customized;

    /** 本套文件清单快照（JSON） */
    private String filesSnapshot;

    /** 从服务商套实例化日期 */
    private LocalDate appliedAt;

    /** 另存为我的范本日期 */
    private LocalDate savedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
