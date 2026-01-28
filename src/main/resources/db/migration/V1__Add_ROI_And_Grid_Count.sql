-- V1 : Ajout des colonnes pour le suivi de performance (ROI et Volume)
ALTER TABLE strategy_history
    ADD COLUMN IF NOT EXISTS nb_grilles_par_test INTEGER DEFAULT 50;

ALTER TABLE strategy_history
    ADD COLUMN IF NOT EXISTS roi DOUBLE PRECISION DEFAULT 0.0;
