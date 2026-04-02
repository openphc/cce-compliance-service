package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.service.ActionRunService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.ActionRunDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/action-runs")
public class ActionRunController {

    private final ActionRunService actionRunService;
    private final DtoMapper dtoMapper;

    public ActionRunController(ActionRunService actionRunService, DtoMapper dtoMapper) {
        this.actionRunService = actionRunService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public ResponseEntity<Page<ActionRunDto>> listAll(Pageable pageable) {
        Page<ActionRun> page = actionRunService.findAll(pageable);
        return ResponseEntity.ok(dtoMapper.toDtoActionRunPage(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionRunDto> getById(@PathVariable UUID id) {
        ActionRun actionRun = actionRunService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(actionRun));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<String> getStatus(@PathVariable UUID id) {
        ActionRun actionRun = actionRunService.findById(id);
        return ResponseEntity.ok(actionRun.getStatus().name());
    }

    @PostMapping("/{id}:cancel")
    public ResponseEntity<ActionRunDto> cancel(@PathVariable UUID id) {
        ActionRun cancelled = actionRunService.cancel(id);
        return ResponseEntity.ok(dtoMapper.toDto(cancelled));
    }
}
