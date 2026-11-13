package com.agentscopea2a.v2.pptanomaly;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ppt-anomaly/versions/{versionId}/mail")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PptMailController {
    private final JdbcTemplate jdbc;
    public PptMailController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/preview") public Map<String,Object> preview(@PathVariable long versionId,@RequestParam String department){
        String subject=jdbc.queryForObject("SELECT 'PPT 异常分拣 - ' || version_name FROM digital_qa_ppt_material_version WHERE id=?",String.class,versionId);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT sequence_no AS sequenceNo,title,source_text AS sourceText,anomaly_keyword AS anomalyKeyword,status FROM digital_qa_ppt_finding WHERE version_id=? AND auto_departments LIKE ? ORDER BY sequence_no",versionId,"%"+department+"%");
        StringBuilder body=new StringBuilder("本次版本涉及部门：").append(department).append("<br><br>");
        for(Map<String,Object> row:rows) body.append(row.get("sequenceNo")).append(". ").append(row.get("title")).append("：").append(row.get("anomalyKeyword")).append("<br>");
        return Map.of("subject",subject,"body",body.toString(),"findings",rows);
    }
    @PostMapping("/send") public Map<String,String> send(@PathVariable long versionId,@RequestParam String department,@RequestHeader("X-User-Id") String userId){
        Map<String,Object> preview=preview(versionId,department); jdbc.update("INSERT INTO digital_qa_ppt_mail_record(version_id,department,subject,body,sent_by,status,sent_at) VALUES (?,?,?,?,?,'SENT',CURRENT_TIMESTAMP)",versionId,department,preview.get("subject"),preview.get("body"),userId); return Map.of("status","SENT");
    }
}


