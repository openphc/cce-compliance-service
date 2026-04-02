package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.compliance.domain.enums.IntelligenceSeverity;
import org.openphc.cce.compliance.domain.enums.IntelligenceTarget;
import org.openphc.cce.compliance.domain.repository.ActionRunRepository;
import org.openphc.cce.compliance.web.dto.IntelligenceSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/v1/compliance/intelligence")
public class IntelligenceSummaryController {

    private final ActionRunRepository actionRunRepository;

    public IntelligenceSummaryController(ActionRunRepository actionRunRepository) {
        this.actionRunRepository = actionRunRepository;
    }

    @GetMapping("/summary")
    public ResponseEntity<IntelligenceSummaryDto> getSummary(
            @RequestParam(defaultValue = "24") int hours) {
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = to.minusHours(hours);

        long totalRuns = actionRunRepository.countByCreatedAtBetween(from, to);

        List<Object[]> severityCounts = actionRunRepository.countBySeverityInPeriod(from, to);
        long criticalCount = 0, highCount = 0, mediumCount = 0, lowCount = 0;
        for (Object[] row : severityCounts) {
            IntelligenceSeverity severity = (IntelligenceSeverity) row[0];
            long count = (Long) row[1];
            switch (severity) {
                case CRITICAL -> criticalCount = count;
                case HIGH -> highCount = count;
                case MEDIUM -> mediumCount = count;
                case LOW -> lowCount = count;
            }
        }

        List<Object[]> targetCounts = actionRunRepository.countByTargetInPeriod(from, to);
        long patientCount = 0, workerCount = 0, supervisorCount = 0, facilityCount = 0;
        for (Object[] row : targetCounts) {
            IntelligenceTarget target = (IntelligenceTarget) row[0];
            long count = (Long) row[1];
            switch (target) {
                case PATIENT -> patientCount = count;
                case ASSIGNED_WORKER -> workerCount = count;
                case SUPERVISOR -> supervisorCount = count;
                case FACILITY -> facilityCount = count;
            }
        }

        IntelligenceSummaryDto summary = IntelligenceSummaryDto.builder()
                .totalRuns(totalRuns)
                .criticalCount(criticalCount)
                .highCount(highCount)
                .mediumCount(mediumCount)
                .lowCount(lowCount)
                .patientTargetCount(patientCount)
                .workerTargetCount(workerCount)
                .supervisorTargetCount(supervisorCount)
                .facilityTargetCount(facilityCount)
                .build();

        return ResponseEntity.ok(summary);
    }
}
