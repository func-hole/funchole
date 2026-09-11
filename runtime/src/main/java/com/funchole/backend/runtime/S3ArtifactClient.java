package com.funchole.backend.runtime;

import java.nio.file.Path;

interface S3ArtifactClient {

    boolean download(String key, Path destination);

    void upload(String key, Path source);
}
