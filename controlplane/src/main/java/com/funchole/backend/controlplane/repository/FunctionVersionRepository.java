package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.FunctionVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionVersionRepository extends JpaRepository<FunctionVersion, UUID> {

    Optional<FunctionVersion> findByIdAndFunction_Id(UUID id, UUID functionId);

    Optional<FunctionVersion> findByIdAndArtifactObjectKeyIsNotNullAndArtifactFormatIsNotNull(UUID id);
}
