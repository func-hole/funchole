package com.funchole.backend.controlplane.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "functions")
public class Function {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "function_key", nullable = false, length = 150, unique = true)
    private String functionKey;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 100)
    private String runtime;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() {
        return id;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public String getFunctionKey() {
        return functionKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getRuntime() {
        return runtime;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public static Function create(
            AppUser appUser,
            String functionKey,
            String name,
            String description,
            String runtime
    ) {
        Function function = new Function();
        OffsetDateTime now = OffsetDateTime.now();
        function.id = UUID.randomUUID();
        function.appUser = appUser;
        function.functionKey = functionKey;
        function.name = name;
        function.description = description;
        function.runtime = runtime;
        function.createdAt = now;
        function.updatedAt = now;
        return function;
    }
}
