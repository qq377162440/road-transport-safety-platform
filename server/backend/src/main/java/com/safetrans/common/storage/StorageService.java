package com.safetrans.common.storage;

import com.safetrans.common.config.StorageProperties;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 对象存储服务（MinIO）。负责桶初始化、上传、下载。
 */
@Slf4j
@Service
public class StorageService {

    private final StorageProperties props;
    private MinioClient client;

    public StorageService(StorageProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            StorageProperties.Minio minio = props.getMinio();
            this.client = MinioClient.builder()
                    .endpoint(minio.getEndpoint())
                    .credentials(minio.getAccessKey(), minio.getSecretKey())
                    .build();
            ensureBucket(minio.getBucket());
            log.info("MinIO 初始化完成: {} bucket={}", minio.getEndpoint(), minio.getBucket());
        } catch (Exception e) {
            log.error("MinIO 初始化失败", e);
            // 不抛异常，避免启动失败；首次使用时再报错
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("创建桶: {}", bucket);
        }
    }

    /** 上传字节流到指定对象名，返回对象名 */
    public String upload(String objectName, byte[] data, String contentType) {
        try {
            StorageProperties.Minio minio = props.getMinio();
            client.putObject(PutObjectArgs.builder()
                    .bucket(minio.getBucket())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
            return objectName;
        } catch (Exception e) {
            log.error("上传失败: {}", objectName, e);
            throw new IllegalStateException("文件上传失败：" + e.getMessage(), e);
        }
    }

    /** 下载对象字节 */
    public byte[] download(String objectName) {
        try {
            StorageProperties.Minio minio = props.getMinio();
            try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(minio.getBucket())
                    .object(objectName)
                    .build())) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.error("下载失败: {}", objectName, e);
            throw new IllegalStateException("文件下载失败：" + e.getMessage(), e);
        }
    }

    /** 生成预签名下载 URL（默认 1 小时） */
    public String presignedUrl(String objectName) {
        try {
            StorageProperties.Minio minio = props.getMinio();
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minio.getBucket())
                    .object(objectName)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception e) {
            log.error("生成预签名 URL 失败: {}", objectName, e);
            throw new IllegalStateException("生成下载链接失败：" + e.getMessage(), e);
        }
    }
}
