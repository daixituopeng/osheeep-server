CREATE TABLE dinner_subscription_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    household_id BIGINT NOT NULL,
    scenario VARCHAR(32) NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    notification_type VARCHAR(40) NULL,
    reference_type VARCHAR(32) NULL,
    reference_id BIGINT NULL,
    reference_version BIGINT NULL,
    event_dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    last_error_code INT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    sent_at DATETIME(3) NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_subscription_request (
        recipient_id,
        request_key,
        scenario
    ),
    UNIQUE KEY uk_dinner_subscription_event (event_dedupe_key),
    KEY idx_dinner_subscription_prompt (
        recipient_id,
        household_id,
        scenario,
        status,
        expires_at
    ),
    KEY idx_dinner_subscription_claim (
        status,
        next_attempt_at,
        updated_at,
        id
    ),
    KEY idx_dinner_subscription_household (household_id, id),
    KEY idx_dinner_subscription_expiry (expires_at, id),
    CONSTRAINT chk_dinner_subscription_recipient
        CHECK (recipient_id > 0),
    CONSTRAINT chk_dinner_subscription_household
        CHECK (household_id > 0),
    CONSTRAINT chk_dinner_subscription_scenario
        CHECK (
            CAST(scenario AS BINARY) IN (
                'PARTNER_JOINED',
                'MENU_CHANGED',
                'MENU_COMPLETED'
            )
        ),
    CONSTRAINT chk_dinner_subscription_outcome
        CHECK (
            CAST(outcome AS BINARY) IN (
                'ACCEPT',
                'REJECT',
                'BAN',
                'FILTER'
            )
        ),
    CONSTRAINT chk_dinner_subscription_status
        CHECK (
            CAST(status AS BINARY) IN (
                'WAITING_EVENT',
                'READY',
                'SENDING',
                'SENT',
                'REJECTED',
                'TERMINAL_FAILED'
            )
        ),
    CONSTRAINT chk_dinner_subscription_notification
        CHECK (
            notification_type IS NULL
            OR CAST(notification_type AS BINARY) IN (
                'PARTNER_JOINED',
                'PARTNER_SELECTION_UPDATED',
                'MENU_RECONFIRM_REQUIRED',
                'MENU_COMPLETED'
            )
        ),
    CONSTRAINT chk_dinner_subscription_reference_type
        CHECK (
            reference_type IS NULL
            OR CAST(reference_type AS BINARY) IN (
                'HOUSEHOLD',
                'MENU',
                'RECORD'
            )
        ),
    CONSTRAINT chk_dinner_subscription_event_shape
        CHECK (
            (
                status IN ('WAITING_EVENT', 'REJECTED')
                AND notification_type IS NULL
                AND reference_type IS NULL
                AND reference_id IS NULL
                AND reference_version IS NULL
                AND event_dedupe_key IS NULL
            )
            OR
            (
                status IN (
                    'READY',
                    'SENDING',
                    'SENT',
                    'TERMINAL_FAILED'
                )
                AND notification_type IS NOT NULL
                AND reference_type IS NOT NULL
                AND reference_id > 0
                AND (reference_version IS NULL OR reference_version >= 0)
                AND event_dedupe_key IS NOT NULL
            )
        ),
    CONSTRAINT chk_dinner_subscription_outcome_status
        CHECK (
            (outcome = 'ACCEPT' AND status <> 'REJECTED')
            OR
            (outcome <> 'ACCEPT' AND status = 'REJECTED')
        ),
    CONSTRAINT chk_dinner_subscription_attempts
        CHECK (attempt_count >= 0 AND attempt_count <= 5),
    CONSTRAINT chk_dinner_subscription_times
        CHECK (
            updated_at >= created_at
            AND expires_at > created_at
            AND (sent_at IS NULL OR sent_at >= created_at)
        ),
    CONSTRAINT chk_dinner_subscription_sent
        CHECK (
            (status = 'SENT' AND sent_at IS NOT NULL)
            OR
            (status <> 'SENT' AND sent_at IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
