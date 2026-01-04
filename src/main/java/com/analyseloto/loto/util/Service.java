package com.analyseloto.loto.util;

public class Service {

    public static int getSecondsFromDays(int days) {
        // Nombre de jours * 24h * 60 minutes * 60 secondes
        return days * 24 * 60 * 60;
    }
}
