package com.agentscopea2a.v2.pptanomaly;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 材料批次的历史明细链接接口，统一校验产品部维护权限。 */
@RestController
@RequestMapping("/api/ppt-anomaly/batches/{batchId}/detail-links")
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptDetailLinkController {
    private final PptAnomalyMapper persistence;
    private final DeveloperPlPersonInfoMapper people;

    /** 装配批次明细链接持久化和权限查询依赖。 */
    public PptDetailLinkController(PptAnomalyMapper persistence, DeveloperPlPersonInfoMapper people) {
        this.persistence = persistence;
        this.people = people;
    }

    /** 校验已登录用户的产品部操作权限。 */
    @ModelAttribute
    public void requireProductDepartment(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireProductDepartment(userId, people);
    }
    /** 查询当前批次已配置的明细跳转链接。 */
    @GetMapping public List<Map<String,Object>> list(@PathVariable long batchId){return persistence.links(batchId);}
    /** 保存当前批次的明细跳转链接。 */
    @PostMapping public Map<String,Object> create(@PathVariable long batchId,@RequestBody Map<String,Object> body,@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId){
        persistence.createLink(batchId, body, userId);
        return Map.of("status","SAVED");
    }
}
