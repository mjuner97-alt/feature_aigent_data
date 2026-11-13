package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Batch;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.FeedbackRequest;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Version;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ppt-anomaly")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PptAnomalyController {
    private final PptAnomalyService service;
    private final PptRuleEngine ruleEngine;
    public PptAnomalyController(PptAnomalyService service, PptRuleEngine ruleEngine) { this.service = service; this.ruleEngine = ruleEngine; }

    @PostMapping("/batches")
    public Batch createBatch(@RequestBody Map<String, String> body, @RequestHeader("X-User-Id") String userId) {
        return service.createBatch(body.get("name"), userId);
    }

    @PostMapping(value = "/batches/{batchId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Version upload(@PathVariable long batchId, @RequestPart("file") MultipartFile file,
                          @RequestParam(required = false) String versionName,
                          @RequestHeader("X-User-Id") String userId) {
        return service.uploadVersion(batchId, file, versionName, userId);
    }

    @GetMapping("/batches/{batchId}/versions")
    public List<Version> versions(@PathVariable long batchId) { return service.listVersions(batchId); }

    @GetMapping("/versions/{versionId}/departments/{department}/overview")
    public List<PptAnomalyModels.Finding> overview(@PathVariable long versionId, @PathVariable String department,
                                                   @RequestHeader("X-User-Id") String userId) { return service.overview(versionId, department, userId); }

    @GetMapping("/versions/{versionId}/slides/{pageNo}")
    public PptAnomalyModels.Slide slide(@PathVariable long versionId, @PathVariable int pageNo) { return service.slide(versionId, pageNo); }

    @PutMapping("/findings/{findingId}/feedback")
    public Map<String, String> feedback(@PathVariable long findingId, @RequestBody FeedbackRequest request,
                                        @RequestHeader("X-User-Id") String userId) {
        service.saveFeedback(findingId, request, userId);
        return Map.of("status", "SAVED");
    }

    @PostMapping("/versions/{versionId}/analyze")
    public Map<String, Object> analyze(@PathVariable long versionId) {
        return Map.of("status", "ANALYZED", "findingCount", ruleEngine.analyze(versionId));
    }
}
