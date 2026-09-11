package com.funchole.backend.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

final class ArtifactArchiveExtractor {

    private ArtifactArchiveExtractor() {
    }

    static void extractTarGz(Path archive, Path destination) {
        try {
            Files.createDirectories(destination);
            try (
                    var fileInput = Files.newInputStream(archive);
                    var gzipInput = new GZIPInputStream(fileInput);
                    var tarInput = new TarArchiveInputStream(gzipInput)
            ) {
                TarArchiveEntry entry;
                while ((entry = tarInput.getNextEntry()) != null) {
                    if (!tarInput.canReadEntryData(entry)) {
                        continue;
                    }
                    Path target = safeTarget(destination, entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(tarInput, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract artifact archive " + archive, exception);
        }
    }

    private static Path safeTarget(Path destination, String entryName) {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path target = normalizedDestination.resolve(entryName).normalize();
        if (!target.startsWith(normalizedDestination)) {
            throw new IllegalStateException("Artifact archive entry escapes cache directory: " + entryName);
        }
        return target;
    }
}
