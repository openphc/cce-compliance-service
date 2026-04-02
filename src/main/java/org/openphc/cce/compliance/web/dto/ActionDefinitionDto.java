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
public class ActionDefinitionDto {

    private UUID id;
    private String actionType;
    private String name;
    private String description;
    private String messageTemplate;
    private String severity;
    private String target;
    private JsonNode routing;
    private String definitionCanonical;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
