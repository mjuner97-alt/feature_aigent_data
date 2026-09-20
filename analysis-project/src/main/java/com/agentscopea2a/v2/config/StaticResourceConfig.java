package com.agentscopea2a.v2.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.DispatcherType;

import java.util.EnumSet;

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

    /**
     * 注册 index.html 标题过滤器:prod 环境把标题改为「生产环境」。
     * 需同时挂 REQUEST 与 FORWARD dispatch —— SPA 路由由 SpaForwardController
     * forward 到 /index.html,默认只挂 REQUEST 会漏掉转发请求。
     */
    @Bean
    public FilterRegistrationBean<IndexTitleFilter> indexTitleFilter(Environment environment) {
        FilterRegistrationBean<IndexTitleFilter> registration =
                new FilterRegistrationBean<>(new IndexTitleFilter(environment));
        registration.addUrlPatterns("/index.html");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        registration.setOrder(0);
        return registration;
    }
}
