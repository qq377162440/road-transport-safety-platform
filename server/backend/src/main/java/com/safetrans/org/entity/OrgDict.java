package com.safetrans.org.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统职能字典（org_dict）。
 */
@Data
@TableName("org_dict")
public class OrgDict {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String dictType;

    private String dictCode;

    private String dictName;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
