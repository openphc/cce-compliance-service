package org.openphc.cce.compliance.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntelligenceEventLogDto {

    private UUID id;
    private JsonNode eventPayload;
    private UUID actionDefinitionId;
    private UUID protocolInstanceId;
    private UUID stepInstanceId;
    private UUID deviationId;
    private String subject;
    private String actionType;
    private String intelligenceDestination;
    private String stepStatus;

    private String slaStatus;
    private String triggerReason;
    private String stepActionId;
    private String evaluationExpression;
    private JsonNode evaluationContext;
    private boolean published;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
}
