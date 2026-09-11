package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.entity.FlowVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowVersionRepository extends JpaRepository<FlowVersion, UUID> {

    Page<FlowVersion> findAllByFlow_Id(UUID flowId, Pageable pageable);

    Optional<FlowVersion> findByIdAndFlow_Id(UUID id, UUID flowId);

    Optional<FlowVersion> findByFlow_IdAndStatus(UUID flowId, FlowVersionStatus status);

    @Query("select coalesce(max(fv.version), 0) from FlowVersion fv where fv.flow.id = :flowId")
    int findMaxVersion(@Param("flowId") UUID flowId);
}
