package com.funchole.backend.artifact;

import java.nio.file.Path;

interface S3ArtifactClient {

    boolean download(String key, Path destination);

    void upload(String key, Path source);

    void delete(String key);
}
