package com.osheeep.server.dinner.household;

import org.springframework.test.context.ContextConfiguration;

/**
 * Named V8 concurrency acceptance suite. The inherited tests use the real Spring transaction
 * stack and a guarded, dedicated MySQL 8 catalog.
 */
@ContextConfiguration(
        initializers = DinnerHouseholdManagementTestDatabaseSafetyInitializer.class)
public class DinnerHouseholdManagementConcurrencyMySqlIT
        extends DinnerMembershipTerminationMySqlIT {
}
