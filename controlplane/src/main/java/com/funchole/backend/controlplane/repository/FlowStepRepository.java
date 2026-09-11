package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.FlowStep;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowStepRepository extends JpaRepository<FlowStep, UUID> {

    List<FlowStep> findAllByFlowVersion_IdOrderByPosition(UUID flowVersionId);

    Optional<FlowStep> findByIdAndFlowVersion_Id(UUID id, UUID flowVersionId);

    boolean existsByFlowVersion_Id(UUID flowVersionId);

    void deleteAllByFlowVersion_Id(UUID flowVersionId);
}
