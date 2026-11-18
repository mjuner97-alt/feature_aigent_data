package com.agentscopea2a.v2.config;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 启动时统一配置 POI 的 JVM 级条目数限制，避免在并发请求中切换全局状态。 */
@Configuration
public class PoiZipSecurityConfig {
    public PoiZipSecurityConfig(@Value("${document.poi.max-file-count:10000}") long maxFileCount) {
        if (maxFileCount <= 0) {
            throw new IllegalArgumentException("document.poi.max-file-count must be positive");
        }
        // PPTX 中页面、关系、图片、图表均分别计数；保留压缩比和单条目大小检查。
        ZipSecureFile.setMaxFileCount(maxFileCount);
    }
}
