package com.safetrans.tpl.dto;

import lombok.Data;

/**
 * 新建范本套请求（服务商侧）。
 */
@Data
public class TplSetCreateReq {
    /** 范本套编码（可选，服务商可自定义，缺省自动生成） */
    private String code;
    /** 范本套名称 */
    private String name;
    /** 运输类型：GENERAL / DANGER / PASSENGER */
    private String bizType;
    /** 区域：ALL=全区域通用 */
    private String region;
    /** 是否默认套 */
    private Integer isDefault;
    /** 套说明 */
    private String desc;
    /** 排序 */
    private Integer sortNo;
}
