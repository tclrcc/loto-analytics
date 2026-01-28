-- V2 : Rattrapage et Ajout des colonnes ROI et Volume
-- On utilise IF NOT EXISTS pour ne pas planter si la colonne est déjà là

ALTER TABLE strategy_history
    ADD COLUMN IF NOT EXISTS nb_grilles_par_test INTEGER DEFAULT 50;

ALTER TABLE strategy_history
    ADD COLUMN IF NOT EXISTS roi DOUBLE PRECISION DEFAULT 0.0;
