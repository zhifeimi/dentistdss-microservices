package press.mizhifei.dentist.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Standalone schema migrator (DATA-02 follow-up): applies the Flyway
 * migrations bundled in this module and exits, so the migration-owner
 * credential no longer needs to live in application pods. Runs as the Helm
 * pre-install/pre-upgrade hook Job and as the compose one-shot `migrator`
 * service; application services boot with {@code spring.flyway.enabled:
 * false} and least-privilege runtime roles, with Hibernate
 * {@code ddl-auto: validate} as the remaining drift detector.
 *
 * <p>
 * Environment:
 * <ul>
 *   <li>{@code DB_MIGRATION_URL} — JDBC URL (default
 *       {@code jdbc:postgresql://postgres:5432/dentistdss})</li>
 *   <li>{@code DB_MIGRATION_USERNAME} — migration owner (default
 *       {@code dentistdss})</li>
 *   <li>{@code POSTGRES_PASSWORD} — owner password (REQUIRED; the process
 *       exits with status 2 when unset or blank)</li>
 * </ul>
 *
 * <p>
 * Exit codes: 0 success, 1 migration failure (the hook Job fails, blocking
 * the rollout — fail closed), 2 missing configuration.
 */
public final class Migrator {

    static final String DEFAULT_URL = "jdbc:postgresql://postgres:5432/dentistdss";
    static final String DEFAULT_USERNAME = "dentistdss";

    private Migrator() {
    }

    public static void main(String[] args) {
        String url = envOrDefault("DB_MIGRATION_URL", DEFAULT_URL);
        String username = envOrDefault("DB_MIGRATION_USERNAME", DEFAULT_USERNAME);
        String password = ownerPassword();
        if (password == null) {
            System.err.println(
                    "migrator: POSTGRES_PASSWORD is required (migration-owner credential)");
            System.exit(2);
        }

        System.out.println("migrator: applying Flyway migrations to " + url + " as " + username);
        try {
            MigrateResult result = Flyway.configure()
                    .dataSource(url, username, password)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            System.out.println("migrator: success — " + result.migrationsExecuted
                    + " migration(s) applied, schema now at version " + result.targetSchemaVersion);
        } catch (Exception exception) {
            System.err.println("migrator: FAILED — " + exception.getMessage());
            System.exit(1);
        }
    }

    /** The owner credential, or {@code null} when unset/blank (exit-2 contract). */
    static String ownerPassword() {
        String password = System.getenv("POSTGRES_PASSWORD");
        return password == null || password.isBlank() ? null : password;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
