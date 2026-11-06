package com.agentscopea2a.v2.skillManager.dto;
import java.util.List;
/** 按版本显式启用；历史流程中为空时始终保留原有执行方式。 */
public record TeamReportConfig(String reportType, String departmentId, String departmentName, List<Target> targets, List<Department> departments) {
    public record Department(String id,String name) {}
    public TeamReportConfig(String reportType,String departmentId,String departmentName,List<Target> targets) { this(reportType,departmentId,departmentName,targets,null); }
    public TeamReportConfig {
        departments = departments == null || departments.isEmpty() ? (departmentId == null || departmentId.isBlank() ? List.of() : List.of(new Department(departmentId,departmentName))) : List.copyOf(departments);
        String fallbackId=departmentId, fallbackName=departmentName;
        targets = targets == null ? List.of() : targets.stream().map(t->t.departmentId()==null?t.inDepartment(fallbackId,fallbackName):t).toList();
    }
    public record Target(String teamId, String teamName, boolean selected, String keyword,
                         List<String> to, List<String> cc, String notifyContactName, String notifyContactPhone,
                         List<String> executors, Boolean notifyEnabled, String notifyBodyTemplate, String reportName, String departmentId, String departmentName) {
        public Target(String teamId,String teamName,boolean selected,String keyword,List<String> to,List<String> cc,String notifyContactName,String notifyContactPhone,List<String> executors,Boolean notifyEnabled,String notifyBodyTemplate,String reportName) {
            this(teamId,teamName,selected,keyword,to,cc,notifyContactName,notifyContactPhone,executors,notifyEnabled,notifyBodyTemplate,reportName,null,null);
        }
        public Target inDepartment(String id,String name) { return new Target(teamId,teamName,selected,keyword,to,cc,notifyContactName,notifyContactPhone,executors,notifyEnabled,notifyBodyTemplate,reportName,id,name); }
        public Target(String teamId,String teamName,boolean selected,String keyword,List<String> to,List<String> cc,
                      String notifyContactName,String notifyContactPhone,List<String> executors,Boolean notifyEnabled,String notifyBodyTemplate) {
            this(teamId,teamName,selected,keyword,to,cc,notifyContactName,notifyContactPhone,executors,notifyEnabled,notifyBodyTemplate,null);
        }
        public Target(String teamId,String teamName,boolean selected,String keyword,List<String> to,List<String> cc,
                      String notifyContactName,String notifyContactPhone) {
            this(teamId,teamName,selected,keyword,to,cc,notifyContactName,notifyContactPhone,null,null,null);
        }
        public Target(String teamId,String teamName,boolean selected,String keyword,List<String> to,List<String> cc) {
            this(teamId,teamName,selected,keyword,to,cc,null,null);
        }
        public Target { to = to == null ? List.of() : List.copyOf(to); cc = cc == null ? List.of() : List.copyOf(cc);
            executors = executors == null ? null : List.copyOf(executors); }
    }
}
