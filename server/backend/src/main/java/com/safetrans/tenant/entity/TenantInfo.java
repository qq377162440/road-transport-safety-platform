package com.safetrans.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 企业主体信息（tenant_info）。
 */
@Data
@TableName("tenant_info")
public class TenantInfo {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private String creditCode;

    private String bizType;

    private Integer bizTermType;

    private LocalDate bizTermEnd;

    private String legalPerson;

    private String safetyCharge;

    private String chargePhone;

    private String region;

    private String address;

    private String industry;

    private BigDecimal investment;

    private BigDecimal regCapital;

    private String bizScope;

    private Integer vehicleScale;

    private String mainRoute;

    private String mainCargo;

    private String parkLocation;

    private BigDecimal parkArea;

    private Integer safetyStaff;

    private Integer employeeCount;

    private String website;

    private String zipcode;

    private Integer monitorOutsource;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableLogic
    private Integer deleted;
}
