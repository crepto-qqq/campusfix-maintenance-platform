package com.campusfix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BackendApplicationDatabasePathTest {

    @Test
    void databasePathIsAlwaysRepositoryLevelDataDirectory() {
        Path databasePath = BackendApplication.resolveCanonicalDatabasePath();
        Path dataDirectory = databasePath.getParent();
        Path repositoryRoot = dataDirectory.getParent();

        assertThat(databasePath.isAbsolute()).isTrue();
        assertThat(databasePath.getFileName()).isEqualTo(Path.of("campusfix"));
        assertThat(dataDirectory.getFileName()).isEqualTo(Path.of("data"));
        assertThat(Files.isRegularFile(repositoryRoot.resolve(Path.of("backend", "build.gradle")))).isTrue();
        assertThat(Files.isDirectory(repositoryRoot.resolve("frontend"))).isTrue();
    }
}
