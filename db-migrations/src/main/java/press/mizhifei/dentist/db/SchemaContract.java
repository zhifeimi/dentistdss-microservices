package press.mizhifei.dentist.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.cfg.Configuration;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

/**
 * Shared schema-contract check for the PostgreSQL database used by the JDBC
 * services.
 *
 * <p>
 * Applies the Flyway migrations shipped in this module ({@code db/migration}
 * on the classpath) to the target database and then verifies that the given
 * entity packages exactly match the resulting schema — the same check
 * {@code spring.jpa.hibernate.ddl-auto=validate} performs when every service
 * boots. It is intended for opt-in contract tests that run against a real
 * database (locally via Docker or in CI via a service container).
 */
public final class SchemaContract {

    private SchemaContract() {
    }

    /**
     * Migrates the target database and validates the mapped entities against
     * it.
     *
     * <p>
     * On a fresh database, {@code V1__baseline.sql} is applied. On an existing
     * database with no {@code flyway_schema_history} table (the production
     * shape after years of {@code ddl-auto=update}), Flyway baselines at
     * version 1 and skips V1 entirely, leaving the schema untouched. Either
     * way, Hibernate schema validation then proves the mapped entities agree
     * with the live schema (tables, columns, column types, and sequences —
     * the checks {@code AbstractSchemaValidator} performs in Hibernate 7).
     *
     * @param jdbcUrl         JDBC URL of the database under test
     * @param username        database user
     * @param password        database password
     * @param entityPackages  packages to scan for {@code @Entity},
     *                        {@code @MappedSuperclass}, and {@code @Embeddable}
     *                        classes
     * @throws org.flywaydb.core.api.FlywayException            if migration fails
     * @throws org.hibernate.tool.schema.spi.SchemaManagementException if the
     *                                                            schema does not
     *                                                            match the
     *                                                            entities
     */
    public static void migrateAndValidate(String jdbcUrl, String username, String password,
            String... entityPackages) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .failOnMissingLocations(true)
                .load();
        flyway.migrate();
        for (MigrationInfo info : flyway.info().all()) {
            System.out.printf("[SchemaContract] %s %s %s %s%n",
                    info.getType(), info.getVersion(), info.getDescription(), info.getState());
        }

        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.url", jdbcUrl);
        configuration.setProperty("hibernate.connection.username", username);
        configuration.setProperty("hibernate.connection.password", password);
        configuration.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.show_sql", "false");
        // Spring Boot applies CamelCaseToUnderscoresNamingStrategy to every
        // service's SessionFactory; a plain Hibernate Configuration does not.
        // Mirror it here so validation uses the same physical column names
        // (e.g. approvalBy -> approval_by) that the services validate against
        // at boot. None of the services override this default.
        configuration.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());
        for (Class<?> mappedClass : discoverMappedClasses(entityPackages)) {
            configuration.addAnnotatedClass(mappedClass);
        }
        try (SessionFactory sessionFactory = configuration.buildSessionFactory()) {
            // Schema validation runs during session-factory bootstrap. Reaching
            // this point means every mapped table, column, column type, and
            // sequence matched the migrated database.
        }
    }

    private static List<Class<?>> discoverMappedClasses(String... entityPackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        List<Class<?>> mappedClasses = new ArrayList<>();
        for (String entityPackage : entityPackages) {
            Set<BeanDefinition> candidates = scanner.findCandidateComponents(entityPackage);
            for (BeanDefinition candidate : candidates) {
                String className = candidate.getBeanClassName();
                if (className == null) {
                    throw new IllegalStateException("Scanned candidate without a class name in " + entityPackage);
                }
                try {
                    mappedClasses.add(Class.forName(className));
                }
                catch (ClassNotFoundException ex) {
                    throw new IllegalStateException("Could not load mapped class " + className, ex);
                }
            }
        }
        if (mappedClasses.isEmpty()) {
            throw new IllegalStateException(
                    "No mapped classes discovered in packages " + Arrays.toString(entityPackages));
        }
        return mappedClasses;
    }
}
