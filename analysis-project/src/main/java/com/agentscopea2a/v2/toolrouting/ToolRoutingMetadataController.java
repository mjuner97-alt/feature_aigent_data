package com.agentscopea2a.v2.toolrouting;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public List<ToolRoutingMetadata> list() {
        return service.list();
    }

    @GetMapping("/{toolId}")
    public ToolRoutingMetadata get(@PathVariable String toolId) {
        return service.get(toolId);
    }

    @PutMapping("/{toolId}")
    public ToolRoutingMetadata save(@PathVariable String toolId, @RequestBody ToolRoutingMetadataInput input) {
        return service.save(toolId, input);
    }

    @GetMapping("/scan")
    public List<ToolRoutingScanCandidate> scan() {
        return scanService.scan();
    }

    @GetMapping("/status")
    public ToolRoutingStatusResponse status() {
        return scanService.status();
    }

}
