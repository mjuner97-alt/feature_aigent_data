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
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.UrlShortenerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * URL 短链 Mapper - 管理URL与短码的持久化映射。
 *
 * <p>包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")}
 * 约束，必须放在此包以便 MyBatis SqlSessionFactory 扫描到。
 */
@Mapper
public interface UrlShortenerMapper {

    int insert(UrlShortenerRecord record);

    UrlShortenerRecord selectByShortCode(@Param("shortCode") String shortCode);

    int deleteExpired();
}
