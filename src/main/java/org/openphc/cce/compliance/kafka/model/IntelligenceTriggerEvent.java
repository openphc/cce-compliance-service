package org.openphc.cce.compliance.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to cce.intelligence.triggers when a compliance deviation is detected
 * or an intelligence rule condition evaluates to true.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntelligenceTriggerEvent {

    private UUID id;
    private String type;
    private String subject;
    private UUID protocolInstanceId;
    private UUID stepInstanceId;
    private UUID deviationId;
    private String deviationType;
    private String stepState;
    private String actionId;
    private String protocolCanonical;
    private String facilityId;
    private OffsetDateTime detectedAt;
    private JsonNode metadata;
    private String severity;
    private String target;
    private String definitionCanonical;
    private UUID actionDefinitionId;
}
