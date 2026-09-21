package com.campusfix;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		Path databasePath = resolveCanonicalDatabasePath();
		System.setProperty(
				"spring.datasource.url",
				"jdbc:h2:file:" + databasePath.toString().replace('\\', '/'));
		System.out.println("CampusFix database: " + databasePath);
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Resolves the database from the repository location rather than the process
	 * working directory. This prevents VS Code, Gradle, or a terminal launched
	 * from frontend/ from silently creating different ./data databases.
	 */
	static Path resolveCanonicalDatabasePath() {
		Path repositoryRoot = findRepositoryRoot(codeLocation());
		if (repositoryRoot == null) {
			repositoryRoot = findRepositoryRoot(Path.of(System.getProperty("user.dir")));
		}

		if (repositoryRoot == null) {
			throw new IllegalStateException(
					"Cannot locate the CampusFix repository root; refusing to create a database in an unknown directory");
		}

		return repositoryRoot.resolve("data").resolve("campusfix").toAbsolutePath().normalize();
	}

	private static Path codeLocation() {
		try {
			return Path.of(BackendApplication.class.getProtectionDomain()
					.getCodeSource()
					.getLocation()
					.toURI());
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("Cannot resolve the Backend application location", exception);
		}
	}

	private static Path findRepositoryRoot(Path startingPath) {
		Path current = startingPath.toAbsolutePath().normalize();
		if (!Files.isDirectory(current)) {
			current = current.getParent();
		}

		while (current != null) {
			if (Files.isRegularFile(current.resolve("backend").resolve("build.gradle"))
					&& Files.isDirectory(current.resolve("frontend"))) {
				return current;
			}
			current = current.getParent();
		}

		return null;
	}

}
