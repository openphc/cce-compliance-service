package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.service.IntelligenceEventLogService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.IntelligenceEventLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/intelligence-events")
public class IntelligenceEventLogController {

    private final IntelligenceEventLogService service;
    private final DtoMapper dtoMapper;

    public IntelligenceEventLogController(IntelligenceEventLogService service, DtoMapper dtoMapper) {
        this.service = service;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public ResponseEntity<Page<IntelligenceEventLogDto>> listAll(
            @RequestParam(required = false) UUID protocolInstanceId,
            @RequestParam(required = false) UUID actionDefinitionId,
            @RequestParam(required = false) Boolean published,
            Pageable pageable) {
        Page<IntelligenceEventLog> events;
        if (protocolInstanceId != null) {
            events = service.findByProtocolInstanceId(protocolInstanceId, pageable);
        } else if (actionDefinitionId != null) {
            events = service.findByActionDefinitionId(actionDefinitionId, pageable);
        } else if (published != null) {
            events = service.findByPublished(published, pageable);
        } else {
            events = service.findAll(pageable);
        }
        return ResponseEntity.ok(events.map(dtoMapper::toDto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntelligenceEventLogDto> getById(@PathVariable UUID id) {
        IntelligenceEventLog event = service.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(event));
    }
}
