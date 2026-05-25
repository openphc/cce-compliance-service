package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface StepInstanceRepository extends JpaRepository<StepInstance, UUID> {

    List<StepInstance> findByProtocolInstanceId(UUID protocolInstanceId);

    List<StepInstance> findByStateIn(Collection<StepState> states);

    List<StepInstance> findByProtocolInstanceIdAndActionIdAndStateIn(
            UUID protocolInstanceId, String actionId, Collection<StepState> states);

    List<StepInstance> findByParentStepId(UUID parentStepId);

    List<StepInstance> findByParentStepIdAndStateIn(UUID parentStepId, Collection<StepState> states);
}
