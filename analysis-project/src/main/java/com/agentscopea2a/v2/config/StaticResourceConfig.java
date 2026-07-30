package com.agentscopea2a.v2.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源配置 - 确保 /index.html 和 assets/* 能被正确访问
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Spring Boot 默认已配置 classpath:/static/,这里显式声明确保生效
        registry.addResourceHandler("/")
                .addResourceLocations("classpath:/static/");
    }
}
