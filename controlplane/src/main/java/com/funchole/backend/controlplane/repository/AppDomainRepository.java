package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.AppDomain;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppDomainRepository extends JpaRepository<AppDomain, UUID> {

    Page<AppDomain> findAllByAppUserId(UUID appUserId, Pageable pageable);
}
