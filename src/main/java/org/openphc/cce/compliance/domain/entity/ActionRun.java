package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.openphc.cce.compliance.domain.enums.ActionType;
import org.openphc.cce.compliance.domain.enums.IntelligenceSeverity;
import org.openphc.cce.compliance.domain.enums.IntelligenceTarget;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "action_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_definition_id", nullable = false)
    private ActionDefinition actionDefinition;

    @Column(name = "intelligence_event_id", nullable = false)
    private UUID intelligenceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionRunStatus status;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    @Column(name = "protocol_instance_id")
    private UUID protocolInstanceId;

    @Column(name = "step_instance_id")
    private UUID stepInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntelligenceSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntelligenceTarget target;

    @Column(name = "resolved_message", columnDefinition = "text")
    private String resolvedMessage;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
