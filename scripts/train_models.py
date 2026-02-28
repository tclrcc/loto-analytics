import pandas as pd
import numpy as np
import os
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense, BatchNormalization, Dropout, Input
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau

MODEL_DIR = "models"
os.makedirs(MODEL_DIR, exist_ok=True)

def calculate_target_value(row):
    """
    Calcule la 'Valeur' de la grille.
    Si tu as scrapé les vrais gains FDJ, tu peux utiliser la colonne directement.
    Sinon, on génère un score synthétique basé sur les biais cognitifs humains.
    """
    # Si la vraie donnée existe, on l'utilise (Ex: gain moyen au rang 3 ou 4)
    # if 'rapport_rang_4' in row and pd.notna(row['rapport_rang_4']):
    #     return float(row['rapport_rang_4'])

    # --- PROXY D'IMPOPULARITÉ (Si pas de vraies données) ---
    score = 10.0 # Note de base
    boules = [row['boule1'], row['boule2'], row['boule3'], row['boule4'], row['boule5']]

    # 1. Biais des dates de naissance (La majorité des joueurs font ça)
    for b in boules:
        if b <= 12:
            score -= 1.0  # Mois (Très joué -> Gain faible si ça sort)
        elif b <= 31:
            score -= 0.5  # Jours (Joué -> Gain moyen)
        else:
            score += 1.5  # Hors dates (Peu joué -> Gain très fort)

    # 2. Biais des suites logiques
    boules_sorted = sorted(boules)
    for i in range(4):
        if boules_sorted[i+1] - boules_sorted[i] == 1:
            score -= 2.0  # Les suites (1,2,3) sont très jouées par les humains

    # 3. Biais des dizaines (Les gens aiment équilibrer)
    dizaines = len(set([b // 10 for b in boules]))
    if dizaines <= 2:
        score += 1.0 # Grilles très déséquilibrées (ex: que des 30 et 40) sont peu jouées

    return max(1.0, score) # On retourne une valeur toujours positive

def train_value_model(df):
    print("🧠 Entraînement V8 (Modèle de Rentabilité et d'Impopularité)...")

    X = []
    y = []

    # Extraction des données
    for _, row in df.iterrows():
        # Input (X) : Hot-Encoding de la grille (49 dimensions)
        vector = np.zeros(49)
        boules = [row['boule1'], row['boule2'], row['boule3'], row['boule4'], row['boule5']]
        for b in boules:
            if 1 <= b <= 49:
                vector[b-1] = 1.0
        X.append(vector)

        # Output (y) : La valeur financière théorique de cette grille
        y.append(calculate_target_value(row))

    X = np.array(X)
    y = np.array(y)

    # Architecture Feed-Forward (Régression)
    # L'objectif est d'apprendre "quelle zone de l'espace vectoriel rapporte le plus"
    model = Sequential([
        Input(shape=(49,)),

        Dense(128, activation='swish'),
        BatchNormalization(),
        Dropout(0.3),

        Dense(64, activation='swish'),
        BatchNormalization(),
        Dropout(0.2),

        Dense(32, activation='swish'),

        # Couche de sortie : 1 seul neurone (La valeur de rentabilité prédite)
        Dense(1, activation='relu')
    ])

    # Huber Loss est parfaite ici car elle ignore les valeurs extrêmes (outliers)
    # qui pourraient fausser l'apprentissage (ex: un jackpot exceptionnel)
    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.002),
                  loss='huber',
                  metrics=['mae'])

    callbacks = [
        EarlyStopping(patience=15, restore_best_weights=True, monitor='val_loss'),
        ReduceLROnPlateau(factor=0.5, patience=5)
    ]

    print("🚀 Début de l'entraînement...")
    model.fit(X, y, epochs=150, batch_size=64, validation_split=0.15, verbose=1, callbacks=callbacks)

    # On sauvegarde au format Keras
    model.save(f"{MODEL_DIR}/value_model_v8.keras")
    print("✅ Modèle V8 'Value Regressor' sauvegardé avec succès.")

if __name__ == "__main__":
    if os.path.exists("loto_history.csv"):
        df = pd.read_csv("loto_history.csv")
        train_value_model(df)
    else:
        print("❌ CSV manquant. Veuillez télécharger l'historique.")
