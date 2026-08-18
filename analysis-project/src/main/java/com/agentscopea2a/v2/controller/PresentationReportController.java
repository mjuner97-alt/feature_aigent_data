package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.UrlShortenerRecord;
import com.agentscopea2a.v2.service.UrlShortenerService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/presentation/reports")
public class PresentationReportController {
    private final UrlShortenerService urlShortenerService;

    public PresentationReportController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @GetMapping(value = "/{reportId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> report(@PathVariable String reportId) {
        UrlShortenerRecord record = urlShortenerService.findRecord(reportId);
        if (record == null || record.getContent() == null || !"text/html".equalsIgnoreCase(record.getMimeType())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'")
                .body(record.getContent().getBytes(StandardCharsets.UTF_8));
    }
}
