package com.agentscopea2a.controller;

import com.agentscopea2a.service.UrlShortenerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * URL短链重定向控制器 — 根据短码查询原始URL并302重定向。
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    @Autowired
    private UrlShortenerService urlShortenerService;

    /**
     * 短链重定向接口 — 根据 shortCode 查询原始URL并302重定向
     */
    @GetMapping("/redirect/download")
    public RedirectView redirect(@RequestParam("shortCode") String shortCode) {
        String originalUrl = urlShortenerService.resolve(shortCode);
        if (originalUrl != null) {
            log.info("Redirecting short_code={} to {}", shortCode, originalUrl);
            return new RedirectView(originalUrl);
        } else {
            log.warn("Short code not found or expired: {}", shortCode);
            return new RedirectView("/error/404");
        }
    }

    /**
     * 模拟下载接口 — 根据 uuid 返回示例文件
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("uuid") String uuid) {
        log.info("Download request: uuid={}", uuid);

        // 模拟生成一个简单的文本文件作为示例
        String content = "这是一个模拟下载文件\nUUID: " + uuid + "\n生成时间: " + java.time.LocalDateTime.now();
        byte[] bytes = content.getBytes();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download-" + uuid + ".txt\"")
                .body(bytes);
    }
}
