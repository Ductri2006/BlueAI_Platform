ALTER TABLE studio_bot_dialog_nodes
    ADD COLUMN english_response_text ${text_type} NULL;
ALTER TABLE studio_bot_dialog_nodes
    ADD COLUMN english_missing_entity_prompt VARCHAR(1000) NULL;
ALTER TABLE studio_bot_dialog_nodes
    ADD COLUMN english_irrelevant_response_text VARCHAR(1000) NULL;
