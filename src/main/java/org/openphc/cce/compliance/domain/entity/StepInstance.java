package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.openphc.cce.compliance.domain.enums.CompletionStatus;
import org.openphc.cce.compliance.domain.enums.StepState;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "step_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_instance_id", nullable = false)
    private ProtocolInstance protocolInstance;

    @Column(name = "action_id", nullable = false)
    private String actionId;

    @Column(name = "repeat_index", nullable = false)
    @Builder.Default
    private int repeatIndex = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepState state;

    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    @Column(name = "overdue_date")
    private OffsetDateTime overdueDate;

    @Column(name = "missed_date")
    private OffsetDateTime missedDate;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completed_by_source")
    private String completedBySource;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_status")
    private CompletionStatus completionStatus;

    @Column(name = "matched_event_id")
    private UUID matchedEventId;

    @Column(name = "required_behavior")
    private String requiredBehavior;

    @Column(name = "parent_step_id")
    private UUID parentStepId;

    @Column(name = "parent_action_id")
    private String parentActionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
