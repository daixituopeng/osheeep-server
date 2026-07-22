package com.osheeep.server.dinner.household;

import org.junit.jupiter.api.Test;

/** Guarded MySQL 8 evidence for the complete V8 migration matrix. */
public class DinnerHouseholdManagementMySqlIT {

    @Test
    void migratesExactlyFreshProductionV4AndCurrentV7PathsThroughV8() {
        new DinnerHouseholdManagementMigrationSmokeMySqlIT()
                .migratesFreshProductionV4AndMinimalV7CatalogsThroughV8();
    }
}
