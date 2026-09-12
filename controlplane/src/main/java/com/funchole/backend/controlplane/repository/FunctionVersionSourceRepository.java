package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.FunctionVersionSource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionVersionSourceRepository extends JpaRepository<FunctionVersionSource, UUID> {
}
