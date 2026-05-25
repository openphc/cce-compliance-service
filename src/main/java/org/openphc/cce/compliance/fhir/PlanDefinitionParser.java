package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PlanDefinitionParser {

    private final IParser fhirJsonParser;

    public PlanDefinitionParser(FhirContext fhirContext) {
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.fhirJsonParser.setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler());
    }

    /**
     * Parse PlanDefinition JSON into a FHIR PlanDefinition resource.
     */
    public PlanDefinition parse(String json) {
        return fhirJsonParser.parseResource(PlanDefinition.class, json);
    }

    /**
     * Extract all actions from a PlanDefinition with their metadata.
     */
    public List<ActionMetadata> extractActions(PlanDefinition planDefinition) {
        List<ActionMetadata> result = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            result.add(buildActionMetadata(action));
        }
        return result;
    }

    /**
     * Build TriggerIndex entries from a PlanDefinition by decomposing each action's
     * trigger data[].codeFilter[] into individual rows.
     * Also indexes sub-step triggers using composite actionId format: "parentActionId/subStepId".
     */
    public List<TriggerIndex> buildTriggerIndexEntries(PlanDefinition planDefinition, UUID protocolDefinitionId) {
        List<TriggerIndex> entries = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            String actionId = action.getId();

            // Index top-level action triggers
            indexActionTriggers(action, actionId, protocolDefinitionId, entries);

            // Index sub-step triggers (nested actions with type=sub-step)
            for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
                if (isSubStep(nestedAction)) {
                    String compositeActionId = actionId + "/" + nestedAction.getId();
                    indexActionTriggers(nestedAction, compositeActionId, protocolDefinitionId, entries);
                }
            }
        }
        return entries;
    }

    private void indexActionTriggers(PlanDefinition.PlanDefinitionActionComponent action, String actionId,
                                     UUID protocolDefinitionId, List<TriggerIndex> entries) {
        for (TriggerDefinition trigger : action.getTrigger()) {
            for (DataRequirement dataReq : trigger.getData()) {
                String resourceType = dataReq.getType();
                if (resourceType == null) continue;

                List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters = dataReq.getCodeFilter();
                if (codeFilters == null || codeFilters.isEmpty()) {
                    entries.add(buildTriggerIndex(resourceType, "", "", "", protocolDefinitionId, actionId));
                    continue;
                }

                for (DataRequirement.DataRequirementCodeFilterComponent cf : codeFilters) {
                    String path = cf.getPath() != null ? cf.getPath() : "";
                    for (Coding coding : cf.getCode()) {
                        String system = coding.getSystem() != null ? coding.getSystem() : "";
                        String code = coding.getCode() != null ? coding.getCode()
                                : (coding.getDisplay() != null ? coding.getDisplay() : "");
                        entries.add(buildTriggerIndex(resourceType, path, system, code, protocolDefinitionId, actionId));
                    }
                }
            }
        }
    }

    /**
     * Extract condition-only triggers — triggers that have no data[] section, only a condition.
     * These are held in-memory and evaluated via Tier 2 for every inbound event.
     */
    public List<ConditionOnlyTriggerInfo> extractConditionOnlyTriggers(PlanDefinition planDefinition) {
        List<ConditionOnlyTriggerInfo> result = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            String actionId = action.getId();

            for (TriggerDefinition trigger : action.getTrigger()) {
                if (hasData(trigger)) continue;

                Expression condition = getCondition(trigger);
                if (condition != null) {
                    result.add(new ConditionOnlyTriggerInfo(
                            actionId,
                            condition.getLanguage(),
                            condition.getExpression()
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Validate triggers at load time. Rejects any trigger that has no data[] AND no condition.
     *
     * @throws IllegalArgumentException if an invalid trigger is found
     */
    public void validateTriggers(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            for (TriggerDefinition trigger : action.getTrigger()) {
                if (!hasData(trigger) && getCondition(trigger) == null) {
                    throw new IllegalArgumentException(
                            "Action '" + action.getId() + "' has a trigger with no data[] and no condition. " +
                                    "At least one of data[] or condition must be present.");
                }
            }
        }
    }

    private ActionMetadata buildActionMetadata(PlanDefinition.PlanDefinitionActionComponent action) {
        // Extract triggers info
        List<TriggerInfo> triggers = new ArrayList<>();
        for (TriggerDefinition trigger : action.getTrigger()) {
            List<DataRequirementInfo> dataReqs = new ArrayList<>();
            for (DataRequirement dr : trigger.getData()) {
                List<CodeFilterInfo> codeFilters = new ArrayList<>();
                if (dr.getCodeFilter() != null) {
                    for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
                        List<CodingInfo> codes = new ArrayList<>();
                        for (Coding c : cf.getCode()) {
                            codes.add(new CodingInfo(c.getSystem(), c.getCode()));
                        }
                        codeFilters.add(new CodeFilterInfo(cf.getPath(), codes));
                    }
                }
                dataReqs.add(new DataRequirementInfo(dr.getType(), codeFilters));
            }

            ConditionInfo conditionInfo = null;
            Expression cond = getCondition(trigger);
            if (cond != null) {
                conditionInfo = new ConditionInfo(cond.getLanguage(), cond.getExpression());
            }

            triggers.add(new TriggerInfo(dataReqs, conditionInfo));
        }

        // Extract related actions
        List<RelatedActionInfo> relatedActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionRelatedActionComponent ra : action.getRelatedAction()) {
            Duration offset = ra.getOffsetDuration();
            RelatedActionInfo info = new RelatedActionInfo(
                    ra.getActionId(),
                    ra.getRelationship() != null ? ra.getRelationship().toCode() : null,
                    offset != null ? offset.getValue() : null,
                    offset != null && offset.getUnit() != null ? offset.getUnit() : null
            );
            relatedActions.add(info);
        }

        // Extract timing
        TimingInfo timingInfo = null;
        if (action.hasTiming() && action.getTiming() instanceof Timing timing) {
            Timing.TimingRepeatComponent repeat = timing.getRepeat();
            if (repeat != null) {
                timingInfo = new TimingInfo(
                        repeat.hasCount() ? repeat.getCount() : null,
                        repeat.hasFrequency() ? repeat.getFrequency() : null,
                        repeat.hasPeriod() ? repeat.getPeriod() : null,
                        repeat.hasPeriodUnit() ? repeat.getPeriodUnit().toCode() : null
                );
            }
        }

        // Extract tolerance-days extension
        Integer toleranceDays = extractToleranceDays(action);

        // Extract requiredBehavior
        String requiredBehavior = action.hasRequiredBehavior()
                ? action.getRequiredBehavior().toCode()
                : null;

        // Extract groupingBehavior and selectionBehavior
        String groupingBehavior = action.hasGroupingBehavior()
                ? action.getGroupingBehavior().toCode()
                : null;
        String selectionBehavior = action.hasSelectionBehavior()
                ? action.getSelectionBehavior().toCode()
                : null;

        // Classify nested actions by type
        List<IntelligenceActionInfo> intelligenceActions = new ArrayList<>();
        List<SubStepActionInfo> subSteps = new ArrayList<>();
        classifyNestedActions(action, intelligenceActions, subSteps);

        return new ActionMetadata(
                action.getId(),
                action.getTitle(),
                triggers,
                relatedActions,
                timingInfo,
                toleranceDays,
                requiredBehavior,
                groupingBehavior,
                selectionBehavior,
                intelligenceActions,
                subSteps
        );
    }

    /**
     * Classify nested actions into intelligence actions (fire-event) and sub-steps.
     * Classification rule:
     * - Explicit type coding "sub-step" → sub-step
     * - Explicit type coding "fire-event" → intelligence action
     * - No type but has definitionCanonical + condition + severity → intelligence action (backward compat)
     * - No type and has triggers → sub-step
     */
    private void classifyNestedActions(PlanDefinition.PlanDefinitionActionComponent parentAction,
                                       List<IntelligenceActionInfo> intelligenceActions,
                                       List<SubStepActionInfo> subSteps) {
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : parentAction.getAction()) {
            if (isSubStep(nestedAction)) {
                subSteps.add(buildSubStepActionInfo(nestedAction));
            } else {
                IntelligenceActionInfo intelligenceAction = buildIntelligenceActionInfo(nestedAction);
                if (intelligenceAction != null) {
                    intelligenceActions.add(intelligenceAction);
                }
            }
        }
    }

    /**
     * Determine if a nested action is a sub-step.
     * A nested action is a sub-step if:
     * 1. It has explicit type coding with code "sub-step", OR
     * 2. It has no type but has triggers (indicating it's an event-matched step)
     */
    private boolean isSubStep(PlanDefinition.PlanDefinitionActionComponent action) {
        // Check explicit type coding
        if (action.hasType()) {
            CodeableConcept type = action.getType();
            for (Coding coding : type.getCoding()) {
                if ("sub-step".equals(coding.getCode())) {
                    return true;
                }
            }
            // Has explicit type but not sub-step — not a sub-step
            return false;
        }
        // No explicit type: sub-step if it has triggers (intelligence actions have condition+definitionCanonical instead)
        return !action.getTrigger().isEmpty();
    }

    private SubStepActionInfo buildSubStepActionInfo(PlanDefinition.PlanDefinitionActionComponent action) {
        // Extract triggers
        List<TriggerInfo> triggers = new ArrayList<>();
        for (TriggerDefinition trigger : action.getTrigger()) {
            List<DataRequirementInfo> dataReqs = new ArrayList<>();
            for (DataRequirement dr : trigger.getData()) {
                List<CodeFilterInfo> codeFilters = new ArrayList<>();
                if (dr.getCodeFilter() != null) {
                    for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
                        List<CodingInfo> codes = new ArrayList<>();
                        for (Coding c : cf.getCode()) {
                            codes.add(new CodingInfo(c.getSystem(), c.getCode()));
                        }
                        codeFilters.add(new CodeFilterInfo(cf.getPath(), codes));
                    }
                }
                dataReqs.add(new DataRequirementInfo(dr.getType(), codeFilters));
            }

            ConditionInfo conditionInfo = null;
            Expression cond = getCondition(trigger);
            if (cond != null) {
                conditionInfo = new ConditionInfo(cond.getLanguage(), cond.getExpression());
            }
            triggers.add(new TriggerInfo(dataReqs, conditionInfo));
        }

        // Extract related actions (scoped within parent)
        List<RelatedActionInfo> relatedActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionRelatedActionComponent ra : action.getRelatedAction()) {
            Duration offset = ra.getOffsetDuration();
            relatedActions.add(new RelatedActionInfo(
                    ra.getActionId(),
                    ra.getRelationship() != null ? ra.getRelationship().toCode() : null,
                    offset != null ? offset.getValue() : null,
                    offset != null && offset.getUnit() != null ? offset.getUnit() : null
            ));
        }

        // Extract timing
        TimingInfo timingInfo = null;
        if (action.hasTiming() && action.getTiming() instanceof Timing timing) {
            Timing.TimingRepeatComponent repeat = timing.getRepeat();
            if (repeat != null) {
                timingInfo = new TimingInfo(
                        repeat.hasCount() ? repeat.getCount() : null,
                        repeat.hasFrequency() ? repeat.getFrequency() : null,
                        repeat.hasPeriod() ? repeat.getPeriod() : null,
                        repeat.hasPeriodUnit() ? repeat.getPeriodUnit().toCode() : null
                );
            }
        }

        Integer toleranceDays = extractToleranceDays(action);
        String requiredBehavior = action.hasRequiredBehavior()
                ? action.getRequiredBehavior().toCode()
                : null;

        // Sub-steps can have their own intelligence actions
        List<IntelligenceActionInfo> subStepIntelligenceActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent childAction : action.getAction()) {
            IntelligenceActionInfo info = buildIntelligenceActionInfo(childAction);
            if (info != null) {
                subStepIntelligenceActions.add(info);
            }
        }

        return new SubStepActionInfo(
                action.getId(),
                action.getTitle(),
                triggers,
                relatedActions,
                timingInfo,
                toleranceDays,
                requiredBehavior,
                subStepIntelligenceActions
        );
    }

    private IntelligenceActionInfo buildIntelligenceActionInfo(PlanDefinition.PlanDefinitionActionComponent action) {
        // Extract condition (kind=applicability)
        String condLanguage = null;
        String condExpression = null;
        for (PlanDefinition.PlanDefinitionActionConditionComponent cond : action.getCondition()) {
            if (cond.getKind() == PlanDefinition.ActionConditionKind.APPLICABILITY
                    && cond.hasExpression()
                    && cond.getExpression().hasExpression()) {
                condLanguage = cond.getExpression().getLanguage();
                condExpression = cond.getExpression().getExpression();
                break;
            }
        }

        // Skip intelligence actions without a condition
        if (condLanguage == null || condExpression == null) return null;

        // Extract definitionCanonical
        String definitionCanonical = null;
        if (action.hasDefinition() && action.getDefinition() instanceof CanonicalType canonical) {
            definitionCanonical = canonical.getValue();
        }

        // Skip intelligence actions without a definitionCanonical
        if (definitionCanonical == null) return null;

        // Extract required severity and destination extensions
        String severity = extractCodeExtension(action,
                "http://openphc.org/fhir/StructureDefinition/intelligence-severity");
        String intelligenceDestination = extractCodeExtension(action,
                "http://openphc.org/fhir/StructureDefinition/intelligence-destination");

        // Reject intelligence actions missing required extensions
        if (severity == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + action.getId()
                            + "' is missing required extension: intelligence-severity");
        }
        if (intelligenceDestination == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + action.getId()
                            + "' is missing required extension: intelligence-destination");
        }

        return new IntelligenceActionInfo(
                action.getId(),
                condLanguage,
                condExpression,
                definitionCanonical,
                severity,
                intelligenceDestination
        );
    }

    private Integer extractToleranceDays(PlanDefinition.PlanDefinitionActionComponent action) {
        Extension ext = action.getExtensionByUrl("http://openphc.org/fhir/StructureDefinition/tolerance-days");
        if (ext != null && ext.getValue() instanceof IntegerType intVal) {
            return intVal.getValue();
        }
        return null;
    }

    private String extractCodeExtension(PlanDefinition.PlanDefinitionActionComponent action, String url) {
        Extension ext = action.getExtensionByUrl(url);
        if (ext != null && ext.getValue() instanceof CodeType codeVal) {
            return codeVal.getCode();
        }
        return null;
    }

    private boolean hasData(TriggerDefinition trigger) {
        return trigger.getData() != null && !trigger.getData().isEmpty();
    }

    private Expression getCondition(TriggerDefinition trigger) {
        if (trigger.hasCondition()) {
            Expression cond = trigger.getCondition();
            if (cond.hasExpression() && !cond.getExpression().isBlank()) {
                return cond;
            }
        }
        return null;
    }

    private TriggerIndex buildTriggerIndex(String resourceType, String path, String codeSystem,
                                           String codeValue, UUID protocolDefinitionId, String actionId) {
        return TriggerIndex.builder()
                .id(TriggerIndexId.builder()
                        .resourceType(resourceType)
                        .path(path)
                        .codeSystem(codeSystem)
                        .codeValue(codeValue)
                        .protocolDefinitionId(protocolDefinitionId)
                        .actionId(actionId)
                        .build())
                .build();
    }

    // ── Inner record types for structured extraction ──

    public record ActionMetadata(
            String id,
            String title,
            List<TriggerInfo> triggers,
            List<RelatedActionInfo> relatedActions,
            TimingInfo timing,
            Integer toleranceDays,
            String requiredBehavior,
            String groupingBehavior,
            String selectionBehavior,
            List<IntelligenceActionInfo> intelligenceActions,
            List<SubStepActionInfo> subSteps
    ) {
        /** Returns true if this action is a group containing sub-steps. */
        public boolean hasSubSteps() {
            return subSteps != null && !subSteps.isEmpty();
        }
    }

    public record TriggerInfo(
            List<DataRequirementInfo> dataRequirements,
            ConditionInfo condition
    ) {}

    public record DataRequirementInfo(
            String resourceType,
            List<CodeFilterInfo> codeFilters
    ) {}

    public record CodeFilterInfo(
            String path,
            List<CodingInfo> codes
    ) {}

    public record CodingInfo(
            String system,
            String code
    ) {}

    public record ConditionInfo(
            String language,
            String expression
    ) {}

    public record RelatedActionInfo(
            String actionId,
            String relationship,
            java.math.BigDecimal offsetValue,
            String offsetUnit
    ) {}

    public record TimingInfo(
            Integer count,
            Integer frequency,
            java.math.BigDecimal period,
            String periodUnit
    ) {}

    public record ConditionOnlyTriggerInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression
    ) {}

    public record IntelligenceActionInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression,
            String definitionCanonical,
            String severity,
            String intelligenceDestination
    ) {}

    public record SubStepActionInfo(
            String id,
            String title,
            List<TriggerInfo> triggers,
            List<RelatedActionInfo> relatedActions,
            TimingInfo timing,
            Integer toleranceDays,
            String requiredBehavior,
            List<IntelligenceActionInfo> intelligenceActions
    ) {}
}
