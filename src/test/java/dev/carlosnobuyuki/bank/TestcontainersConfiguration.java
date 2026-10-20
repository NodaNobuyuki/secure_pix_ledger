package dev.carlosnobuyuki.bank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

// Public so integration tests in sub-packages (api, infrastructure, ...) can import it.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** The least-privilege role the application connects as (ADR-0012). */
  public static final String APP_ROLE = "bank_app";

  // Throwaway credential for a container that only lives as long as the test run.
  private static final String APP_PASSWORD = "test-only-app-password";

  @Bean
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
        // The same script compose uses, so the tests also prove the role provisioning.
        .withCopyFileToContainer(
            MountableFile.forHostPath("ops/postgres/init/01-create-app-role.sh", 0755),
            "/docker-entrypoint-initdb.d/01-create-app-role.sh")
        .withEnv("APP_DB_USER", APP_ROLE)
        .withEnv("APP_DB_PASSWORD", APP_PASSWORD);
  }

  // Not @ServiceConnection, which gives Flyway and the application the same superuser. Here Flyway
  // migrates as the container's owner and everything else connects as the restricted role, so a
  // missing grant fails the suite.
  @Bean
  DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
    return registry -> {
      registry.add("spring.datasource.url", postgres::getJdbcUrl);
      registry.add("spring.datasource.username", () -> APP_ROLE);
      registry.add("spring.datasource.password", () -> APP_PASSWORD);
      registry.add("spring.flyway.user", postgres::getUsername);
      registry.add("spring.flyway.password", postgres::getPassword);
      registry.add("spring.flyway.placeholders.app_role", () -> APP_ROLE);
    };
  }
}
