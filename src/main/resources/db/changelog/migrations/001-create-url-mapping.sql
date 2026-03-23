--liquibase formatted sql
--changeset swiftlink:001 author:swiftlink
CREATE TABLE url_mapping (
    id         BIGINT PRIMARY KEY,
    short_code VARCHAR(10) UNIQUE NOT NULL, -- NOSONAR: VARCHAR2 is Oracle-specific; PostgreSQL uses VARCHAR
    long_url   TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_short_code ON url_mapping(short_code);
--rollback DROP INDEX idx_short_code; DROP TABLE url_mapping;
