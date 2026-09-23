package org.openphc.cce.compliance.web;

import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.common.entity.*;
import org.openphc.cce.compliance.web.dto.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class DtoMapper {

    public IntelligenceEventLogDto toDto(IntelligenceEventLog entity) {
        return IntelligenceEventLogDto.builder()
                .id(entity.getId())
                .eventPayload(entity.getEventPayload())
                .actionDefinitionId(entity.getActionDefinitionId())
                .protocolInstanceId(entity.getProtocolInstanceId())
                .stepInstanceId(entity.getStepInstanceId())
                .deviationId(entity.getDeviationId())
                .subject(entity.getSubject())
                .actionType(entity.getActionType())
                .intelligenceDestination(entity.getIntelligenceDestination())
                .stepStatus(entity.getStepStatus())
                .slaStatus(entity.getSlaStatus())
                .triggerReason(entity.getTriggerReason())
                .stepActionId(entity.getStepActionId())
                .evaluationExpression(entity.getEvaluationExpression())
                .evaluationContext(entity.getEvaluationContext())
                .published(entity.isPublished())
                .publishedAt(entity.getPublishedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public List<IntelligenceEventLogDto> toDtoIntelligenceEventLogList(List<IntelligenceEventLog> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }
}
