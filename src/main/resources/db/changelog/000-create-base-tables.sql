--liquibase formatted sql
--changeset crawler-dev:000-create-base-tables dbms:postgresql

-- Базовая таблица pages
CREATE TABLE IF NOT EXISTS pages (
                                     id SERIAL PRIMARY KEY,
                                     url TEXT UNIQUE NOT NULL,
                                     title TEXT,
                                     h1 TEXT,
                                     description TEXT,
                                     score DOUBLE PRECISION DEFAULT 0,
                                     keyword_matches INTEGER DEFAULT 0,
                                     crawled_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Базовые индексы
CREATE INDEX IF NOT EXISTS idx_pages_score ON pages(score DESC);
CREATE INDEX IF NOT EXISTS idx_pages_crawled_at ON pages(crawled_at DESC);
