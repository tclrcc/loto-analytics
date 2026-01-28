-- V3 : Ajout des index de performance pour les recherches et tris

-- 1. Index sur les boules (LotoTirage) pour accélérer les recherches statistiques
-- On utilise IF NOT EXISTS pour éviter les erreurs si on relance le script
CREATE INDEX IF NOT EXISTS idx_b1 ON tirage (boule1);
CREATE INDEX IF NOT EXISTS idx_b2 ON tirage (boule2);

-- 2. Index sur la date de calcul (StrategyConfig)
-- Vital car on fait souvent "ORDER BY date_calcul DESC LIMIT 1"
CREATE INDEX IF NOT EXISTS idx_date_calcul ON strategy_history (date_calcul);
