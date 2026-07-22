package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

class DinnerHouseholdManagementFlywayMigrationStrategyTest {

    private static final String TEST_DATABASE = "hhmgmt_it";

    @Test
    void exactConfiguredAndActualCatalogMigratesOnce() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);

        assertThatCode(() -> new DinnerHouseholdManagementFlywayMigrationStrategy(TEST_DATABASE)
                        .migrate(fixture.flyway()))
                .doesNotThrowAnyException();

        verify(fixture.flyway(), times(1)).migrate();
    }

    @Test
    void blankExpectedCatalogFailsBeforeMigration() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);

        assertRejected(new DinnerHouseholdManagementFlywayMigrationStrategy(" "), fixture);
    }

    @Test
    void redirectedActualCatalogFailsBeforeMigration() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog("production");

        assertRejected(
                new DinnerHouseholdManagementFlywayMigrationStrategy(TEST_DATABASE), fixture);
    }

    @Test
    void mismatchedDefaultSchemaFailsBeforeMigration() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);
        when(fixture.configuration().getDefaultSchema()).thenReturn("production");

        assertRejected(
                new DinnerHouseholdManagementFlywayMigrationStrategy(TEST_DATABASE), fixture);
    }

    @Test
    void mismatchedOrAdditionalSchemasFailBeforeMigration() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);
        when(fixture.configuration().getSchemas())
                .thenReturn(new String[] {TEST_DATABASE, "production"});

        assertRejected(
                new DinnerHouseholdManagementFlywayMigrationStrategy(TEST_DATABASE), fixture);
    }

    private void assertRejected(
            DinnerHouseholdManagementFlywayMigrationStrategy strategy,
            FlywayFixture fixture
    ) {
        assertThatThrownBy(() -> strategy.migrate(fixture.flyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerHouseholdManagementFlywayMigrationStrategy.FAILURE_MESSAGE);
        verify(fixture.flyway(), never()).migrate();
    }

    private FlywayFixture flywayUsingCatalog(String actualCatalog) throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getDefaultSchema()).thenReturn(TEST_DATABASE);
        when(configuration.getSchemas()).thenReturn(new String[] {TEST_DATABASE});
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE()"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(actualCatalog);
        return new FlywayFixture(flyway, configuration);
    }

    private record FlywayFixture(Flyway flyway, Configuration configuration) {
    }
}
