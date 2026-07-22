package com.osheeep.server.dinner.household;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

public final class DinnerHouseholdManagementTestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String FAILURE_MESSAGE =
            "Household management integration tests require an explicit loopback ephemeral database";

    private final String rawSelectedDatabase;
    private final String rawTestDatabase;
    private final String rawEphemeralOptIn;

    public DinnerHouseholdManagementTestDatabaseSafetyInitializer() {
        this(
                System.getenv("OSHEEEP_DB_NAME"),
                System.getenv("OSHEEEP_DB_TEST_NAME"),
                System.getenv("OSHEEEP_ALLOW_EPHEMERAL_DATABASES"));
    }

    DinnerHouseholdManagementTestDatabaseSafetyInitializer(
            String rawSelectedDatabase,
            String rawTestDatabase,
            String rawEphemeralOptIn
    ) {
        this.rawSelectedDatabase = rawSelectedDatabase;
        this.rawTestDatabase = rawTestDatabase;
        this.rawEphemeralOptIn = rawEphemeralOptIn;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        requireSafe(applicationContext.getEnvironment());
    }

    private void requireSafe(Environment environment) {
        String selectedDatabase = environment.getProperty("OSHEEEP_DB_NAME");
        String testDatabase = environment.getProperty("OSHEEEP_DB_TEST_NAME");
        MysqlTarget target = mysqlTarget(environment.getProperty("spring.datasource.url"));
        boolean localProfile = Arrays.asList(environment.getActiveProfiles()).contains("local");

        if (!localProfile
                || !"true".equals(rawEphemeralOptIn)
                || !StringUtils.hasText(selectedDatabase)
                || !Objects.equals(selectedDatabase, testDatabase)
                || !Objects.equals(selectedDatabase, rawSelectedDatabase)
                || !Objects.equals(selectedDatabase, rawTestDatabase)
                || target == null
                || !isLoopback(target.host())
                || !Objects.equals(selectedDatabase, target.catalog())
                || StringUtils.hasText(environment.getProperty("spring.flyway.url"))) {
            throw new IllegalStateException(FAILURE_MESSAGE);
        }
    }

    private MysqlTarget mysqlTarget(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String path = uri.getPath();
            if (!"mysql".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(uri.getHost())
                    || path == null
                    || !path.startsWith("/")
                    || path.length() == 1
                    || path.substring(1).contains("/")) {
                return null;
            }
            return new MysqlTarget(uri.getHost(), path.substring(1));
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private record MysqlTarget(String host, String catalog) {
    }
}
