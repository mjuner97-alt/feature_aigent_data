package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.ScriptParamRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 脚本参数取值规则表 (script_param_rule) MyBatis Mapper, GaussDB 数据源.
 *
 * <p>由 {@code com.agentscopea2a.config.datasource.GaussConfig} 的 {@code @MapperScan} 扫描。
 */
@Mapper
public interface ScriptParamRuleMapper {

    /**
     * 按 rule_key 查询启用的规则记录.
     *
     * @param ruleKey 规则标识
     * @return 记录; 不存在或已停用返回 null
     */
    ScriptParamRule selectByRuleKey(@Param("ruleKey") String ruleKey);
}
