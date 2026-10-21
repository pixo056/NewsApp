ALTER TABLE reactions ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'like';
ALTER TABLE reactions ADD CONSTRAINT reaction_kind CHECK(kind IN ('like','celebrate','insightful','support'));
CREATE INDEX reactions_news ON reactions(news_id);
