package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.service.IntelligenceEventLogService;
import org.openphc.cce.compliance.web.DtoMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pins the read API's contract: which service query each filter selects, and the JSON field names a
 * consumer sees. The routing is a chain of ifs, so a filter wired to the wrong query would otherwise
 * only surface downstream.
 */
class IntelligenceEventLogControllerTest {

    // Standalone rather than @WebMvcTest: the application class carries @EnableJpaRepositories, which a
    // web slice would try to satisfy with an entityManagerFactory it does not create. Standalone wires
    // exactly the controller under test, with the paging resolver and JSON converter the real dispatcher
    // uses.
    private MockMvc mockMvc;
    private final IntelligenceEventLogService service = mock(IntelligenceEventLogService.class);

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                new ObjectMapper().registerModule(new JavaTimeModule()));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IntelligenceEventLogController(service, new DtoMapper()))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setMessageConverters(converter)
                .build();
    }

    private static final String BASE = "/v1/compliance/intelligence-events";

    private IntelligenceEventLog event(UUID id) {
        return IntelligenceEventLog.builder()
                .id(id)
                .subject("patient-1")
                .actionType("CommunicationRequest")
                .intelligenceDestination("ASSIGNED_WORKER")
                .stepStatus("not-started")
                .slaStatus("missed")
                .triggerReason("missed")
                .published(true)
                .publishedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    void listAll_noFilter_usesTheUnfilteredQuery() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findAll(any(Pageable.class))).thenReturn(page(List.of(event(id))));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].stepStatus").value("not-started"))
                .andExpect(jsonPath("$.content[0].slaStatus").value("missed"))
                .andExpect(jsonPath("$.content[0].triggerReason").value("missed"));

        verify(service).findAll(any(Pageable.class));
    }

    @Test
    void listAll_protocolInstanceIdFilter_selectsThatQuery() throws Exception {
        UUID protocolInstanceId = UUID.randomUUID();
        when(service.findByProtocolInstanceId(eq(protocolInstanceId), any(Pageable.class)))
                .thenReturn(page(List.of(event(UUID.randomUUID()))));

        mockMvc.perform(get(BASE).param("protocolInstanceId", protocolInstanceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSizeOne()));

        verify(service).findByProtocolInstanceId(eq(protocolInstanceId), any(Pageable.class));
        verify(service, never()).findAll(any(Pageable.class));
    }

    @Test
    void listAll_actionDefinitionIdFilter_selectsThatQuery() throws Exception {
        UUID actionDefinitionId = UUID.randomUUID();
        when(service.findByActionDefinitionId(eq(actionDefinitionId), any(Pageable.class)))
                .thenReturn(page(List.of(event(UUID.randomUUID()))));

        mockMvc.perform(get(BASE).param("actionDefinitionId", actionDefinitionId.toString()))
                .andExpect(status().isOk());

        verify(service).findByActionDefinitionId(eq(actionDefinitionId), any(Pageable.class));
    }

    @Test
    void listAll_publishedFilter_selectsThatQuery() throws Exception {
        when(service.findByPublished(eq(false), any(Pageable.class)))
                .thenReturn(page(List.of(event(UUID.randomUUID()))));

        mockMvc.perform(get(BASE).param("published", "false"))
                .andExpect(status().isOk());

        verify(service).findByPublished(eq(false), any(Pageable.class));
    }

    @Test
    void listAll_protocolInstanceIdWinsOverTheOtherFilters() throws Exception {
        // Documents the precedence of the if-chain rather than leaving it to be discovered.
        UUID protocolInstanceId = UUID.randomUUID();
        when(service.findByProtocolInstanceId(eq(protocolInstanceId), any(Pageable.class)))
                .thenReturn(page(List.of()));

        mockMvc.perform(get(BASE)
                        .param("protocolInstanceId", protocolInstanceId.toString())
                        .param("actionDefinitionId", UUID.randomUUID().toString())
                        .param("published", "true"))
                .andExpect(status().isOk());

        verify(service).findByProtocolInstanceId(eq(protocolInstanceId), any(Pageable.class));
        verify(service, never()).findByActionDefinitionId(any(), any(Pageable.class));
        verify(service, never()).findByPublished(anyBoolean(), any(Pageable.class));
    }

    @Test
    void listAll_honoursPaging() throws Exception {
        when(service.findAll(any(Pageable.class))).thenReturn(page(List.of()));

        mockMvc.perform(get(BASE).param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        verify(service).findAll(argThat((Pageable p) -> p.getPageNumber() == 2 && p.getPageSize() == 5));
    }

    @Test
    void getById_returnsTheEvent() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(event(id));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.subject").value("patient-1"));
    }


    private static org.hamcrest.Matcher<java.util.Collection<?>> hasSizeOne() {
        return org.hamcrest.Matchers.hasSize(1);
    }

    /**
     * A page carrying a real {@link org.springframework.data.domain.PageRequest}. The single-argument
     * PageImpl constructor yields an unpaged page whose getPageSize() throws, which Jackson cannot
     * serialize — the repository always returns a properly paged result.
     */
    private static org.springframework.data.domain.Page<IntelligenceEventLog> page(
            List<IntelligenceEventLog> content) {
        return new PageImpl<>(content, org.springframework.data.domain.PageRequest.of(0, 20),
                content.size());
    }
}
