package com.funchole.backend.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.controlplane.entity.FunctionVersionSource;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionSourceRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transport-neutral application service for submitting and reading back the
 * source bundle (build input) of an exact FunctionVersion. Depends only on
 * repositories, {@link SourceStore}, and plain domain types - no HTTP, MCP,
 * CLI, or UI type ever appears in its signatures, so every future adapter
 * can call it directly.
 *
 * <p>Source is intentionally kept separate from the FunctionVersion's
 * artifact fields (build output): this service never reads or writes
 * artifact state, and {@link FunctionVersionArtifactRegistry} never reads or
 * writes source state.
 *
 * <p>Raw file content never lands in Postgres: only the manifest (paths,
 * entrypoint, runtime) is persisted here, and content is delegated to
 * {@link SourceStore}, mirroring how {@code artifact.ArtifactStore} keeps
 * published-artifact bytes out of the relational database.
 */
@Service
public class FunctionVersionSourceService {

    private final FunctionVersionRepository functionVersionRepository;
    private final FunctionVersionSourceRepository functionVersionSourceRepository;
    private final SourceStore sourceStore;
    private final ObjectMapper objectMapper;

    public FunctionVersionSourceService(
            FunctionVersionRepository functionVersionRepository,
            FunctionVersionSourceRepository functionVersionSourceRepository,
            SourceStore sourceStore
    ) {
        this.functionVersionRepository = functionVersionRepository;
        this.functionVersionSourceRepository = functionVersionSourceRepository;
        this.sourceStore = sourceStore;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public FunctionVersionSource submitSource(UUID functionVersionId, SourceBundle sourceBundle) {
        if (functionVersionId == null) {
            throw new IllegalArgumentException("functionVersionId is required");
        }
        if (!functionVersionRepository.existsById(functionVersionId)) {
            throw new ResourceNotFoundException("Function version not found: " + functionVersionId);
        }
        validateSourceBundle(sourceBundle);

        List<String> relativePaths = sourceBundle.files().stream().map(SourceFile::relativePath).toList();
        String relativePathsJson = writePaths(relativePaths);

        // Content goes to the SourceStore before the manifest is committed: a
        // failure here leaves nothing behind to reference; a manifest commit
        // failure after a successful write instead leaves an orphaned,
        // harmless directory on disk - the same accepted asymmetry already
        // used for published artifacts (see FunctionVersionDeploymentService).
        sourceStore.save(functionVersionId, sourceBundle.files());

        FunctionVersionSource source = functionVersionSourceRepository.findById(functionVersionId)
                .map(existing -> {
                    existing.replaceWith(
                            sourceBundle.runtimeType(), sourceBundle.runtimeVersion(), sourceBundle.entrypoint(),
                            relativePathsJson);
                    return existing;
                })
                .orElseGet(() -> FunctionVersionSource.create(
                        functionVersionId,
                        sourceBundle.runtimeType(),
                        sourceBundle.runtimeVersion(),
                        sourceBundle.entrypoint(),
                        relativePathsJson
                ));
        return functionVersionSourceRepository.save(source);
    }

    @Transactional(readOnly = true)
    public Optional<SourceBundle> findSource(UUID functionVersionId) {
        return functionVersionSourceRepository.findById(functionVersionId).map(this::toSourceBundle);
    }

    private SourceBundle toSourceBundle(FunctionVersionSource source) {
        List<String> relativePaths = readPaths(source.getRelativePaths());
        List<SourceFile> files = sourceStore.load(source.getFunctionVersionId(), relativePaths);
        return new SourceBundle(source.getRuntimeType(), source.getRuntimeVersion(), source.getEntrypoint(), files);
    }

    private void validateSourceBundle(SourceBundle sourceBundle) {
        if (sourceBundle == null) {
            throw new IllegalArgumentException("sourceBundle is required");
        }
        if (sourceBundle.runtimeType() == null || sourceBundle.runtimeType().isBlank()) {
            throw new IllegalArgumentException("runtimeType is required");
        }
        List<SourceFile> files = sourceBundle.files();
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("source must contain at least one file");
        }

        Set<String> relativePaths = new HashSet<>();
        for (SourceFile file : files) {
            validateRelativePath(file.relativePath());
            if (!relativePaths.add(file.relativePath())) {
                throw new IllegalArgumentException("duplicate source file path: " + file.relativePath());
            }
        }

        String entrypoint = sourceBundle.entrypoint();
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("entrypoint is required");
        }
        if (!relativePaths.contains(entrypoint)) {
            throw new IllegalArgumentException("entrypoint does not match any submitted source file: " + entrypoint);
        }
    }

    private void validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("source file relativePath is required");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("source file relativePath must not be absolute: " + relativePath);
        }
        for (String segment : relativePath.split("[/\\\\]", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                throw new IllegalArgumentException("source file relativePath is invalid: " + relativePath);
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException(
                        "source file relativePath must not traverse outside the source root: " + relativePath);
            }
        }
    }

    private String writePaths(List<String> relativePaths) {
        try {
            return objectMapper.writeValueAsString(relativePaths);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize source file paths", exception);
        }
    }

    private List<String> readPaths(String relativePathsJson) {
        try {
            return objectMapper.readValue(relativePathsJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize source file paths", exception);
        }
    }
}
