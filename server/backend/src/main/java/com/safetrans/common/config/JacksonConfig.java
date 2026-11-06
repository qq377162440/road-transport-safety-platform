package com.safetrans.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 全局 Jackson 配置：将 Long / long 序列化为字符串。
 *
 * 原因：后端主键、pdfId、tenantId 等为 19 位 Long，超过 JavaScript 安全整数（2^53），
 * 前端 JSON.parse 会丢失末几位精度，导致「按 id 下载 / 编辑」等操作取到错误的 id。
 * 统一以字符串传输可从根上消除该隐患（前端反序列化 "123" → Long 仍正常）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper mapper = builder.createXmlMapper(false).build();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(module);
        return mapper;
    }
}
