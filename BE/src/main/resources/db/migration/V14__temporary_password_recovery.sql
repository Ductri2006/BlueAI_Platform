ALTER TABLE app_users ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN temporary_password_expires_at TIMESTAMP(6) NULL;
ALTER TABLE app_users ADD COLUMN temporary_password_consumed_at TIMESTAMP(6) NULL;
