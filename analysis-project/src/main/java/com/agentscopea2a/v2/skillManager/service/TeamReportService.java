package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.TeamReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

@Service
public class TeamReportService {
 private final TeamReportMapper mapper;
 private final ObjectMapper json;
 private final MockOrgService org;
 public TeamReportService(TeamReportMapper mapper, ObjectMapper json, MockOrgService org) {
  this.mapper=mapper; this.json=json; this.org=org;
 }
 public TeamReportConfig read(String value) {
  if(value==null || value.isBlank()) return null;
  try { return json.readValue(value,TeamReportConfig.class); }
  catch(Exception e) { throw new IllegalStateException("TeamReportConfigInvalid",e); }
 }
 public String write(Object value) {
  if(value==null) return null;
  try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
 }
 /** 从关系型参数池还原接口模型；旧版 JSON 在迁移前仅用于读取。 */
 public TeamReportConfig config(SkillFlow flow) {
  TeamReportConfig header=read(flow.getTeamReportJson());
  if(header==null) return null;
  List<ReportParameter> rows=mapper.parameters(flow.getId());
  if(rows.isEmpty() && !header.targets().isEmpty()) return new TeamReportConfig(header.reportType(),header.departmentId(),header.departmentName(),header.targets().stream().map(t->legacyContact(t,flow)).toList(),header.departments());
  List<TeamReportConfig.Target> targets=rows.stream().map(row->{
   try {
    var p=json.readTree(row.getParamsJson());
    if(!(header.departments().isEmpty()?Objects.equals(header.departmentName(),p.path("dev_dept").asText()):header.departments().stream().anyMatch(d->Objects.equals(d.name(),p.path("dev_dept").asText())))
       || !Objects.equals("TEAM".equals(header.reportType())?"团队报告":"部门报告",p.path("report_type").asText())
       || ("TEAM".equals(header.reportType()) && (row.getTeamId().isEmpty() || p.path("stat_group").asText().isBlank()))
       || ("DEPARTMENT".equals(header.reportType()) && (!row.getTeamId().isEmpty() || p.has("stat_group"))))
       throw new IllegalStateException("参数池记录与任务固定部门／类型不一致");
    return legacyContact(new TeamReportConfig.Target(row.getTeamId().isEmpty()?null:row.getTeamId(),
     p.has("stat_group")?p.get("stat_group").asText():null,Boolean.TRUE.equals(row.getAutoEnabled()),row.getKeyword(),
     json.readValue(row.getToReceiversJson(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),
     json.readValue(row.getCcReceiversJson(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),row.getNotifyContactName(),row.getNotifyContactPhone(),
     row.getExecutorsJson()==null?null:json.readValue(row.getExecutorsJson(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),row.getNotifyEnabled(),row.getNotifyBodyTemplate(),p.path("report_name").asText(null)),flow).inDepartment(p.path("department_id").asText(header.departmentId()),p.path("dev_dept").asText());
   } catch(Exception e) { throw new IllegalStateException("ReportParameterPoolInvalid: 参数池数据无法解析",e); }
  }).toList();
  return new TeamReportConfig(header.reportType(),header.departmentId(),header.departmentName(),targets,header.departments());
 }
 public String header(TeamReportConfig config) {
  if(config==null) return null;
  return write(Map.of("reportType",config.reportType(),"departmentId",config.departmentId(),"departmentName",config.departmentName(),"departments",config.departments()));
 }
 /** 调用方在事务内持有任务行锁，保证配置与参数池原子更新。 */
 public void savePool(Long flowId,TeamReportConfig config) {
  if(config==null) return;
  Map<String,ReportParameter> previous=new LinkedHashMap<>();
  mapper.parameters(flowId).forEach(row->previous.put(row.getTeamId(),row));
  int order=0;
  for(var target:config.targets()) {
   ReportParameter row=new ReportParameter();row.setFlowId(flowId);row.setTeamId(target.teamId()==null?"":target.teamId());
   row.setParamsJson(write(snapshotParams(config,target)));row.setKeyword(target.keyword());
   row.setAutoEnabled(target.selected());row.setSortOrder(order++);
   row.setToReceiversJson(write(target.to()));row.setCcReceiversJson(write(target.cc()));
   row.setNotifyContactName(target.notifyContactName());row.setNotifyContactPhone(target.notifyContactPhone());
   row.setExecutorsJson(write(target.executors()));row.setNotifyEnabled(target.notifyEnabled());row.setNotifyBodyTemplate(target.notifyBodyTemplate());
   ReportParameter existing=previous.remove(row.getTeamId());
   if(existing==null) mapper.insertParameter(row);
   else {row.setId(existing.getId());mapper.updateParameter(row);}
  }
  previous.values().forEach(row->mapper.deleteParameter(flowId,row.getId()));
  mapper.updateHeader(flowId,header(config));
 }
 /** 使用与提交、编辑相同的任务锁迁移旧版 JSON 配置。 */
 public void materialize(SkillFlow flow) {
  TeamReportConfig previous=read(flow.getTeamReportJson());
  if(previous!=null && !previous.targets().isEmpty()) {
   if(!mapper.parameters(flow.getId()).isEmpty()) throw new IllegalStateException("ReportParameterPoolConflict: 请核对旧配置与参数池");
   var compatible=new TeamReportConfig(previous.reportType(),previous.departmentId(),previous.departmentName(),previous.targets().stream().map(t->legacyContact(t,flow)).toList(),previous.departments());
   savePool(flow.getId(),compatible);flow.setTeamReportJson(header(compatible));
  }
 }
 public List<Map<String,Object>> departments() { return mapper.departments(); }
 public List<TeamReportConfig.Target> candidates(String deptId) {
  String dept=departmentName(deptId);
  return mapper.teams(deptId).stream().map(t -> defaults(dept,String.valueOf(t.get("id")),String.valueOf(t.get("name")))).toList();
 }
 public TeamReportConfig.Target departmentDefaults(String deptId) {
  String dept=departmentName(deptId);
  return defaults(dept,null,null);
 }
 private TeamReportConfig.Target defaults(String dept,String id,String team) {
  var source=mapper.objectDefaults(dept,team==null?"":team);
  for(String key:List.of("keyword_count"))
   if(((Number)source.get(key)).intValue()>1)throw new IllegalStateException("TeamReportDefaultsConflict: "+dept+" / "+Objects.toString(team,"部门报告")+" 的初始关键词或联系人配置不一致");
  List<String> to=new ArrayList<>(),cc=new ArrayList<>();
  var people=mapper.recipients(dept,team==null?"":team);
  boolean roleDefaults=team!=null && people.stream().anyMatch(r->Set.of("项目经理","分管领导").contains(Objects.toString(r.get("position_role"),"").trim()));
  long leaders=people.stream().filter(r->"分管领导".equals(Objects.toString(r.get("position_role"),"").trim())).map(r->r.get("user_id")).distinct().count();
  if(team!=null && leaders>1)throw new IllegalStateException("TeamReportLeaderConflict: "+dept+" / "+team+" 只能配置一位分管领导");
  for(var r:people) {
   String role=Objects.toString(r.get("position_role"),"").trim(),user=String.valueOf(r.get("user_id")).trim();
   if(roleDefaults){if("项目经理".equals(role))to.add(user);else if("分管领导".equals(role))cc.add(user);}
   else if(team==null || role.isEmpty())("TO".equals(r.get("recipient_type"))?to:cc).add(user);
  }
  to=to.stream().distinct().toList();cc=cc.stream().distinct().toList();
  // 兼容未标记的历史默认值；带角色标记的导入数据直接使用来源表。
  if(!roleDefaults){to=org.filterExistingUserIds(to);cc=org.filterExistingUserIds(cc);}
  List<String> finalTo=to;
  String keyword=com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull((String)source.get("report_keyword"));
  return new TeamReportConfig.Target(id,team,true,keyword==null?(team==null ? dept+"部门报告" : team+"团队报告"):keyword,to,cc.stream().filter(u->!finalTo.contains(u)).toList(),
   (String)source.get("notify_contact_name"),(String)source.get("notify_contact_phone"),List.of(),true,"");
 }
 private String departmentName(String id) {
  return mapper.departments().stream().filter(d->Objects.equals(String.valueOf(d.get("id")),id))
   .map(d->String.valueOf(d.get("name"))).findFirst().orElseThrow(()->new IllegalStateException("TeamReportDepartmentUnavailable: 请选择有效部门"));
 }
 public SkillFlowDefinitionRequest prepare(SkillFlowDefinitionRequest r,SkillFlow old) {
  if(old==null) validateCreationType(r);
  TeamReportConfig prior=old==null ? null : config(old);
  TeamReportConfig c=r.teamReport()==null ? prior : r.teamReport();
  if(old!=null && r.typeId()!=null && !Objects.equals(r.typeId(),old.getTypeId()))
   throw new IllegalStateException("FlowTypeImmutable: 类型保存后不可修改");
  if(c==null) {
   return r;
  }
  if(old!=null && prior==null) throw new IllegalStateException("FlowExecutionModeImmutable: 历史任务不能切换执行模式");
  if(!Set.of("DEPARTMENT","TEAM").contains(Objects.toString(c.reportType(),""))) throw new IllegalStateException("TeamReportTypeRequired");
  if(prior!=null && !Objects.equals(prior.reportType(),c.reportType())) throw new IllegalStateException("TeamReportTypeImmutable");
  if("DEPARTMENT".equals(c.reportType()) && prior!=null && !Objects.equals(prior.departmentId(),c.departmentId())) throw new IllegalStateException("TeamReportDepartmentImmutable");
  List<TeamReportConfig.Department> departments=c.departments().isEmpty()?List.of(new TeamReportConfig.Department(c.departmentId(),c.departmentName())):c.departments();
  Map<String,String> departmentNames=new LinkedHashMap<>();
  Map<String,String> candidates=new LinkedHashMap<>();
  for(var d:departments) {
   if(departmentNames.containsKey(d.id())) throw new IllegalStateException("TeamReportDuplicateDepartment");
   String name;
   try { name=departmentName(d.id()); } catch(IllegalStateException e) {
    name=prior==null?null:prior.departments().stream().filter(p->Objects.equals(p.id(),d.id())).map(TeamReportConfig.Department::name).findFirst().orElse(Objects.equals(prior.departmentId(),d.id())?prior.departmentName():null);
    if(name==null)throw e;
   }
   departmentNames.put(d.id(),name);
   final String departmentKey=d.id();
   mapper.teams(d.id()).forEach(t->candidates.put(departmentKey+":"+t.get("id"),String.valueOf(t.get("name"))));
  }
  if(prior!=null)prior.targets().forEach(t->candidates.put(Objects.toString(t.departmentId(),prior.departmentId())+":"+t.teamId(),t.teamName()));
  String dept=departmentNames.values().iterator().next();
  List<TeamReportConfig.Target> targets=new ArrayList<>(); Set<String> seen=new HashSet<>();
  if(c.targets().isEmpty()) throw new IllegalStateException("TeamReportTargetsRequired: 请至少维护一个执行对象");
  for(var t:c.targets()) {
   boolean team="TEAM".equals(c.reportType()); String id=team ? t.teamId() : null;
   String targetDept=t.departmentId()==null?c.departmentId():t.departmentId();
   if(!departmentNames.containsKey(targetDept))throw new IllegalStateException("TeamReportDepartmentUnavailable");
   if(team && !candidates.containsKey(targetDept+":"+id)) throw new IllegalStateException("TeamReportTeamOutsideDepartment: 团队不属于当前部门");
   if(!seen.add(Objects.toString(id,"department"))) throw new IllegalStateException("TeamReportDuplicateTeam");
   List<String> to=validReportPeople(t.to()),cc=validReportPeople(t.cc());
   if(t.keyword()==null || t.keyword().isBlank()) throw new IllegalStateException("TeamReportKeywordRequired");
   var priorTarget=prior==null?null:prior.targets().stream().filter(p->Objects.equals(p.teamId(),id)).findFirst().orElse(null);
   List<String> executors=t.executors()!=null?t.executors():priorTarget==null?List.of():priorTarget.executors();
   Boolean notify=t.notifyEnabled()!=null?t.notifyEnabled():priorTarget!=null?priorTarget.notifyEnabled():old!=null?old.getNotifyEnabled():r.notifyEnabled();
   String body=t.notifyBodyTemplate()!=null?t.notifyBodyTemplate():priorTarget!=null?priorTarget.notifyBodyTemplate():old!=null?old.getNotifyBodyTemplate():r.notifyBodyTemplate();
   String reportName=t.reportName()!=null?t.reportName():priorTarget==null?null:priorTarget.reportName();
   if(reportName!=null && (reportName.isBlank() || reportName.length()>500))throw new IllegalStateException("ReportNameInvalid: 报告名称不能为空且不得超过 500 字符");
   if(body!=null && body.length()>2000)throw new IllegalStateException((t.teamName()==null?dept:t.teamName())+"：通知邮件正文模板超长（上限 2000 字符）");
   targets.add(new TeamReportConfig.Target(id,team ? candidates.get(targetDept+":"+id) : null,t.selected(),t.keyword().trim(),to,cc.stream().filter(u->!to.contains(u)).toList(),
    com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull(t.notifyContactName()==null&&priorTarget!=null?priorTarget.notifyContactName():t.notifyContactName()),
    com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull(t.notifyContactPhone()==null&&priorTarget!=null?priorTarget.notifyContactPhone():t.notifyContactPhone()),
    executors,!Boolean.FALSE.equals(notify),body==null?"":body,reportName==null?null:reportName.trim()).inDepartment(targetDept,departmentNames.get(targetDept)));
  }
  for(String departmentKey:departmentNames.keySet()) if(targets.stream().noneMatch(t->Objects.equals(t.departmentId(),departmentKey))) throw new IllegalStateException("TeamReportTargetsRequired: 每个部门至少添加一个团队");
  if("DEPARTMENT".equals(c.reportType()) && (targets.size()!=1 || departmentNames.size()!=1)) throw new IllegalStateException("TeamReportDepartmentSingleTarget");
  if(Boolean.TRUE.equals(r.autoTriggerEnabled()) && targets.stream().noneMatch(TeamReportConfig.Target::selected)) throw new IllegalStateException("TeamReportSelectionRequired: 至少选择一个自动执行对象");
  c=new TeamReportConfig(c.reportType(),departmentNames.keySet().iterator().next(),dept,targets,departmentNames.entrySet().stream().map(e->new TeamReportConfig.Department(e.getKey(),e.getValue())).toList());
  for(var n:r.nodes()) validateReferences(n.scriptParamsJson(),c.reportType());
  Long type=prior==null ? mapper.typeId("TEAM".equals(c.reportType()) ? "团队报告":"部门报告") : old.getTypeId();
  if(type==null) throw new IllegalStateException("TeamReportMigrationRequired");
  List<SkillFlowDefinitionRequest.Trigger> triggers=targets.stream().map(t->new SkillFlowDefinitionRequest.Trigger(t.keyword(),0,true)).toList();
  return new SkillFlowDefinitionRequest(r.code(),r.name(),r.description(),r.taskQuestion(),r.summaryQuestionTemplate(),r.enabled(),r.scheduleRules(),r.maxParallelism(),r.notifyEnabled(),triggers,r.nodes(),r.reportOutline(),r.notifySettings(),r.notifyContactName(),r.notifyContactPhone(),r.notifyBodyTemplate(),r.executors()==null?(old==null?List.of():mapper.legacyExecutors(old.getId())):validPeople(r.executors()),r.autoTriggerEnabled(),type,c);
 }
 private TeamReportConfig.Target legacyContact(TeamReportConfig.Target t,SkillFlow flow) {
  return new TeamReportConfig.Target(t.teamId(),t.teamName(),t.selected(),t.keyword(),t.to(),t.cc(),
   t.notifyContactName()==null?flow.getNotifyContactName():t.notifyContactName(),t.notifyContactPhone()==null?flow.getNotifyContactPhone():t.notifyContactPhone(),
   t.executors()==null?mapper.legacyExecutors(flow.getId()):t.executors(),
   t.notifyEnabled()==null?!Boolean.FALSE.equals(flow.getNotifyEnabled()):t.notifyEnabled(),
   t.notifyBodyTemplate()==null?Objects.toString(flow.getNotifyBodyTemplate(),""):t.notifyBodyTemplate(),t.reportName()).inDepartment(t.departmentId(),t.departmentName());
 }
 /** 执行权限归属于任务，并适用于该任务下的全部报告团队。 */
 public boolean mayExecute(SkillFlow flow,TeamReportConfig.Target target,String userId) {
  if(userId==null || userId.isBlank())return false;
  if(userId.equals(flow.getCreatedBy()))return true;
  List<String> people=mapper.legacyExecutors(flow.getId());
  return people.contains(userId) && org.userExists(userId);
 }
 public boolean mayTriggerKeyword(SkillFlow flow,String keyword,String userId) {
  if(Boolean.TRUE.equals(flow.getChatPublic()))return true;
  var config=config(flow);
  return config!=null && config.targets().stream().filter(t->FlowDefinitionService.normalizeKeyword(t.keyword()).equals(keyword)).anyMatch(t->mayExecute(flow,t,userId));
 }
 private void validateCreationType(SkillFlowDefinitionRequest r) {
  if(r.typeId()==null) throw new IllegalStateException("FlowTypeRequired: 请先选择已配置的任务类型");
  Map<String,Object> type=mapper.configuredType(r.typeId());
  if(type==null || !Boolean.TRUE.equals(type.get("enabled")) || r.typeId()==1L
    || Set.of("其他","默认类型").contains(Objects.toString(type.get("name"),"")))
   throw new IllegalStateException("FlowTypeUnavailable: 请选择长任务配置中已启用的非默认类型");
  String name=Objects.toString(type.get("name"),"");
  String expected="团队报告".equals(name)?"TEAM":"部门报告".equals(name)?"DEPARTMENT":null;
  if(expected!=null && (r.teamReport()==null || !expected.equals(r.teamReport().reportType()))
    || expected==null && r.teamReport()!=null)
   throw new IllegalStateException("FlowTypeConfigMismatch: 报告配置与所选任务类型不一致");
 }
 public record ReportPerson(String userId,String name,String department,String positionRole) {}
 /** 角色仅决定初始默认值，手动选择时可将来源人员放入任一收件组。 */
 public List<ReportPerson> people(String department,String team,String keyword) {
  if(department==null || department.isBlank())throw new IllegalArgumentException("ReportDepartmentRequired");
  String search=Objects.toString(keyword,"").trim().toLowerCase(Locale.ROOT);
  return mapper.recipients(department,Objects.toString(team,"")).stream().map(row->new ReportPerson(
   String.valueOf(row.get("user_id")).trim(),Objects.toString(row.get("user_name"),String.valueOf(row.get("user_id"))),department,Objects.toString(row.get("position_role"),"")))
   .filter(person->search.isEmpty()||person.userId().toLowerCase(Locale.ROOT).contains(search)||person.name().toLowerCase(Locale.ROOT).contains(search)).toList();
 }
 public List<String> filterReportPeople(Collection<String> ids) {
  Set<String> active=new HashSet<>(mapper.activePersonIds().stream().filter(Objects::nonNull).map(String::trim).toList());
  List<String> normalized=ids==null?List.of():ids.stream().filter(Objects::nonNull).map(String::trim).filter(id->!id.isBlank()).distinct().toList();
  active.addAll(org.filterExistingUserIds(normalized));
  return normalized.stream().filter(active::contains).toList();
 }
 private List<String> validReportPeople(List<String> ids) {
  List<String> normalized=ids.stream().filter(Objects::nonNull).map(String::trim).filter(id->!id.isBlank()).distinct().toList();
  List<String> valid=filterReportPeople(normalized);
  if(valid.size()!=normalized.size())throw new IllegalStateException("TeamReportRecipientInvalid: 请选择有效人员，可按姓名或统一认证号搜索");
  return valid;
 }
 private List<String> validPeople(List<String> ids) {
  List<String> normalized=ids.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isBlank()).distinct().toList();
  List<String> valid=org.filterExistingUserIds(normalized);
  if(valid.size()!=normalized.size()) throw new IllegalStateException("TeamReportRecipientInvalid: 名单含无效人员，请重新选择");
  return valid;
 }
 public static void validateReferences(String params,String type) {
  if(params==null || !params.contains("$public")) return;
  try {
   var tree=new ObjectMapper().readTree(params);
   for(var value:tree) if(value.isObject() && value.has("$public")) {
    String key=value.path("$public").asText();
    if(value.size()!=1 || !Set.of("report_type","dev_dept","stat_group","data_date").contains(key)
      || ("stat_group".equals(key) && !"TEAM".equals(type))) throw new IllegalStateException("TeamReportPublicReferenceInvalid: "+key);
   }
  } catch(IllegalStateException e) { throw e; } catch(Exception e) { throw new IllegalStateException("ScriptParamsInvalid",e); }
 }
 public Map<String,Object> publicParams(TeamReportConfig c,TeamReportConfig.Target t,LocalDate date) {
  Map<String,Object> p=storedPublicParams(c,t);p.put("data_date",date.toString());return p;
 }
 public Map<String,Object> storedPublicParams(TeamReportConfig c,TeamReportConfig.Target t) {
  Map<String,Object> p=new LinkedHashMap<>();p.put("report_type","TEAM".equals(c.reportType())?"团队报告":"部门报告");
  p.put("dev_dept",t.departmentName()==null?c.departmentName():t.departmentName());if("TEAM".equals(c.reportType()))p.put("stat_group",t.teamName());return p;
 }
 /** 报告标题保存在参数池元数据中，并在每次执行时冻结，不写入脚本公共参数。 */
 public Map<String,Object> snapshotParams(TeamReportConfig c,TeamReportConfig.Target t) {
  Map<String,Object> p=storedPublicParams(c,t);
  p.put("department_id",t.departmentId()==null?c.departmentId():t.departmentId());
  if(t.reportName()!=null && !t.reportName().isBlank())p.put("report_name",t.reportName().trim());
  return p;
 }
 public List<Map<String,Object>> progress(Long id) {
  return mapper.progress(id).stream().map(row->{
   Map<String,Object> result=new LinkedHashMap<>(row);
   Object stored=result.remove("public_params_json");
   try {var p=json.readTree(stored==null?"{}":stored.toString());
    result.put("team_name",p.path("stat_group").asText(null));result.put("department_name",p.path("dev_dept").asText(null));
    result.put("report_type",p.path("report_type").asText(null));
   }catch(Exception e){throw new IllegalStateException("ReportParameterPoolInvalid",e);}return result;
  }).toList();
 }
 public void lock(Long id) { mapper.lock(id); }
 public void requireNoManual(Long id) { if(mapper.activeManual(id)>0) throw new IllegalStateException("TeamReportManualBatchActive: 上一手动批次尚未全部结束"); }
 public boolean mayRun(SkillFlowExecution e) {
  if(e.getBatchId()==null) return true;
  mapper.lock(e.getFlowId());return mapper.predecessors(e.getFlowId(),e.getId())==0;
 }
 public void checkRetry(SkillFlowExecution e) {
  if(e==null || e.getBatchId()==null) return;
  mapper.lock(e.getFlowId());
  if(e.getStatus().terminal()) {
      requireNoManual(e.getFlowId());
      if(mapper.otherActive(e.getFlowId(),e.getId())>0) throw new IllegalStateException("TeamReportQueueBusy: 同任务还有排队或执行中的对象，请等待结束再重跑");
  }
  if(mapper.predecessors(e.getFlowId(),e.getId())>0) throw new IllegalStateException("TeamReportQueueBusy: 同任务还有排队或执行中的对象，请等待结束再重跑");
 }
}
