package com.safetrans.tpl.dto;

import lombok.Data;

/**
 * 另存为请求（把当前范本复制为新的「我的范本」）。
 */
@Data
public class SaveAsReq {
    /** 新范本名称 */
    private String name;
}
