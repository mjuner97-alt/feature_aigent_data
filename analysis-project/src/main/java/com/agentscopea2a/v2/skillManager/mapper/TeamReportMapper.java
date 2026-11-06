package com.agentscopea2a.v2.skillManager.mapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;
import com.agentscopea2a.v2.skillManager.entity.ReportParameter;
@Mapper
public interface TeamReportMapper {
 @Select("SELECT user_id FROM skill_flow_executor WHERE flow_id=#{id} ORDER BY id")
 List<String> legacyExecutors(@Param("id") Long id);
 @Select("SELECT id,name,enabled FROM skill_flow_type WHERE id=#{id}")
 Map<String,Object> configuredType(@Param("id") Long id);
 @Select("SELECT id,flow_id,team_id,params_json,keyword,auto_enabled,sort_order,to_receivers_json,cc_receivers_json,notify_contact_name,notify_contact_phone,executors_json,notify_enabled,notify_body_template FROM skill_flow_report_param WHERE flow_id=#{id} ORDER BY sort_order,id")
 @Results({@Result(column="flow_id",property="flowId"),@Result(column="team_id",property="teamId"),@Result(column="params_json",property="paramsJson"),@Result(column="auto_enabled",property="autoEnabled"),@Result(column="sort_order",property="sortOrder"),@Result(column="to_receivers_json",property="toReceiversJson"),@Result(column="cc_receivers_json",property="ccReceiversJson"),@Result(column="notify_contact_name",property="notifyContactName"),@Result(column="notify_contact_phone",property="notifyContactPhone"),@Result(column="executors_json",property="executorsJson"),@Result(column="notify_enabled",property="notifyEnabled"),@Result(column="notify_body_template",property="notifyBodyTemplate")})
 List<ReportParameter> parameters(@Param("id") Long id);
 @Delete("DELETE FROM skill_flow_report_param WHERE flow_id=#{flowId} AND id=#{id}")
 int deleteParameter(@Param("flowId") Long flowId,@Param("id") Long id);
 @Insert("INSERT INTO skill_flow_report_param(flow_id,team_id,params_json,keyword,auto_enabled,sort_order,to_receivers_json,cc_receivers_json,notify_contact_name,notify_contact_phone,executors_json,notify_enabled,notify_body_template) VALUES(#{flowId},#{teamId},#{paramsJson},#{keyword},#{autoEnabled},#{sortOrder},#{toReceiversJson},#{ccReceiversJson},#{notifyContactName},#{notifyContactPhone},#{executorsJson},#{notifyEnabled},#{notifyBodyTemplate})")
 int insertParameter(ReportParameter parameter);
 @Update("UPDATE skill_flow_report_param SET params_json=#{paramsJson},keyword=#{keyword},auto_enabled=#{autoEnabled},sort_order=#{sortOrder},to_receivers_json=#{toReceiversJson},cc_receivers_json=#{ccReceiversJson},notify_contact_name=#{notifyContactName},notify_contact_phone=#{notifyContactPhone},executors_json=#{executorsJson},notify_enabled=#{notifyEnabled},notify_body_template=#{notifyBodyTemplate},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND flow_id=#{flowId}")
 int updateParameter(ReportParameter parameter);
 @Update("UPDATE skill_flow SET team_report_json=#{header} WHERE id=#{id}")
 int updateHeader(@Param("id") Long id,@Param("header") String header);
 @Select("""
 SELECT COALESCE(d.id,'dept_' || MD5(p.dev_dept)) AS id,p.dev_dept AS name
 FROM team_report_notify_person p LEFT JOIN team_report_department d ON d.name=p.dev_dept
 WHERE p.enabled=TRUE AND TRIM(p.dev_dept)<>''
 GROUP BY d.id,p.dev_dept ORDER BY p.dev_dept
 """)
 List<Map<String,Object>> departments();
 @Select("""
 SELECT COALESCE(t.id,'team_' || MD5(p.dev_dept || CHR(31) || p.stat_group)) AS id,p.stat_group AS name
 FROM team_report_notify_person p LEFT JOIN team_report_department d ON d.name=p.dev_dept
 LEFT JOIN team_report_team t ON t.department_id=d.id AND t.name=p.stat_group
 WHERE p.enabled=TRUE AND TRIM(p.stat_group)<>'' AND COALESCE(d.id,'dept_' || MD5(p.dev_dept))=#{id}
 GROUP BY t.id,t.sort_order,p.dev_dept,p.stat_group
 ORDER BY COALESCE(t.sort_order,MIN(p.sort_order)),p.stat_group
 """)
 List<Map<String,Object>> teams(@Param("id") String id);
 @Select("""
 SELECT MAX(NULLIF(TRIM(report_keyword),'')) AS report_keyword,
 MAX(NULLIF(TRIM(notify_contact_name),'')) AS notify_contact_name,
 MAX(NULLIF(TRIM(notify_contact_phone),'')) AS notify_contact_phone,
 COUNT(DISTINCT NULLIF(TRIM(report_keyword),'')) AS keyword_count,
 COUNT(DISTINCT NULLIF(TRIM(notify_contact_name),'')) AS contact_count,
 COUNT(DISTINCT NULLIF(TRIM(notify_contact_phone),'')) AS phone_count
 FROM team_report_notify_person WHERE dev_dept=#{dept} AND stat_group=#{team} AND enabled=TRUE
 """)
 Map<String,Object> objectDefaults(@Param("dept") String dept,@Param("team") String team);
 @Select("SELECT user_id,user_name,position_role,recipient_type FROM team_report_notify_person WHERE dev_dept=#{dept} AND stat_group=#{team} AND enabled=TRUE ORDER BY sort_order,id")
 List<Map<String,Object>> recipients(@Param("dept") String dept,@Param("team") String team);
 @Select("SELECT DISTINCT user_id FROM team_report_notify_person WHERE enabled=TRUE")
 List<String> activePersonIds();
 @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
 @Select("SELECT id FROM skill_flow WHERE id=#{id} FOR UPDATE")
 Long lock(@Param("id") Long id);
 @Select("SELECT COUNT(*) FROM skill_flow_execution WHERE flow_id=#{id} AND batch_id IS NOT NULL AND trigger_type='MANUAL' AND status NOT IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')")
 int activeManual(@Param("id") Long id);
 @Select("SELECT COUNT(*) FROM skill_flow_execution WHERE flow_id=#{flowId} AND batch_id IS NOT NULL AND id!=#{id} AND status NOT IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED') AND (id < #{id} OR started_at IS NOT NULL)")
 @Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
 int predecessors(@Param("flowId") Long flowId,@Param("id") Long id);
 @Select("SELECT COUNT(*) FROM skill_flow_execution WHERE flow_id=#{flowId} AND batch_id IS NOT NULL AND id!=#{id} AND status NOT IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')")
 int otherActive(@Param("flowId") Long flowId,@Param("id") Long id);
 @Select("SELECT id,batch_id,status,public_params_json FROM skill_flow_execution WHERE flow_id=#{id} AND batch_id=(SELECT batch_id FROM skill_flow_execution WHERE flow_id=#{id} AND batch_id IS NOT NULL AND trigger_type='MANUAL' ORDER BY id DESC LIMIT 1) ORDER BY id")
 List<Map<String,Object>> progress(@Param("id") Long id);
 @Select("SELECT id FROM skill_flow_type WHERE name=#{name} AND enabled=TRUE")
 Long typeId(@Param("name") String name);
}
