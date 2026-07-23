CREATE TABLE dinner_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    household_id BIGINT NULL,
    type VARCHAR(40) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id BIGINT NOT NULL,
    reference_version BIGINT NULL,
    dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_notifications_dedupe (dedupe_key),
    KEY idx_dinner_notifications_recipient_feed (recipient_id, expires_at, id),
    KEY idx_dinner_notifications_recipient_unread (recipient_id, read_at, expires_at, id),
    KEY idx_dinner_notifications_household (household_id, id),
    KEY idx_dinner_notifications_expiry (expires_at, id),
    CONSTRAINT chk_dinner_notifications_recipient
        CHECK (recipient_id > 0),
    CONSTRAINT chk_dinner_notifications_household
        CHECK (household_id IS NULL OR household_id > 0),
    CONSTRAINT chk_dinner_notifications_type
        CHECK (
            CAST(type AS BINARY) IN (
                'PARTNER_JOINED',
                'PARTNER_SELECTION_UPDATED',
                'MENU_RECONFIRM_REQUIRED',
                'MENU_COMPLETED',
                'FAMILY_RECIPE_UPDATED',
                'INVENTORY_UPDATED',
                'OWNERSHIP_TRANSFERRED',
                'MEMBER_LEFT',
                'MEMBER_REMOVED'
            )
        ),
    CONSTRAINT chk_dinner_notifications_reference_type
        CHECK (
            CAST(reference_type AS BINARY) IN (
                'HOUSEHOLD',
                'MENU',
                'RECORD',
                'RECIPE',
                'INVENTORY',
                'HOUSEHOLD_OPERATION'
            )
        ),
    CONSTRAINT chk_dinner_notifications_reference
        CHECK (
            reference_id > 0
            AND (reference_version IS NULL OR reference_version >= 0)
        ),
    CONSTRAINT chk_dinner_notifications_scope
        CHECK (
            (type = 'MEMBER_REMOVED' AND household_id IS NULL)
            OR
            (type <> 'MEMBER_REMOVED' AND household_id IS NOT NULL)
        ),
    CONSTRAINT chk_dinner_notifications_read_time
        CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT chk_dinner_notifications_expiry
        CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
