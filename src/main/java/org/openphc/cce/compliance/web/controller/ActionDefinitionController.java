package org.openphc.cce.compliance.web.controller;

import jakarta.validation.Valid;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.enums.ActionType;
import org.openphc.cce.compliance.domain.enums.IntelligenceSeverity;
import org.openphc.cce.compliance.domain.enums.IntelligenceTarget;
import org.openphc.cce.compliance.service.ActionDefinitionService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.ActionDefinitionDto;
import org.openphc.cce.compliance.web.dto.CreateActionDefinitionRequest;
import org.openphc.cce.compliance.web.dto.UpdateActionDefinitionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/action-definitions")
public class ActionDefinitionController {

    private final ActionDefinitionService actionDefinitionService;
    private final DtoMapper dtoMapper;

    public ActionDefinitionController(ActionDefinitionService actionDefinitionService,
                                       DtoMapper dtoMapper) {
        this.actionDefinitionService = actionDefinitionService;
        this.dtoMapper = dtoMapper;
    }

    @PostMapping
    public ResponseEntity<ActionDefinitionDto> create(
            @Valid @RequestBody CreateActionDefinitionRequest request) {
        ActionDefinition entity = ActionDefinition.builder()
                .actionType(ActionType.valueOf(request.getActionType()))
                .name(request.getName())
                .description(request.getDescription())
                .messageTemplate(request.getMessageTemplate())
                .severity(IntelligenceSeverity.valueOf(request.getSeverity()))
                .target(IntelligenceTarget.valueOf(request.getTarget()))
                .routing(request.getRouting())
                .definitionCanonical(request.getDefinitionCanonical())
                .build();

        ActionDefinition created = actionDefinitionService.create(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(dtoMapper.toDto(created));
    }

    @GetMapping
    public ResponseEntity<List<ActionDefinitionDto>> listAll() {
        List<ActionDefinition> definitions = actionDefinitionService.findAll();
        return ResponseEntity.ok(dtoMapper.toDtoActionDefinitionList(definitions));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionDefinitionDto> getById(@PathVariable UUID id) {
        ActionDefinition definition = actionDefinitionService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(definition));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ActionDefinitionDto> update(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateActionDefinitionRequest request) {
        ActionDefinition updated = ActionDefinition.builder()
                .actionType(request.getActionType() != null ? ActionType.valueOf(request.getActionType()) : null)
                .name(request.getName())
                .description(request.getDescription())
                .messageTemplate(request.getMessageTemplate())
                .severity(request.getSeverity() != null ? IntelligenceSeverity.valueOf(request.getSeverity()) : null)
                .target(request.getTarget() != null ? IntelligenceTarget.valueOf(request.getTarget()) : null)
                .routing(request.getRouting())
                .build();

        ActionDefinition result = actionDefinitionService.update(id, updated);
        return ResponseEntity.ok(dtoMapper.toDto(result));
    }
}
