package com.agentscopea2a.v2.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityRouterTest {
    @Test
    void recallsSkillsFromCapabilityTermsAndKeepsBoundedPool() {
        CapabilityMetadata quality = new CapabilityMetadata("quality", "质量指标", List.of("质量"),
                List.of("达标率", "打分率"), List.of("quality_metrics"), 10, true);
        CapabilityRouter router = new CapabilityRouter(3, 2);
        var result = router.recallSkillNames("查询达标率", List.of(quality),
                Map.of("quality", List.of("skill-a", "skill-b", "skill-c")));
        assertEquals(List.of("skill-a", "skill-b"), result.stream().toList());
    }
}
