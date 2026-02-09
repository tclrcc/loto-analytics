import pandas as pd
import numpy as np
import os
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, BatchNormalization, Dropout, Input, Bidirectional
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau

# Configuration V7 "Deep Feature"
SEQ_LENGTH = 12
MODEL_DIR = "models"
os.makedirs(MODEL_DIR, exist_ok=True)

# NOUVEAU : Calculateur de Features "Structurelles"
def get_features(row):
    # 1. Hot Encoding (Le "Quoi") - 49 entrées
    vector = np.zeros(49)
    boules = [x for x in row if 1 <= x <= 49]
    for val in boules:
        vector[val-1] = 1.0

    # 2. Structural Features (Le "Comment") - 3 entrées supplémentaires
    # A. Somme Normalisée (Moyenne ~150, Max ~240)
    s = sum(boules)
    feat_sum = s / 255.0

    # B. Parité (Ratio de pairs)
    pairs = len([x for x in boules if x % 2 == 0])
    feat_parity = pairs / 5.0

    # C. Somme des Finales (0-9)
    finales = sum([x % 10 for x in boules])
    feat_finales = finales / 45.0 # Max 9*5

    # Concaténation : 49 + 3 = 52 features
    return np.concatenate([vector, [feat_sum, feat_parity, feat_finales]])

def train_lstm_advanced(df):
    print("🧠 Entraînement V7 (Structure + Valeur)...")

    data = df[['boule1', 'boule2', 'boule3', 'boule4', 'boule5']].values

    # Transformation V7
    vectors = np.array([get_features(row) for row in data])
    input_dim = 52 # 49 boules + 3 meta-features

    X, y = [], []
    # Pour Y (la cible), on ne garde que les 49 boules (on ne prédit pas la somme, on prédit le tirage)
    for i in range(len(vectors) - SEQ_LENGTH):
        X.append(vectors[i:i+SEQ_LENGTH])      # Input : Contexte Riche (52)
        y.append(vectors[i+SEQ_LENGTH][:49])   # Output : Tirage Brut (49)

    X = np.array(X)
    y = np.array(y)

    model = Sequential([
        Input(shape=(SEQ_LENGTH, input_dim)),

        # Architecture V7 : Plus large pour absorber les meta-données
        Bidirectional(LSTM(144, return_sequences=True)), # 128 -> 144
        BatchNormalization(),
        Dropout(0.35),

        LSTM(72), # 64 -> 72
        Dropout(0.3),

        Dense(64, activation='swish'), # Swish est excellent pour le Deep Learning moderne

        Dense(49, activation='sigmoid')
    ])

    model.compile(optimizer='adam',
                 loss='binary_crossentropy',
                 metrics=['accuracy'])

    callbacks = [
        EarlyStopping(patience=12, restore_best_weights=True, monitor='loss'),
        ReduceLROnPlateau(factor=0.5, patience=6)
    ]

    model.fit(X, y, epochs=120, batch_size=32, verbose=1, callbacks=callbacks)
    model.save(f"{MODEL_DIR}/lstm_v7.keras")
    print("✅ Modèle V7 sauvegardé (52 features input).")

if __name__ == "__main__":
    if os.path.exists("loto_history.csv"):
        df = pd.read_csv("loto_history.csv")
        if 'date_tirage' in df.columns:
            df['date_tirage'] = pd.to_datetime(df['date_tirage'])
            df = df.sort_values('date_tirage')
        train_lstm_advanced(df)
    else:
        print("❌ CSV manquant.")
