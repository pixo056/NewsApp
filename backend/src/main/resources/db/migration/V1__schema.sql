CREATE TABLE app_users (
 id UUID PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE, email VARCHAR(254) NOT NULL UNIQUE,
 display_name VARCHAR(80) NOT NULL, bio VARCHAR(500) NOT NULL DEFAULT '', password_hash TEXT NOT NULL,
 role VARCHAR(12) NOT NULL CHECK(role IN ('USER','ADMIN')), verified BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE communities (
 id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(500) NOT NULL,
 initials VARCHAR(8) NOT NULL, color VARCHAR(12) NOT NULL, category VARCHAR(32) NOT NULL,
 owner_id UUID NOT NULL REFERENCES app_users, official BOOLEAN NOT NULL DEFAULT FALSE,
 daily_limit INTEGER NOT NULL DEFAULT 5 CHECK(daily_limit BETWEEN 1 AND 1000), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE news (
 id UUID PRIMARY KEY, author_id UUID NOT NULL REFERENCES app_users, community_id UUID REFERENCES communities,
 published_revision_id UUID, latest_revision_id UUID, published_at TIMESTAMPTZ,
 deleted BOOLEAN NOT NULL DEFAULT FALSE, views INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE news_revisions (
 id UUID PRIMARY KEY, news_id UUID NOT NULL REFERENCES news, title VARCHAR(150) NOT NULL,
 body TEXT NOT NULL DEFAULT '', category VARCHAR(32) NOT NULL, source_url VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(12) NOT NULL CHECK(status IN ('pending','published','rejected','withdrawn')),
 rejection_reason VARCHAR(2000), reviewer_id UUID REFERENCES app_users, reviewed_at TIMESTAMPTZ,
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE news ADD CONSTRAINT fk_published_revision FOREIGN KEY(published_revision_id) REFERENCES news_revisions;
ALTER TABLE news ADD CONSTRAINT fk_latest_revision FOREIGN KEY(latest_revision_id) REFERENCES news_revisions;
CREATE TABLE media_assets (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES app_users, kind VARCHAR(8) NOT NULL CHECK(kind IN ('image','video')),
 filename TEXT NOT NULL, poster_filename TEXT, width INTEGER NOT NULL, height INTEGER NOT NULL,
 duration DOUBLE PRECISION, size_bytes BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE revision_media (
 revision_id UUID NOT NULL REFERENCES news_revisions, media_id UUID NOT NULL REFERENCES media_assets,
 sort_order INTEGER NOT NULL, PRIMARY KEY(revision_id,media_id)
);
CREATE TABLE subscriptions(user_id UUID NOT NULL REFERENCES app_users, community_id UUID NOT NULL REFERENCES communities, PRIMARY KEY(user_id,community_id));
CREATE TABLE bookmarks(user_id UUID NOT NULL REFERENCES app_users, news_id UUID NOT NULL REFERENCES news, PRIMARY KEY(user_id,news_id));
CREATE TABLE reactions(user_id UUID NOT NULL REFERENCES app_users, news_id UUID NOT NULL REFERENCES news, PRIMARY KEY(user_id,news_id));
CREATE TABLE reports (
 id UUID PRIMARY KEY, news_id UUID NOT NULL REFERENCES news, reporter_id UUID NOT NULL REFERENCES app_users,
 reason VARCHAR(100) NOT NULL, comment VARCHAR(2000) NOT NULL DEFAULT '', status VARCHAR(12) NOT NULL DEFAULT 'pending',
 resolution VARCHAR(2000), reviewer_id UUID REFERENCES app_users, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_at TIMESTAMPTZ
);
CREATE TABLE account_tokens (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users, token_hash VARCHAR(64) NOT NULL UNIQUE,
 purpose VARCHAR(12) NOT NULL CHECK(purpose IN ('verify','reset')), expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE local_mail (
 id UUID PRIMARY KEY, recipient VARCHAR(254) NOT NULL, subject VARCHAR(200) NOT NULL,
 body TEXT NOT NULL, action_url TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE notifications (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users, message VARCHAR(500) NOT NULL,
 news_id UUID REFERENCES news, read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_log (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES app_users, action VARCHAR(50) NOT NULL,
 target_id UUID NOT NULL, detail VARCHAR(2000) NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX news_feed ON news(published_at DESC,id DESC) WHERE deleted=FALSE AND published_revision_id IS NOT NULL;
CREATE INDEX revision_queue ON news_revisions(status,submitted_at);
CREATE INDEX user_daily_quota ON news(author_id,created_at);
CREATE INDEX revisions_daily ON news_revisions(news_id,submitted_at);
CREATE INDEX media_access ON revision_media(media_id);
CREATE INDEX reports_queue ON reports(status,created_at);
