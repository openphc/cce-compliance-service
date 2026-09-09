package org.openphc.cce.compliance.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.web.dto.IntelligenceEventLogDto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DtoMapperTest {

    private final DtoMapper mapper = new DtoMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsEveryFieldOfTheEventLog() {
        // Guards the API contract: a field added to the entity but forgotten in the mapper silently
        // disappears from the response, which no other test would catch.
        UUID id = UUID.randomUUID();
        UUID actionDefId = UUID.randomUUID();
        UUID protocolInstanceId = UUID.randomUUID();
        UUID stepInstanceId = UUID.randomUUID();
        UUID deviationId = UUID.randomUUID();
        OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime createdAt = publishedAt.minusMinutes(1);
        JsonNode payload = objectMapper.valueToTree(Map.of("id", id.toString()));
        JsonNode context = objectMapper.valueToTree(Map.of("slaStatus", "missed"));

        IntelligenceEventLogDto dto = mapper.toDto(IntelligenceEventLog.builder()
                .id(id)
                .eventPayload(payload)
                .actionDefinitionId(actionDefId)
                .protocolInstanceId(protocolInstanceId)
                .stepInstanceId(stepInstanceId)
                .deviationId(deviationId)
                .subject("patient-1")
                .actionType("CommunicationRequest")
                .intelligenceDestination("ASSIGNED_WORKER")
                .stepStatus("not-started")
                .slaStatus("missed")
                .triggerReason("missed")
                .stepActionId("anc-visit-2-escalation")
                .evaluationExpression("{\"==\": [1, 1]}")
                .evaluationContext(context)
                .published(true)
                .publishedAt(publishedAt)
                .createdAt(createdAt)
                .build());

        assertEquals(id, dto.getId());
        assertEquals(payload, dto.getEventPayload());
        assertEquals(actionDefId, dto.getActionDefinitionId());
        assertEquals(protocolInstanceId, dto.getProtocolInstanceId());
        assertEquals(stepInstanceId, dto.getStepInstanceId());
        assertEquals(deviationId, dto.getDeviationId());
        assertEquals("patient-1", dto.getSubject());
        assertEquals("CommunicationRequest", dto.getActionType());
        assertEquals("ASSIGNED_WORKER", dto.getIntelligenceDestination());
        // the two halves of a step's condition are carried separately, not conflated
        assertEquals("not-started", dto.getStepStatus());
        assertEquals("missed", dto.getSlaStatus());
        assertEquals("missed", dto.getTriggerReason());
        assertEquals("anc-visit-2-escalation", dto.getStepActionId());
        assertEquals("{\"==\": [1, 1]}", dto.getEvaluationExpression());
        assertEquals(context, dto.getEvaluationContext());
        assertTrue(dto.isPublished());
        assertEquals(publishedAt, dto.getPublishedAt());
        assertEquals(createdAt, dto.getCreatedAt());
    }

    @Test
    void nullableFieldsSurviveAsNull() {
        IntelligenceEventLogDto dto = mapper.toDto(IntelligenceEventLog.builder()
                .id(UUID.randomUUID())
                .subject("patient-1")
                .published(false)
                .build());

        assertNull(dto.getStepInstanceId());
        assertNull(dto.getDeviationId());
        assertNull(dto.getPublishedAt());
        assertFalse(dto.isPublished());
    }

    @Test
    void mapsAList() {
        List<IntelligenceEventLogDto> dtos = mapper.toDtoIntelligenceEventLogList(List.of(
                IntelligenceEventLog.builder().id(UUID.randomUUID()).subject("a").build(),
                IntelligenceEventLog.builder().id(UUID.randomUUID()).subject("b").build()));

        assertEquals(2, dtos.size());
        assertEquals("a", dtos.get(0).getSubject());
        assertEquals("b", dtos.get(1).getSubject());
    }

    @Test
    void nullListBecomesEmpty() {
        assertTrue(mapper.toDtoIntelligenceEventLogList(null).isEmpty());
    }
}
