package com.agentscopea2a.mapper.ck;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface LongTaskNodeExecutionLogMapper {
    void insert(@Param("log") Map<String, Object> log);

    Map<String, Object> detail(@Param("id") String id, @Param("userId") String userId,
                               @Param("all") boolean all);

    long count(@Param("userId") String userId, @Param("all") boolean all,
               @Param("from") String from, @Param("to") String to,
               @Param("status") String status, @Param("flowName") String flowName,
               @Param("nodeName") String nodeName, @Param("filterUserId") String filterUserId);

    List<Map<String, Object>> page(@Param("userId") String userId, @Param("all") boolean all,
                                   @Param("from") String from, @Param("to") String to,
                                   @Param("status") String status, @Param("flowName") String flowName,
                                   @Param("nodeName") String nodeName, @Param("filterUserId") String filterUserId,
                                   @Param("offset") int offset, @Param("limit") int limit);
}
