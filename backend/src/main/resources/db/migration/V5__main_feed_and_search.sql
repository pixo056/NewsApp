-- Preserve previously published material. New community approvals require a separate main-feed review.
ALTER TABLE news ADD COLUMN main_revision_id uuid REFERENCES news_revisions(id);
ALTER TABLE news ADD COLUMN main_published_at timestamptz;
ALTER TABLE news_revisions ADD COLUMN main_status text NOT NULL DEFAULT 'waiting'
  CHECK (main_status IN ('waiting','pending','published','rejected','withdrawn'));
ALTER TABLE news_revisions ADD COLUMN main_rejection_reason text;
ALTER TABLE news_revisions ADD COLUMN main_reviewer_id uuid REFERENCES app_users(id);
ALTER TABLE news_revisions ADD COLUMN main_reviewed_at timestamptz;
UPDATE news SET main_revision_id=published_revision_id,main_published_at=published_at;
UPDATE news_revisions r SET main_status='published' FROM news n WHERE n.main_revision_id=r.id;
CREATE INDEX news_main_feed ON news(main_published_at DESC,id DESC) WHERE NOT deleted AND main_revision_id IS NOT NULL;

-- Explicit Russian/ASCII case folding works even in the portable database's C locale.
-- Translate Unicode whitespace before collapsing; search text and query use the same function.
CREATE FUNCTION news_search(text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT trim(regexp_replace(translate(coalesce($1,''),
    'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯё' ||
    chr(160)||chr(5760)||chr(8192)||chr(8193)||chr(8194)||chr(8195)||chr(8196)||chr(8197)||chr(8198)||chr(8199)||chr(8200)||chr(8201)||chr(8202)||chr(8232)||chr(8233)||chr(8239)||chr(8287)||chr(12288)||chr(133)||chr(65279),
    'abcdefghijklmnopqrstuvwxyzабвгдеежзийклмнопрстуфхцчшщъыьэюяе' || repeat(' ',20)),
    '[[:space:]]+', ' ', 'g'));
$$;
