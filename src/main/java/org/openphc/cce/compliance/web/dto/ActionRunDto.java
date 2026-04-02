package org.openphc.cce.compliance.web.dto;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionRunDto {

    private UUID id;
    private UUID actionDefinitionId;
    private UUID intelligenceEventId;
    private String status;
    private String patientId;
    private UUID protocolInstanceId;
    private UUID stepInstanceId;
    private String actionType;
    private String severity;
    private String target;
    private String resolvedMessage;
    private String failureReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
