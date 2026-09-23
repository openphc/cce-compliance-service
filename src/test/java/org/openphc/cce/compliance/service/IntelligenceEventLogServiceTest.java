package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntelligenceEventLogServiceTest {

    @Mock private IntelligenceEventLogRepository repository;
    @InjectMocks private IntelligenceEventLogService service;

    private final Pageable pageable = PageRequest.of(0, 20);

    private IntelligenceEventLog log(UUID id) {
        return IntelligenceEventLog.builder().id(id).subject("patient-1").build();
    }

    @Test
    void findById_returnsTheEvent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(log(id)));

        assertEquals(id, service.findById(id).getId());
    }

    @Test
    void findById_missing_throwsEntityNotFoundNamingTheId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> service.findById(id));
        assertTrue(ex.getMessage().contains(id.toString()));
    }

    @Test
    void findAll_bothForms() {
        when(repository.findAll()).thenReturn(List.of(log(UUID.randomUUID())));
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(log(UUID.randomUUID()))));

        assertEquals(1, service.findAll().size());
        assertEquals(1, service.findAll(pageable).getTotalElements());
    }

    @Test
    void findByProtocolInstanceId_bothForms() {
        UUID id = UUID.randomUUID();
        when(repository.findByProtocolInstanceId(id)).thenReturn(List.of(log(UUID.randomUUID())));
        when(repository.findByProtocolInstanceId(id, pageable))
                .thenReturn(new PageImpl<>(List.of(log(UUID.randomUUID()))));

        assertEquals(1, service.findByProtocolInstanceId(id).size());
        assertEquals(1, service.findByProtocolInstanceId(id, pageable).getTotalElements());
    }

    @Test
    void findByActionDefinitionId_bothForms() {
        UUID id = UUID.randomUUID();
        when(repository.findByActionDefinitionId(id)).thenReturn(List.of(log(UUID.randomUUID())));
        when(repository.findByActionDefinitionId(id, pageable))
                .thenReturn(new PageImpl<>(List.of(log(UUID.randomUUID()))));

        assertEquals(1, service.findByActionDefinitionId(id).size());
        assertEquals(1, service.findByActionDefinitionId(id, pageable).getTotalElements());
    }

    @Test
    void findByPublished_bothForms() {
        when(repository.findByPublished(false)).thenReturn(List.of(log(UUID.randomUUID())));
        when(repository.findByPublished(false, pageable))
                .thenReturn(new PageImpl<>(List.of(log(UUID.randomUUID()))));

        assertEquals(1, service.findByPublished(false).size());
        Page<IntelligenceEventLog> page = service.findByPublished(false, pageable);
        assertEquals(1, page.getTotalElements());
    }
}
