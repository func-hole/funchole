package com.funchole.backend.controlplane.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.funchole.backend.controlplane.entity.AppDomain;

public interface AppDomainRepository extends JpaRepository<AppDomain, UUID> {
    Optional<AppDomain> findById(UUID id);
}
