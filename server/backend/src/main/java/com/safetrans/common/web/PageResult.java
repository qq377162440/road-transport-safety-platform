package com.safetrans.common.web;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页返回结构（A2）。
 */
@Data
public class PageResult<T> implements Serializable {

    private long total;
    private long pageNo;
    private long pageSize;
    private List<T> records;

    public static <T> PageResult<T> of(long total, long pageNo, long pageSize, List<T> records) {
        PageResult<T> p = new PageResult<>();
        p.total = total;
        p.pageNo = pageNo;
        p.pageSize = pageSize;
        p.records = records;
        return p;
    }
}
