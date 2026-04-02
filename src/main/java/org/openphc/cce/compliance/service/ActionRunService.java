package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.openphc.cce.compliance.domain.repository.ActionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ActionRunService {

    private static final Logger log = LoggerFactory.getLogger(ActionRunService.class);

    private static final Set<ActionRunStatus> TERMINAL_STATUSES = Set.of(
            ActionRunStatus.COMPLETED, ActionRunStatus.FAILED, ActionRunStatus.CANCELLED);

    private final ActionRunRepository actionRunRepository;

    public ActionRunService(ActionRunRepository actionRunRepository) {
        this.actionRunRepository = actionRunRepository;
    }

    public ActionRun save(ActionRun actionRun) {
        return actionRunRepository.save(actionRun);
    }

    @Transactional(readOnly = true)
    public Page<ActionRun> findAll(Pageable pageable) {
        return actionRunRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public ActionRun findById(UUID id) {
        return actionRunRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ActionRun not found: " + id));
    }

    public ActionRun cancel(UUID id) {
        ActionRun actionRun = findById(id);

        if (TERMINAL_STATUSES.contains(actionRun.getStatus())) {
            throw new IllegalStateException(
                    "Cannot cancel action run in terminal state: " + actionRun.getStatus());
        }

        actionRun.setStatus(ActionRunStatus.CANCELLED);
        actionRun.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        actionRun = actionRunRepository.save(actionRun);

        log.info("Cancelled action run: id={}", id);
        return actionRun;
    }
}
