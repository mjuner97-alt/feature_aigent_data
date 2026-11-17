package com.agentscopea2a.v2.pptanomaly;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 历史部门邮件预览及发送记录接口，当前自动通知由 PptFeedbackNotifier 负责。 */
@RestController
@RequestMapping("/api/ppt-anomaly/versions/{versionId}/mail")
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptMailController {
    private final PptAnomalyMapper persistence;
    private final DeveloperPlPersonInfoMapper people;

    /** 装配历史邮件记录持久化和用户权限查询依赖。 */
    public PptMailController(PptAnomalyMapper persistence, DeveloperPlPersonInfoMapper people) {
        this.persistence = persistence;
        this.people = people;
    }

    /** 校验已登录用户的产品部操作权限。 */
    @ModelAttribute
    public void requireProductDepartment(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireProductDepartment(userId, people);
    }
    /** 按部门生成历史邮件预览正文及对应事项列表。 */
    @GetMapping("/preview") public Map<String,Object> preview(@PathVariable long versionId,@RequestParam String department){
        String subject=persistence.mailSubject(versionId);
        List<Map<String,Object>> rows=persistence.mailFindings(versionId, "%"+department+"%");
        StringBuilder body=new StringBuilder("本次版本涉及部门：").append(department).append("<br><br>");
        for(Map<String,Object> row:rows) body.append(row.get("sequenceNo")).append(". ").append(row.get("title")).append("：").append(row.get("anomalyKeyword")).append("<br>");
        return Map.of("subject",subject,"body",body.toString(),"findings",rows);
    }
    /** 保存部门邮件发送记录并返回登记状态。 */
    @PostMapping("/send") public Map<String,String> send(@PathVariable long versionId,@RequestParam String department,@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId){
        Map<String,Object> preview=preview(versionId,department); persistence.recordMail(versionId, department, preview, userId); return Map.of("status","SENT");
    }
}
