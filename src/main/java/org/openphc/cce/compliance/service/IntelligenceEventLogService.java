package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IntelligenceEventLogService {

    private final IntelligenceEventLogRepository repository;

    public IntelligenceEventLogService(IntelligenceEventLogRepository repository) {
        this.repository = repository;
    }

    public IntelligenceEventLog findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Intelligence event log not found: " + id));
    }

    public List<IntelligenceEventLog> findAll() {
        return repository.findAll();
    }

    public Page<IntelligenceEventLog> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public List<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId) {
        return repository.findByProtocolInstanceId(protocolInstanceId);
    }

    public Page<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId, Pageable pageable) {
        return repository.findByProtocolInstanceId(protocolInstanceId, pageable);
    }

    public List<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId) {
        return repository.findByActionDefinitionId(actionDefinitionId);
    }

    public Page<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId, Pageable pageable) {
        return repository.findByActionDefinitionId(actionDefinitionId, pageable);
    }

    public List<IntelligenceEventLog> findByPublished(boolean published) {
        return repository.findByPublished(published);
    }

    public Page<IntelligenceEventLog> findByPublished(boolean published, Pageable pageable) {
        return repository.findByPublished(published, pageable);
    }
}
