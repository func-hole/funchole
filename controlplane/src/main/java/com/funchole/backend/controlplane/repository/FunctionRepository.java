package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.Function;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionRepository extends JpaRepository<Function, UUID> {

    Optional<Function> findByFunctionKeyAndDeletedAtIsNull(String functionKey);
}
