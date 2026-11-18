package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/** 例会反馈结果接口，提供批次汇总查询及 Word 文件下载。 */
@RestController
@RequestMapping("/api/ppt-anomaly/batches/{batchId}/result")
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptResultController {
    private final PptResultService results;
    private final PptResultWordWriter writer;
    private final DeveloperPlPersonInfoMapper people;

    /** 装配反馈汇总、Word 导出和产品部权限查询依赖。 */
    public PptResultController(PptResultService results, PptResultWordWriter writer, DeveloperPlPersonInfoMapper people) {
        this.results = results;
        this.writer = writer;
        this.people = people;
    }

    /** 校验产品部权限并返回批次最新已识别版本的反馈汇总。 */
    @GetMapping
    public PptResultService.Report result(@PathVariable long batchId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireProductDepartment(userId, people);
        return results.generate(batchId, null);
    }

    /** 校验产品部权限并下载指定版本的 Word 反馈汇总。 */
    @GetMapping("/word")
    public ResponseEntity<byte[]> word(@PathVariable long batchId, @RequestParam long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireProductDepartment(userId, people);
        return wordResponse(results.generate(batchId, versionId), writer, userId);
    }

    static ResponseEntity<byte[]> wordResponse(PptResultService.Report result, PptResultWordWriter writer, String userId) {
        byte[] bytes = writer.write(result, userId);
        String name = result.batchName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                + "_v" + result.versionNo() + "_反馈汇总.docx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }
}
