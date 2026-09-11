package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.entity.ArtifactMetadata;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FunctionVersionArtifactRegistry {

    public static final String ARTIFACT_FORMAT_TAR_GZ = "TAR_GZ";

    private static final String ARTIFACT_FILE_NAME = "artifact.tar.gz";

    private final FunctionVersionRepository functionVersionRepository;

    public FunctionVersionArtifactRegistry(FunctionVersionRepository functionVersionRepository) {
        this.functionVersionRepository = functionVersionRepository;
    }

    @Transactional
    public FunctionVersion attachPublishedArtifact(UUID functionVersionId, String artifactObjectKey, String artifactFormat) {
        FunctionVersion functionVersion = functionVersionRepository.findById(functionVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Function version not found: " + functionVersionId));
        validateArtifactReference(functionVersionId, artifactObjectKey, artifactFormat);
        functionVersion.attachArtifact(artifactObjectKey, artifactFormat);
        return functionVersionRepository.save(functionVersion);
    }

    @Transactional(readOnly = true)
    public Optional<ArtifactMetadata> findArtifactMetadata(UUID functionVersionId) {
        return functionVersionRepository.findById(functionVersionId)
                .flatMap(FunctionVersion::getArtifactMetadata);
    }

    public static String artifactObjectKey(UUID functionVersionId) {
        if (functionVersionId == null) {
            throw new IllegalArgumentException("functionVersionId is required");
        }
        return "artifacts/" + functionVersionId + "/" + ARTIFACT_FILE_NAME;
    }

    private void validateArtifactReference(UUID functionVersionId, String artifactObjectKey, String artifactFormat) {
        if (artifactObjectKey == null || artifactObjectKey.isBlank()) {
            throw new IllegalArgumentException("artifactObjectKey is required");
        }
        if (!artifactObjectKey(functionVersionId).equals(artifactObjectKey)) {
            throw new IllegalArgumentException(
                    "artifactObjectKey must match the exact functionVersionId: " + functionVersionId);
        }
        if (artifactFormat == null || artifactFormat.isBlank()) {
            throw new IllegalArgumentException("artifactFormat is required");
        }
    }
}
