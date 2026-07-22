package com.osheeep.server.dinner.household;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.util.StringUtils;

public final class DinnerHouseholdManagementFlywayMigrationStrategy
        implements FlywayMigrationStrategy {

    static final String FAILURE_MESSAGE =
            "Household management Flyway migration requires the exact ephemeral test catalog";

    private final String expectedDatabase;

    public DinnerHouseholdManagementFlywayMigrationStrategy(String expectedDatabase) {
        this.expectedDatabase = expectedDatabase;
    }

    @Override
    public void migrate(Flyway flyway) {
        Configuration configuration = flyway == null ? null : flyway.getConfiguration();
        if (!StringUtils.hasText(expectedDatabase)
                || configuration == null
                || !Objects.equals(expectedDatabase, configuration.getDefaultSchema())
                || !Arrays.equals(
                        new String[] {expectedDatabase}, configuration.getSchemas())) {
            throw unsafe();
        }
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null || !Objects.equals(expectedDatabase, currentCatalog(dataSource))) {
            throw unsafe();
        }
        flyway.migrate();
    }

    private String currentCatalog(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT DATABASE()")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException exception) {
            throw unsafe(exception);
        }
    }

    private IllegalStateException unsafe() {
        return new IllegalStateException(FAILURE_MESSAGE);
    }

    private IllegalStateException unsafe(Exception cause) {
        return new IllegalStateException(FAILURE_MESSAGE, cause);
    }
}
