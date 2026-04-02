package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface ActionRunRepository extends JpaRepository<ActionRun, UUID> {

    Page<ActionRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(ActionRunStatus status);

    @Query("SELECT ar.severity, COUNT(ar) FROM ActionRun ar " +
            "WHERE ar.createdAt BETWEEN :from AND :to GROUP BY ar.severity")
    java.util.List<Object[]> countBySeverityInPeriod(@Param("from") OffsetDateTime from,
                                                      @Param("to") OffsetDateTime to);

    @Query("SELECT ar.target, COUNT(ar) FROM ActionRun ar " +
            "WHERE ar.createdAt BETWEEN :from AND :to GROUP BY ar.target")
    java.util.List<Object[]> countByTargetInPeriod(@Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to);

    long countByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);
}
