ALTER TABLE kb_embedding_rebuild
    ADD COLUMN targets_ready BOOLEAN NOT NULL DEFAULT FALSE;
