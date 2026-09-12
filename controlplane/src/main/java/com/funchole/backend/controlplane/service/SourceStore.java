package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.entity.SourceFile;
import java.util.List;
import java.util.UUID;

/**
 * Storage-neutral boundary for the raw content of a FunctionVersion's
 * submitted source files. {@link FunctionVersionSourceService} persists only
 * the source manifest (paths, entrypoint, runtime) in Postgres; file bytes
 * live behind this interface instead, so a future backend (e.g. object
 * storage) can replace {@link LocalSourceStore} without changing the
 * application service or the database schema - the same shape as
 * {@code artifact.ArtifactStore} for published artifacts.
 */
public interface SourceStore {

    /**
     * Replaces the entire stored source for the given FunctionVersion with
     * exactly the given files.
     */
    void save(UUID functionVersionId, List<SourceFile> files);

    /**
     * Loads the content of exactly the given relative paths, previously
     * stored via {@link #save}, for the given FunctionVersion.
     */
    List<SourceFile> load(UUID functionVersionId, List<String> relativePaths);
}
