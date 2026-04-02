package org.openphc.cce.compliance.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateActionDefinitionRequest {

    @NotBlank
    private String actionType;

    @NotBlank
    private String name;

    private String description;

    private String messageTemplate;

    @NotBlank
    private String severity;

    @NotBlank
    private String target;

    private JsonNode routing;

    @NotBlank
    private String definitionCanonical;
}
