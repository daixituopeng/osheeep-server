package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class DinnerHouseholdManagementTestDatabaseSafetyInitializerTest {

    private static final String DATABASE = "hhmgmt_it";

    @Test
    void acceptsExactRawNamesOptInLoopbackAndDatasourceCatalog() {
        GenericApplicationContext context = contextWith(Map.of(
                "OSHEEEP_DB_NAME", DATABASE,
                "OSHEEEP_DB_TEST_NAME", DATABASE,
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:33307/" + DATABASE));

        assertThatCode(() -> initializer(DATABASE, DATABASE, "true").initialize(context))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrMismatchedRawDatabaseNames() {
        GenericApplicationContext context = safeContext();

        assertRejected(initializer(null, DATABASE, "true"), context);
        assertRejected(initializer(DATABASE, "other_it", "true"), context);
    }

    @Test
    void rejectsMissingExplicitEphemeralOptIn() {
        assertRejected(initializer(DATABASE, DATABASE, null), safeContext());
        assertRejected(initializer(DATABASE, DATABASE, "TRUE"), safeContext());
    }

    @Test
    void rejectsNonLoopbackDatasourceHosts() {
        for (String host : new String[] {"db.internal", "10.0.0.8", "mysql.example.com"}) {
            GenericApplicationContext context = contextWith(Map.of(
                    "OSHEEEP_DB_NAME", DATABASE,
                    "OSHEEEP_DB_TEST_NAME", DATABASE,
                    "spring.datasource.url", "jdbc:mysql://" + host + ":3306/" + DATABASE));
            assertRejected(initializer(DATABASE, DATABASE, "true"), context);
        }
    }

    @Test
    void rejectsDatasourceCatalogMismatchAndFlywayOverride() {
        GenericApplicationContext wrongCatalog = contextWith(Map.of(
                "OSHEEEP_DB_NAME", DATABASE,
                "OSHEEEP_DB_TEST_NAME", DATABASE,
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:33307/production"));
        assertRejected(initializer(DATABASE, DATABASE, "true"), wrongCatalog);

        Map<String, Object> properties = new HashMap<>();
        properties.put("OSHEEEP_DB_NAME", DATABASE);
        properties.put("OSHEEEP_DB_TEST_NAME", DATABASE);
        properties.put("spring.datasource.url", "jdbc:mysql://127.0.0.1:33307/" + DATABASE);
        properties.put("spring.flyway.url", "jdbc:mysql://127.0.0.1:33307/production");
        assertRejected(initializer(DATABASE, DATABASE, "true"), contextWith(properties));
    }

    @Test
    void rejectsNonLocalProfile() {
        GenericApplicationContext context = safeContext();
        context.getEnvironment().setActiveProfiles("prod");

        assertRejected(initializer(DATABASE, DATABASE, "true"), context);
    }

    private DinnerHouseholdManagementTestDatabaseSafetyInitializer initializer(
            String selected,
            String test,
            String optIn
    ) {
        return new DinnerHouseholdManagementTestDatabaseSafetyInitializer(
                selected, test, optIn);
    }

    private GenericApplicationContext safeContext() {
        return contextWith(Map.of(
                "OSHEEEP_DB_NAME", DATABASE,
                "OSHEEEP_DB_TEST_NAME", DATABASE,
                "spring.datasource.url", "jdbc:mysql://localhost:33307/" + DATABASE));
    }

    private GenericApplicationContext contextWith(Map<String, Object> properties) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("household-management-test-safety", properties));
        context.getEnvironment().setActiveProfiles("local");
        return context;
    }

    private void assertRejected(
            DinnerHouseholdManagementTestDatabaseSafetyInitializer initializer,
            GenericApplicationContext context
    ) {
        assertThatThrownBy(() -> initializer.initialize(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerHouseholdManagementTestDatabaseSafetyInitializer.FAILURE_MESSAGE);
    }
}
