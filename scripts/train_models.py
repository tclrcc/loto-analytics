import pandas as pd
import numpy as np
import os
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, BatchNormalization, Dropout
import xgboost as xgb

# Configuration
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
MODEL_DIR = "models"
os.makedirs(MODEL_DIR, exist_ok=True)

def get_multi_hot_encoded(row):
    vector = np.zeros(49)
    for val in row:
        if 1 <= val <= 49: vector[val-1] = 1.0
    return vector

def train_lstm(df):
    print("🧠 Entraînement LSTM en cours...")
    data = df[['boule1', 'boule2', 'boule3', 'boule4', 'boule5']].values

    sequence_length = 12
    vectors = np.array([get_multi_hot_encoded(row) for row in data])

    X, y = [], []
    for i in range(len(vectors) - sequence_length):
        X.append(vectors[i:i+sequence_length])
        y.append(vectors[i+sequence_length])

    X = np.array(X)
    y = np.array(y)

    model = Sequential([
        LSTM(128, return_sequences=True, input_shape=(sequence_length, 49)),
        BatchNormalization(),
        Dropout(0.3),
        LSTM(64),
        Dropout(0.3),
        Dense(49, activation='sigmoid') # Classification Multi-Label
    ])

    model.compile(optimizer='adam', loss='binary_crossentropy')
    model.fit(X, y, epochs=50, batch_size=32, verbose=1)

    model.save(f"{MODEL_DIR}/lstm_v4.h5")
    print("✅ Modèle LSTM sauvegardé (models/lstm_v4.h5)")

def train_xgboost(df):
    print("🌲 Initialisation XGBoost...")
    # Création d'un modèle structurel pour la V4
    model = xgb.XGBClassifier()
    # Dummy train pour initialiser la structure du fichier
    X = np.random.rand(10, 5)
    y = np.random.randint(0, 2, 10)
    model.fit(X, y)
    model.save_model(f"{MODEL_DIR}/xgb_v4.json")
    print("✅ Modèle XGBoost initialisé.")

if __name__ == "__main__":
    csv_path = "loto_history.csv"
    if os.path.exists(csv_path):
        df = pd.read_csv(csv_path)
        train_lstm(df)
        train_xgboost(df)
    else:
        print(f"❌ Erreur: Fichier {csv_path} manquant. Exportez la base d'abord.")
