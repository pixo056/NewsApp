CREATE TABLE news_viewers (
 news_id UUID NOT NULL REFERENCES news ON DELETE CASCADE,
 viewer_key VARCHAR(66) NOT NULL,
 viewed_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(news_id,viewer_key)
);
CREATE INDEX news_viewers_expiry ON news_viewers(viewed_at);
