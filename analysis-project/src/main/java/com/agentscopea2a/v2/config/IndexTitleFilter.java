package com.agentscopea2a.v2.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 按运行环境重写 index.html 的 {@code <title>}。
 *
 * <p>前端只构建一次打进 static/(模板标题为「数字QA」,不带环境后缀),
 * 环境后缀由后端启动 profile 决定:prod →「数字QA-生产环境」,
 * dev →「数字QA-测试环境」;其他 profile 保持模板标题不动。
 *
 * <p>只拦截 {@code /index.html}(SPA 各路由都由 SpaForwardController
 * forward 到这里)。注册时需同时挂 REQUEST 与 FORWARD dispatch,
 * 否则 forward 过来的请求不会经过本过滤器。
 */
public class IndexTitleFilter extends OncePerRequestFilter {

    /** 目标标题;无匹配环境 profile 时为 null,保持 index.html 模板标题。 */
    private final String title;

    public IndexTitleFilter(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("prod")) {
            this.title = "数字QA-生产环境";
        } else if (active.contains("dev")) {
            this.title = "数字QA-测试环境";
        } else {
            this.title = null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        byte[] body = wrapper.getContentAsByteArray();
        if (title != null && body.length > 0 && isHtml(wrapper.getContentType())) {
            String html = new String(body, StandardCharsets.UTF_8);
            if (html.contains("<title>")) {
                html = html.replaceAll("<title>[^<]*</title>", "<title>" + title + "</title>");
                body = html.getBytes(StandardCharsets.UTF_8);
            }
        }
        // 响应头已被 wrapper 透传到底层 response,重写后只需修正长度并回写 body
        wrapper.setHeader("Content-Length", String.valueOf(body.length));
        response.getOutputStream().write(body);
    }

    /** 仅处理 HTML 响应,放行可能出现在同路径上的非 HTML 内容。 */
    private boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }
}
