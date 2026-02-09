import pandas as pd
import numpy as np
import os
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, BatchNormalization, Dropout, Input, Bidirectional
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau

# Configuration V6
SEQ_LENGTH = 12  # On regarde 12 tirages en arrière
MODEL_DIR = "models"
os.makedirs(MODEL_DIR, exist_ok=True)

def get_multi_hot_encoded(row):
    vector = np.zeros(49)
    for val in row:
        if 1 <= val <= 49: vector[val-1] = 1.0
    return vector

def train_lstm_advanced(df):
    print("🧠 Entraînement LSTM Bidirectionnel V6...")

    # 1. Préparation des données
    data = df[['boule1', 'boule2', 'boule3', 'boule4', 'boule5']].values
    # Inversion : TensorFlow aime chronologique (Vieux -> Récent), CSV est souvent inverse
    # Assure-toi que ton CSV est trié par date croissante avant !

    vectors = np.array([get_multi_hot_encoded(row) for row in data])

    X, y = [], []
    for i in range(len(vectors) - SEQ_LENGTH):
        X.append(vectors[i:i+SEQ_LENGTH])
        y.append(vectors[i+SEQ_LENGTH])

    X = np.array(X)
    y = np.array(y)

    # 2. Architecture V6 "Deep Trend"
    model = Sequential([
        Input(shape=(SEQ_LENGTH, 49)),

        # Bidirectionnel : Comprend le contexte passé ET futur (sur séquence d'entraînement)
        Bidirectional(LSTM(128, return_sequences=True)),
        BatchNormalization(),
        Dropout(0.4), # Forte régularisation

        LSTM(64),
        Dropout(0.3),

        Dense(64, activation='relu'),

        # Sortie : Probabilité pour chaque boule (Sigmoid car multi-label indépendant)
        Dense(49, activation='sigmoid')
    ])

    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
                 loss='binary_crossentropy',
                 metrics=['accuracy'])

    # 3. Callbacks intelligents
    callbacks = [
        EarlyStopping(patience=10, restore_best_weights=True, monitor='loss'),
        ReduceLROnPlateau(factor=0.5, patience=5)
    ]

    model.fit(X, y, epochs=100, batch_size=32, verbose=1, callbacks=callbacks)

    model.save(f"{MODEL_DIR}/lstm_v6.keras")
    print("✅ Modèle V6 sauvegardé.")

if __name__ == "__main__":
    # Assurez-vous que loto_history.csv existe et est à jour
    if os.path.exists("loto_history.csv"):
        df = pd.read_csv("loto_history.csv")
        # Tri par date indispensable pour LSTM
        if 'date_tirage' in df.columns:
            df['date_tirage'] = pd.to_datetime(df['date_tirage'])
            df = df.sort_values('date_tirage')

        train_lstm_advanced(df)
    else:
        print("❌ CSV manquant.")
