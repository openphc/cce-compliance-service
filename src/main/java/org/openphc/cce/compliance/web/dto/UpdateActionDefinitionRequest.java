package org.openphc.cce.compliance.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateActionDefinitionRequest {

    private String actionType;
    private String name;
    private String description;
    private String messageTemplate;
    private String severity;
    private String target;
    private JsonNode routing;
}
