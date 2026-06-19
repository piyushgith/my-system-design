package com.ecommerce.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationFilesTest {

    private static final Pattern VERSION_PREFIX = Pattern.compile("V(\\d+)__.*\\.sql");

    @Test
    void migrationsAreSequentialAndEnableUuidGenerationFirst() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        List<Path> migrations;
        try (var stream = Files.list(migrationDir)) {
            migrations = stream
                    .filter(path -> VERSION_PREFIX.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }

        assertThat(migrations).isNotEmpty();
        for (int i = 0; i < migrations.size(); i++) {
            assertThat(migrations.get(i).getFileName().toString()).startsWith("V" + (i + 1) + "__");
        }
        String firstMigration = Files.readString(migrations.getFirst());
        assertThat(firstMigration).contains("CREATE EXTENSION IF NOT EXISTS \"pgcrypto\"");
    }
}
