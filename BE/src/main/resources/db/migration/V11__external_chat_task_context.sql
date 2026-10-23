-- Immutable company binding is assigned by the provisioning administrator, never by a task caller.
ALTER TABLE ai_integration_clients ADD COLUMN external_system VARCHAR(40);
ALTER TABLE ai_integration_clients ADD COLUMN external_company_id VARCHAR(128);

CREATE TABLE ai_task_external_context (
    task_id CHAR(36) NOT NULL,
    context_json ${text_type} NOT NULL,
    CONSTRAINT pk_ai_task_external_context PRIMARY KEY (task_id),
    CONSTRAINT fk_ai_task_external_context_task FOREIGN KEY (task_id)
        REFERENCES ai_task_ledger (id) ON DELETE CASCADE
);
