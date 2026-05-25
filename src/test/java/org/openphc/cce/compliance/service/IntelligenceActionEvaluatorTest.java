package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntelligenceActionEvaluatorTest {

    @Mock private PlanDefinitionParser planDefinitionParser;
    @Mock private ExpressionEvaluationService expressionEvaluationService;
    @Mock private ActionDefinitionService actionDefinitionService;
    @Mock private IntelligenceTriggerProducer intelligenceTriggerProducer;
    @Mock private IntelligenceEventLogRepository intelligenceEventLogRepository;
    @Mock private DeviationRepository deviationRepository;

    private IntelligenceActionEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        evaluator = new IntelligenceActionEvaluator(
                planDefinitionParser, expressionEvaluationService,
                actionDefinitionService, intelligenceTriggerProducer,
                intelligenceEventLogRepository,
                deviationRepository, objectMapper, meterRegistry, 256);
    }

    // ── Deviation Tests ──

    @Nested
    class EvaluateOnDeviation {

        @Test
        void matchingAction_createsEventLogAndPublishesEvent() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            ActionDefinition actionDef = buildActionDefinition();
            String expr = "{\"==\": [1, 1]}";

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", expr,
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq(expr), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/alert|1.0"))
                    .thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            // Event log created and published
            assertEquals(1, result.size());
            IntelligenceEventLog eventLog = result.get(0);
            assertTrue(eventLog.isPublished());
            assertNotNull(eventLog.getPublishedAt());

            // Audit context stored directly on the event log
            assertEquals("bp-high-alert", eventLog.getStepActionId());
            assertEquals("overdue", eventLog.getTriggerReason());
            assertEquals(expr, eventLog.getEvaluationExpression());
            assertNotNull(eventLog.getEvaluationContext());
            assertEquals(deviation.getId(), eventLog.getDeviationId());

            // Event published correctly
            ArgumentCaptor<IntelligenceTriggerEvent> eventCaptor =
                    ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
            verify(intelligenceTriggerProducer).publish(eventCaptor.capture());
            IntelligenceTriggerEvent event = eventCaptor.getValue();
            assertEquals(actionDef.getId(), event.getActionDefinitionId());
            assertEquals(step.getProtocolInstance().getProtocolDefinition().getId(), event.getProtocolDefinitionId());
            assertEquals(actionDef.getActionType().name(), event.getActionType());
            assertNotNull(event.getIntelligenceEventId());
            assertEquals("overdue", event.getStepState());
            assertEquals("bp-check", event.getActionId());

            // Deviation.intelligenceEventId set
            verify(deviationRepository).save(deviation);
            assertNotNull(deviation.getIntelligenceEventId());

            // Event log saved twice (unpublished → published)
            verify(intelligenceEventLogRepository, times(2)).save(any(IntelligenceEventLog.class));
        }

        @Test
        void nonMatchingAction_noEventLogCreated() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic",
                            "{\">\": [{\"var\": \"daysOverdue\"}, 30]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
            verify(intelligenceTriggerProducer, never()).publish(any());
        }

        @Test
        void multipleActions_someMatchSomeDont() {
            StepInstance step = buildStep("bp-check", StepState.MISSED);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("bp-normal", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0"),
                    buildIntelligenceAction("bp-critical-escalation", "text/fhirpath", "true",
                            "http://openphc.org/ActivityDefinition/escalation|1.0")));

            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);
            when(expressionEvaluationService.evaluate(eq("text/fhirpath"), eq("true"), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(2, result.size());

            // Audit context stored directly on each event log
            assertEquals("bp-high-alert", result.get(0).getStepActionId());
            assertEquals("bp-critical-escalation", result.get(1).getStepActionId());

            verify(intelligenceTriggerProducer, times(2)).publish(any());
        }

        @Test
        void missingActionDefinition_actionSkippedAndLogged() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/missing|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/missing|1.0"))
                    .thenThrow(new EntityNotFoundException("not found"));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
        }

        @Test
        void noIntelligenceActionsOnStep_returnsEmptyList() {
            StepInstance step = buildStep("simple-step", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of());

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(expressionEvaluationService, never()).evaluate(anyString(), anyString(), any());
        }

        @Test
        void deviationContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5));
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnDeviation(step, deviation);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(expressionEvaluationService).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("overdue", context.get("stepState").asText());
            assertEquals("overdue", context.get("deviationType").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals(0, context.get("repeatIndex").asInt());
            assertTrue(context.has("daysOverdue"));
            assertTrue(context.get("daysOverdue").asLong() >= 4);
            assertTrue(context.has("dueDate"));
            assertTrue(context.has("daysPastMissedDate"));
        }
    }

    // ── Completion Tests ──

    @Nested
    class EvaluateOnCompletion {

        @Test
        void completionWithMatchingAction_createsEventLog() {
            StepInstance step = buildStep("bp-check", StepState.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            step.setCompletionStatus(CompletionStatus.LATE);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("late-notify", "text/jsonlogic",
                            "{\"==\": [{\"var\": \"completionStatus\"}, \"late\"]}",
                            "http://openphc.org/ActivityDefinition/late-alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<IntelligenceEventLog> result = evaluator.evaluateOnCompletion(step);

            assertEquals(1, result.size());

            // Audit context stored directly on the event log with correct trigger reason
            IntelligenceEventLog eventLog = result.get(0);
            assertEquals("completion", eventLog.getTriggerReason());
            assertEquals("late-notify", eventLog.getStepActionId());
            assertNull(eventLog.getDeviationId());

            // No deviation to update
            verify(deviationRepository, never()).save(any());
        }

        @Test
        void completionContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", StepState.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            step.setCompletionStatus(CompletionStatus.ON_TIME);
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("check-rule", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnCompletion(step);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(expressionEvaluationService).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("completed", context.get("stepState").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals("on_time", context.get("completionStatus").asText());
            assertTrue(context.has("completedAt"));
            assertTrue(context.has("dueDate"));
            assertFalse(context.has("deviationType"));
        }

        @Test
        void completionNoIntelligenceActions_returnsEmpty() {
            StepInstance step = buildStep("simple-step", StepState.COMPLETED);

            mockParserReturnsIntelligenceActions(step, List.of());

            List<IntelligenceEventLog> result = evaluator.evaluateOnCompletion(step);

            assertTrue(result.isEmpty());
        }
    }

    // ── Edge Cases ──

    @Nested
    class EdgeCases {

        @Test
        void conditionEvaluationError_actionSkipped() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("error-action", "text/jsonlogic", "invalid-expr",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("parse error"));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
        }

        @Test
        void actionNotFoundInPlanDefinition_returnsEmpty() {
            StepInstance step = buildStep("nonexistent-action", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            var mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of(
                    new PlanDefinitionParser.ActionMetadata("other-action", "Other",
                            List.of(), List.of(), null, null, null, null, null, List.of(), List.of())));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
        }

        @Test
        void deviationAlreadyHasIntelligenceEventId_notOverwritten() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            UUID existingEventId = UUID.randomUUID();
            deviation.setIntelligenceEventId(existingEventId);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            evaluator.evaluateOnDeviation(step, deviation);

            // Should not overwrite existing intelligenceEventId
            verify(deviationRepository, never()).save(any());
            assertEquals(existingEventId, deviation.getIntelligenceEventId());
        }

        @Test
        void evaluationContextStoredAsJsonNode() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(1, result.size());
            JsonNode evalCtx = result.get(0).getEvaluationContext();
            assertNotNull(evalCtx);
            assertEquals("overdue", evalCtx.get("stepState").asText());
            assertEquals("overdue", evalCtx.get("deviationType").asText());
            assertEquals("bp-check", evalCtx.get("actionId").asText());
        }
    }

    // ── Metrics Tests ──

    @Nested
    class MetricsTracking {

        @Test
        void actionsEvaluatedCounter_incrementedForEachConditionCheck() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("action-1", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("action-2", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnDeviation(step, deviation);

            Counter counter = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            assertNotNull(counter);
            assertEquals(2.0, counter.count());
        }

        @Test
        void actionsFiredCounter_incrementedOnlyForMatchingActions() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("action-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("action-2", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0")));

            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            evaluator.evaluateOnDeviation(step, deviation);

            Counter evaluated = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            Counter fired = meterRegistry.find("cce.intelligence.actions.fired").counter();
            assertNotNull(evaluated);
            assertNotNull(fired);
            assertEquals(2.0, evaluated.count());
            assertEquals(1.0, fired.count());
        }

        @Test
        void noIntelligenceActions_countersNotIncremented() {
            StepInstance step = buildStep("simple-step", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsIntelligenceActions(step, List.of());

            evaluator.evaluateOnDeviation(step, deviation);

            Counter evaluated = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            Counter fired = meterRegistry.find("cce.intelligence.actions.fired").counter();
            assertNotNull(evaluated);
            assertNotNull(fired);
            assertEquals(0.0, evaluated.count());
            assertEquals(0.0, fired.count());
        }
    }

    // ── Helpers ──

    private StepInstance buildStep(String actionId, StepState state) {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/test")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        ProtocolInstance protocolInstance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .protocolCanonical("http://openphc.org/PlanDefinition/test|1.0.0")
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();

        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .state(state)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                .build();
    }

    private Deviation buildDeviation(StepInstance step, DeviationType type) {
        return Deviation.builder()
                .id(UUID.randomUUID())
                .protocolInstance(step.getProtocolInstance())
                .stepInstance(step)
                .deviationType(type)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ActionDefinition buildActionDefinition() {
        return ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/ActivityDefinition/alert")
                .version("1.0")
                .name("alert")
                .title("Alert Action")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.CommunicationRequest)
                .definition(objectMapper.createObjectNode())
                .build();
    }

    private PlanDefinitionParser.IntelligenceActionInfo buildIntelligenceAction(
            String actionId, String language, String expression, String canonical) {
        return new PlanDefinitionParser.IntelligenceActionInfo(
                actionId, language, expression, canonical, null, null);
    }

    private void mockParserReturnsIntelligenceActions(StepInstance step,
                                                       List<PlanDefinitionParser.IntelligenceActionInfo> actions) {
        var mockPlanDef = mock(PlanDefinition.class);
        when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
        when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of(
                new PlanDefinitionParser.ActionMetadata(step.getActionId(), "Test Action",
                        List.of(), List.of(), null, null, null, null, null, actions, List.of())));
    }
}
