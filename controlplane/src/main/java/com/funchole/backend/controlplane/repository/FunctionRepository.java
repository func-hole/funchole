package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.Function;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionRepository extends JpaRepository<Function, UUID> {

    Optional<Function> findByFunctionKeyAndDeletedAtIsNull(String functionKey);

    Page<Function> findAllByAppUser_IdAndDeletedAtIsNull(UUID appUserId, Pageable pageable);

    Optional<Function> findByIdAndAppUser_IdAndDeletedAtIsNull(UUID id, UUID appUserId);

    boolean existsByFunctionKey(String functionKey);
}
