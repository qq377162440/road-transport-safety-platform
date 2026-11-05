package com.safetrans.tpl.dto;

import lombok.Data;

/**
 * 应用范本请求（企业从服务商套实例化）。
 */
@Data
public class ApplyTplSetReq {
    /** 来源服务商范本套 id */
    private Long sourceSetId;
    /** 新范本名称（缺省用服务商套名） */
    private String name;
}
