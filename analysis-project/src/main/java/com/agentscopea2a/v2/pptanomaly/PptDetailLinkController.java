package com.agentscopea2a.v2.pptanomaly;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ppt-anomaly/batches/{batchId}/detail-links")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PptDetailLinkController {
    private final JdbcTemplate jdbc;
    public PptDetailLinkController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable long batchId){return jdbc.queryForList("SELECT id,alias_name AS aliasName,target_url AS targetUrl,ppt_title AS pptTitle,chapter,page_no AS pageNo,enabled FROM digital_qa_ppt_detail_link WHERE batch_id=? ORDER BY id",batchId);}
    @PostMapping public Map<String,Object> create(@PathVariable long batchId,@RequestBody Map<String,Object> body,@RequestHeader("X-User-Id") String userId){
        jdbc.update("INSERT INTO digital_qa_ppt_detail_link(batch_id,alias_name,target_url,ppt_title,chapter,page_no,created_by) VALUES (?,?,?,?,?,?,?)",batchId,body.get("aliasName"),body.get("targetUrl"),body.get("pptTitle"),body.get("chapter"),body.get("pageNo"),userId);
        return Map.of("status","SAVED");
    }
}


