package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ActionDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ActionDefinitionService.class);

    private final ActionDefinitionRepository actionDefinitionRepository;
    private final AuditService auditService;

    public ActionDefinitionService(ActionDefinitionRepository actionDefinitionRepository,
                                    AuditService auditService) {
        this.actionDefinitionRepository = actionDefinitionRepository;
        this.auditService = auditService;
    }

    public ActionDefinition create(ActionDefinition actionDefinition) {
        if (actionDefinitionRepository.existsByDefinitionCanonical(actionDefinition.getDefinitionCanonical())) {
            throw new IllegalStateException(
                    "Action definition already exists for canonical: " + actionDefinition.getDefinitionCanonical());
        }

        actionDefinition = actionDefinitionRepository.save(actionDefinition);

        auditService.audit("INTELLIGENCE", "ACTION_DEFINITION_CREATED", "system",
                "ActionDefinition", actionDefinition.getId().toString(),
                Map.of("definitionCanonical", actionDefinition.getDefinitionCanonical(),
                        "actionType", actionDefinition.getActionType().name()));

        log.info("Created action definition: id={}, canonical={}",
                actionDefinition.getId(), actionDefinition.getDefinitionCanonical());

        return actionDefinition;
    }

    @Transactional(readOnly = true)
    public List<ActionDefinition> findAll() {
        return actionDefinitionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public ActionDefinition findById(UUID id) {
        return actionDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ActionDefinition not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<ActionDefinition> findByDefinitionCanonical(String definitionCanonical) {
        return actionDefinitionRepository.findByDefinitionCanonical(definitionCanonical);
    }

    public ActionDefinition update(UUID id, ActionDefinition updated) {
        ActionDefinition existing = findById(id);

        existing.setActionType(updated.getActionType());
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setMessageTemplate(updated.getMessageTemplate());
        existing.setSeverity(updated.getSeverity());
        existing.setTarget(updated.getTarget());
        existing.setRouting(updated.getRouting());

        existing = actionDefinitionRepository.save(existing);

        auditService.audit("INTELLIGENCE", "ACTION_DEFINITION_UPDATED", "system",
                "ActionDefinition", existing.getId().toString(),
                Map.of("definitionCanonical", existing.getDefinitionCanonical()));

        log.info("Updated action definition: id={}", existing.getId());

        return existing;
    }
}
