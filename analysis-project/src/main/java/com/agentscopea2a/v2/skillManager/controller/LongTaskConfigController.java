package com.agentscopea2a.v2.skillManager.controller;
import com.agentscopea2a.v2.skillManager.dto.*; import com.agentscopea2a.v2.skillManager.service.LongTaskConfigService; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController
@RequestMapping("/api/long-task-types")
public class LongTaskConfigController { private final LongTaskConfigService service; public LongTaskConfigController(LongTaskConfigService service){this.service=service;}
 @GetMapping public List<LongTaskTypeResponse> list(@RequestParam(required=false) Boolean enabled){return service.listTypes(enabled);}
 @PostMapping public LongTaskTypeResponse create(@RequestBody LongTaskTypeRequest req,@RequestHeader("X-User-Id") String user){return service.createType(req,user);}
 @PutMapping("/{id}") public LongTaskTypeResponse update(@PathVariable Long id,@RequestBody LongTaskTypeRequest req,@RequestHeader("X-User-Id") String user){return service.updateType(id,req,user);}
 @DeleteMapping("/{id}") public void delete(@PathVariable Long id,@RequestHeader("X-User-Id") String user){service.deleteType(id,user);}
}
