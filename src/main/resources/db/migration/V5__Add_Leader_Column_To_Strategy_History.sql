-- Migration pour ajouter la colonne 'leader' à la table strategy_history
-- On définit une valeur par défaut à 'false' pour ne pas casser les données existantes
ALTER TABLE strategy_history ADD COLUMN leader BOOLEAN DEFAULT FALSE;

-- Optionnel : Marquer le tirage le plus récent comme leader par défaut pour initialiser proprement
UPDATE strategy_history
SET leader = TRUE
WHERE id IN (
    SELECT id FROM strategy_history ORDER BY date_calcul DESC LIMIT 1
    );
