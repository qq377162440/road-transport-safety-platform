package com.safetrans.tpl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 制度联动待修订记录（tpl_revision_log）。
 */
@Data
@TableName("tpl_revision_log")
public class TplRevisionLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /** 触发联动的模块，如 org */
    private String srcModule;

    /** desc 为 MySQL 保留字，需反引号转义 */
    @TableField("`desc`")
    private String desc;

    /** 0待处理 1已保存到当前范本 2已另存为新范本 3稍后处理 */
    private Integer status;

    /** 关联的 ent_tpl_set id（当前应用范本） */
    private Long linkedSetId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
