package com.campusfix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BackendApplicationDatabasePathTest {

    @Test
    void databasePathIsAlwaysRepositoryLevelDataDirectory() {
        Path databasePath = BackendApplication.resolveCanonicalDatabasePath();

        assertThat(databasePath.isAbsolute()).isTrue();
        assertThat(databasePath.toString())
                .endsWith(Path.of("ELEC5619-Lab03-Group-03", "data", "campusfix").toString())
                .doesNotContain("frontend\\data")
                .doesNotContain("backend\\data");
    }
}
