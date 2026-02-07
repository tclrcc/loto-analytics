-- V5 : Ajout de la colonne 'leader' pour identifier le meilleur algo
-- Utilisation de IF NOT EXISTS pour éviter l'erreur en prod si la colonne est déjà là

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='strategy_history' AND column_name='leader') THEN
ALTER TABLE strategy_history ADD COLUMN leader BOOLEAN DEFAULT FALSE;
END IF;
END $$;
