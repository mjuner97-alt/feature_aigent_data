package com.agentscopea2a.v2.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 静态资源映射配置，确保 SPA 前端资源可被正确访问 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 显式映射 /assets/** 确保静态 JS/CSS 资源被正确返回
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");
        // 映射根路径和 index.html
        registry.addResourceHandler("/")
                .addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/index.html");
    }
}
