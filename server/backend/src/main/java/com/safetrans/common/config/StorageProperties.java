package com.safetrans.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（MinIO）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "safetrans.storage")
public class StorageProperties {
    /** 存储类型：minio */
    private String type = "minio";
    /** MinIO 配置 */
    private Minio minio = new Minio();

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "archive";
    }
}
