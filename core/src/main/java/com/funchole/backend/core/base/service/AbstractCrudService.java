package com.funchole.backend.core.base.service;

import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public abstract class AbstractCrudService<T, R extends JpaRepository<T, UUID>> {

    protected final R repository;

    protected AbstractCrudService(R repository) {
        this.repository = repository;
    }

    protected T findRequired(UUID id, String resourceName) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(resourceName + " not found: " + id));
    }
}
