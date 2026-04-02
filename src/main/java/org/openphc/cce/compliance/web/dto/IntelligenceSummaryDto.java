package org.openphc.cce.compliance.web.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntelligenceSummaryDto {

    private long totalRuns;
    private long criticalCount;
    private long highCount;
    private long mediumCount;
    private long lowCount;
    private long patientTargetCount;
    private long workerTargetCount;
    private long supervisorTargetCount;
    private long facilityTargetCount;
}
