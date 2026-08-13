/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * URL 短链记录 - 存储原始URL与对应短码的映射关系。
 *
 * <p>由 {@code com.agentscopea2a.v2.service.UrlShortenerService} 管理；表 {@code url_shortener}
 * 通过 {@code src/main/resources/mybatis/mapper/gauss/UrlShortenerMapper.xml} 映射。
 *
 * <p>包路径受 {@code GaussConfig.setTypeAliasesPackage("com.agentscopea2a.entity")} 约束，
 * 必须放在此包以便 MyBatis 解析 XML 中的类型别名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlShortenerRecord {

    private Long id;

    private String shortCode;

    private String originalUrl;

    /** 直接落库的内容 (content 模式). 非空时 RedirectController 直接吐字节, 不读磁盘. */
    private String content;

    /** 下载文件名, 如 "q2_1.csv". */
    private String filename;

    /** MIME 类型, 默认 "text/csv". */
    private String mimeType;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}
