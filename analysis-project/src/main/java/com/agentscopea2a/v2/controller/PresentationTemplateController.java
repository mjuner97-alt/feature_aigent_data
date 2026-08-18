package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.v2.presentation.PresentationTemplateManageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Management API for presentation_template_registry. */
@RestController
@RequestMapping("/api/presentation-template-registry")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PresentationTemplateController {
    private final PresentationTemplateManageService service;

    public PresentationTemplateController(PresentationTemplateManageService service) {
        this.service = service;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @GetMapping
    public List<PresentationTemplateEntry> list() {
        return service.list();
    }

    @GetMapping("/get")
    public ResponseEntity<PresentationTemplateEntry> get(@RequestParam Long id) {
        PresentationTemplateEntry entry = service.get(id);
        return entry == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(entry);
    }

    @PostMapping
    public PresentationTemplateEntry create(@RequestBody PresentationTemplateEntry entry,
                                            @RequestHeader("X-User-Id") String userId) {
        return service.create(entry, userId);
    }

    @PutMapping
    public PresentationTemplateEntry update(@RequestParam Long id,
                                            @RequestBody PresentationTemplateEntry patch,
                                            @RequestHeader("X-User-Id") String userId) {
        return service.update(id, patch);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam Long id, @RequestHeader("X-User-Id") String userId) {
        service.delete(id);
    }
}
