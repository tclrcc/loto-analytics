package com.analyseloto.loto.enums;

import lombok.Getter;

@Getter
public enum LotoRank {
    RANK_1(1, "5 numéros + Chance", "Jackpot"),
    RANK_2(2, "5 numéros", "5 bons numéros"),
    RANK_3(3, "4 numéros + Chance", "4 numéros + Chance"),
    RANK_4(4, "4 numéros", "4 numéros"),
    RANK_5(5, "3 numéros + Chance", "3 numéros + Chance"),
    RANK_6(6, "3 numéros", "3 numéros"),
    RANK_7(7, "2 numéros + Chance", "2 numéros + Chance"),
    RANK_8(8, "2 numéros", "2 numéros"),
    RANK_9(9, "N° Chance uniquement", "Remboursement");

    private final int position;
    private final String description;
    private final String label;

    LotoRank(int position, String description, String label) {
        this.position = position;
        this.description = description;
        this.label = label;
    }

    // Méthode statique pour retrouver l'Enum depuis le chiffre JSON
    public static LotoRank fromPosition(int pos) {
        for (LotoRank r : values()) {
            if (r.position == pos) return r;
        }
        return null; // ou throw Exception
    }
}
