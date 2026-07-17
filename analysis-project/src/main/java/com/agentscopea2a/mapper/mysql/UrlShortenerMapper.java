package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.UrlShortenerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * URL短链 Mapper — 管理URL与短码的持久化映射。
 */
@Mapper
public interface UrlShortenerMapper {

    int insert(UrlShortenerRecord record);

    UrlShortenerRecord selectByShortCode(@Param("shortCode") String shortCode);

    int deleteExpired();
}