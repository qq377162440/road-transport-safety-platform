package com.safetrans.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 3 在线接口文档（A2）。访问 /swagger-ui.html。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI safetransOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("道路运输安全生产管理平台 API")
                        .version("1.0.0")
                        .description("统一返回结构 {code,msg,data}；除登录/验证码外均需 Bearer Token。"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components().addSecuritySchemes("Bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
