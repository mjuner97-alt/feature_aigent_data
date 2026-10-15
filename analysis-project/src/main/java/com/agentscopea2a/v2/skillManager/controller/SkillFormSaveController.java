package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.service.SkillFormSaveService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillFormSaveController {
    private final SkillFormSaveService service;
    public SkillFormSaveController(SkillFormSaveService service) { this.service = service; }
    @PostMapping("/save")
    public Skill save(@RequestBody SkillFormSaveService.SaveRequest request,
                      @RequestHeader("X-User-Id") String userId) {
        return service.save(request, userId);
    }
}
