-- Vital pour findAllOptimized (ORDER BY date_tirage DESC)
CREATE INDEX IF NOT EXISTS idx_date_tirage ON tirage (date_tirage);
