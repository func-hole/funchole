package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FunctionVersionRepository extends JpaRepository<FunctionVersion, UUID> {

    Optional<FunctionVersion> findByIdAndFunction_Id(UUID id, UUID functionId);

    Optional<FunctionVersion> findByIdAndArtifactObjectKeyIsNotNullAndArtifactFormatIsNotNull(UUID id);

    /**
     * Atomic compare-and-swap: flips {@code status} to {@code newStatus} only
     * if it is currently exactly {@code expectedStatus}, as a single
     * database-level conditional UPDATE. Returns the number of rows changed
     * (0 or 1, since {@code id} is the primary key) - the caller uses that to
     * tell "already transitioned by someone else" apart from "does not
     * exist" with a follow-up read, but the transition itself is decided
     * entirely by this one statement, so concurrent callers can never both
     * win it.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FunctionVersion fv SET fv.status = :newStatus, fv.updatedAt = :updatedAt "
            + "WHERE fv.id = :id AND fv.status = :expectedStatus")
    int compareAndSetStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") FunctionVersionStatus expectedStatus,
            @Param("newStatus") FunctionVersionStatus newStatus,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
