package com.agentscopea2a.v2.toolrouting;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import com.agentscopea2a.v2.common.PageResponse;

/** Management API for discoverability metadata only; it cannot execute a registered tool. */
@RestController
@RequestMapping("/api/tool-routing")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ToolRoutingMetadataController {

    private final ToolRoutingMetadataAdminService service;
    private final ToolRoutingScanService scanService;

    public ToolRoutingMetadataController(ToolRoutingMetadataAdminService service, ToolRoutingScanService scanService) {
        this.service = service;
        this.scanService = scanService;
    }

    @GetMapping
    public PageResponse<ToolRoutingMetadata> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100)); int p = Math.max(1, page);
        List<ToolRoutingMetadata> all = service.list(); int from = Math.min((p - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponse<>(all.subList(from, to), p, size, all.size());
    }

    @GetMapping("/{toolId}")
    public ToolRoutingMetadata get(@PathVariable String toolId) {
        return service.get(toolId);
    }

    /** 不分页全量元数据: 前端 configurations 映射必须覆盖扫描页上的所有工具, 分页会错位。 */
    @GetMapping("/all")
    public List<ToolRoutingMetadata> all() {
        return service.list();
    }

    @PutMapping("/{toolId}")
    public ToolRoutingMetadata save(@PathVariable String toolId, @RequestBody ToolRoutingMetadataInput input,
                                    @RequestHeader("X-User-Id") String userId) {
        return service.save(toolId, input, userId);
    }

    /** keyword/type 在分页前过滤: 搜索框要能搜到所有分页的内容, 客户端只过滤当前页。 */
    @GetMapping("/scan")
    public PageResponse<ToolRoutingScanCandidate> scan(@RequestParam(defaultValue="1") int page,
                                                       @RequestParam(defaultValue="20") int pageSize,
                                                       @RequestParam(required=false) String keyword,
                                                       @RequestParam(required=false) String type) {
        int size = Math.max(1, Math.min(pageSize, 100)); int p = Math.max(1, page);
        List<ToolRoutingScanCandidate> all = filterScan(scanService.scan(), keyword, type);
        int from = Math.min((p - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponse<>(all.subList(from, to), p, size, all.size());
    }

    private static List<ToolRoutingScanCandidate> filterScan(List<ToolRoutingScanCandidate> all, String keyword, String type) {
        List<ToolRoutingScanCandidate> filtered = all;
        if (type != null && !type.isBlank()) {
            filtered = filtered.stream().filter(candidate -> candidate.toolType().name().equalsIgnoreCase(type.trim())).toList();
        }
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.trim().toLowerCase();
            filtered = filtered.stream().filter(candidate -> (candidate.toolId() + " " + candidate.name() + " "
                    + candidate.description() + " " + candidate.creator()).toLowerCase().contains(needle)).toList();
        }
        return filtered;
    }

    @GetMapping("/status")
    public ToolRoutingStatusResponse status() {
        return scanService.status();
    }

}
