--liquibase formatted sql
--changeset crawler-dev:001-enhance-crawler-schema dbms:postgresql

-- Добавляем новые колонки
ALTER TABLE pages ADD COLUMN IF NOT EXISTS domain TEXT;
ALTER TABLE pages ADD COLUMN IF NOT EXISTS content_text TEXT;
ALTER TABLE pages ADD COLUMN IF NOT EXISTS crawl_depth INTEGER DEFAULT 0;
ALTER TABLE pages ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'new';
ALTER TABLE pages ADD COLUMN IF NOT EXISTS error_message TEXT;

-- Индексы для фильтрации
CREATE INDEX IF NOT EXISTS idx_pages_domain ON pages(domain);
CREATE INDEX IF NOT EXISTS idx_pages_status ON pages(status);
CREATE INDEX IF NOT EXISTS idx_pages_depth ON pages(crawl_depth);

-- Полнотекстовый поиск
ALTER TABLE pages ADD COLUMN IF NOT EXISTS search_vector tsvector;
CREATE INDEX IF NOT EXISTS idx_pages_search ON pages USING GIN(search_vector);

-- Триггер для авто-обновления search_vector
CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('russian', coalesce(NEW.h1, '')), 'B') ||
        setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'C') ||
        setweight(to_tsvector('russian', coalesce(NEW.content_text, '')), 'D');
RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tsvector_update ON pages;
CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON pages
                         FOR EACH ROW EXECUTE FUNCTION update_search_vector();

-- Таблица задач краулинга
CREATE TABLE IF NOT EXISTS crawl_jobs (
                                          id UUID PRIMARY KEY,
                                          seed_url TEXT NOT NULL,
                                          keywords TEXT[] NOT NULL,
                                          max_depth INTEGER DEFAULT 3,
                                          threads INTEGER DEFAULT 5,
                                          status VARCHAR(20) DEFAULT 'running',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    stats_processed INTEGER DEFAULT 0,
    stats_failed INTEGER DEFAULT 0
    );

CREATE INDEX IF NOT EXISTS idx_crawl_jobs_status ON crawl_jobs(status);
CREATE INDEX IF NOT EXISTS idx_crawl_jobs_created ON crawl_jobs(created_at DESC);
