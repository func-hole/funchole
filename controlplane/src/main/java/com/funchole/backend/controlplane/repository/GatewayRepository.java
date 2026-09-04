package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.Gateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayRepository extends JpaRepository<Gateway, UUID> {

    @EntityGraph(attributePaths = { "appDomain", "certificate" })
    Page<Gateway> findAllByAppUser_Id(UUID appUserId, Pageable pageable);

    @EntityGraph(attributePaths = { "appDomain", "certificate" })
    Optional<Gateway> findByIdAndAppUser_Id(UUID id, UUID appUserId);

    boolean existsByUniqueKey(String uniqueKey);
}
