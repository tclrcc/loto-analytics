import pandas as pd
import numpy as np
import os
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense, BatchNormalization, Dropout, Input
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau

MODEL_DIR = "models"
os.makedirs(MODEL_DIR, exist_ok=True)

def train_value_model(df):
    print("🧠 Entraînement V8 (Modèle de Rentabilité basé sur les vrais rapports)...")

    # Filtrer les tirages où il y a eu des gagnants pour avoir des rapports valides
    # On cible les rangs intermédiaires (ex: 4 bons numéros) car ils sont plus fréquents et représentatifs du comportement
    df = df.dropna(subset=['rapport_rang_4'])

    X = []
    y = []

    for _, row in df.iterrows():
        # Input (X) : Hot-Encoding de la grille (49 dimensions)
        vector = np.zeros(49)
        boules = [row['boule1'], row['boule2'], row['boule3'], row['boule4'], row['boule5']]
        for b in boules:
            if 1 <= b <= 49:
                vector[b-1] = 1.0
        X.append(vector)

        # Output (y) : Le gain RÉEL généré par cette combinaison
        # Si le rapport moyen habituel est de 50€, et que ce jour là il était de 120€, l'IA va adorer ces numéros.
        y.append(float(row['rapport_rang_4']))

    X = np.array(X)
    y = np.array(y)

    # Standardisation de la cible (très important pour les réseaux de neurones)
    y_mean, y_std = y.mean(), y.std()
    y_scaled = (y - y_mean) / y_std

    model = Sequential([
        Input(shape=(49,)),
        Dense(128, activation='swish'),
        BatchNormalization(),
        Dropout(0.3),
        Dense(64, activation='swish'),
        BatchNormalization(),
        Dropout(0.2),
        Dense(32, activation='swish'),
        Dense(1, activation='linear') # Linear car on prédit une valeur continue (standardisée)
    ])

    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.002),
                  loss='huber', metrics=['mae'])

    callbacks = [
        EarlyStopping(patience=15, restore_best_weights=True, monitor='val_loss'),
        ReduceLROnPlateau(factor=0.5, patience=5)
    ]

    model.fit(X, y_scaled, epochs=150, batch_size=64, validation_split=0.15, verbose=1, callbacks=callbacks)

    # Sauvegarde des paramètres de scaling avec le modèle
    np.save(f"{MODEL_DIR}/y_scaler.npy", np.array([y_mean, y_std]))
    model.save(f"{MODEL_DIR}/value_model_v8.keras")
    print("✅ Modèle V8 'Value Regressor' sauvegardé avec succès.")

if __name__ == "__main__":
    if os.path.exists("loto_history.csv"):
        df = pd.read_csv("loto_history.csv", sep=";") # Attention au délimiteur FDJ
        train_value_model(df)
