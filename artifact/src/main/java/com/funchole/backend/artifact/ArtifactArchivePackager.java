package com.funchole.backend.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

final class ArtifactArchivePackager {

    private ArtifactArchivePackager() {
    }

    static void packageTarGz(Path sourceDirectory, Path archive) {
        if (sourceDirectory == null || !Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("preparedArtifactDirectory must be an existing directory");
        }

        try {
            if (archive.getParent() != null) {
                Files.createDirectories(archive.getParent());
            }
            try (
                    var output = Files.newOutputStream(archive);
                    var gzipOutput = new GZIPOutputStream(output);
                    var tarOutput = new TarArchiveOutputStream(gzipOutput)
            ) {
                tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                try (var paths = Files.walk(sourceDirectory)) {
                    paths.filter(path -> !path.equals(sourceDirectory))
                            .sorted(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()))
                            .forEach(path -> addEntry(sourceDirectory, path, tarOutput));
                }
                tarOutput.finish();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to package artifact directory " + sourceDirectory, exception);
        }
    }

    private static void addEntry(Path sourceDirectory, Path source, TarArchiveOutputStream tarOutput) {
        Path relative = sourceDirectory.relativize(source);
        String entryName = relative.toString().replace(java.io.File.separatorChar, '/');
        try {
            TarArchiveEntry entry = new TarArchiveEntry(source.toFile(), entryName);
            tarOutput.putArchiveEntry(entry);
            if (Files.isRegularFile(source)) {
                Files.copy(source, tarOutput);
            }
            tarOutput.closeArchiveEntry();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to add artifact archive entry " + entryName, exception);
        }
    }
}
