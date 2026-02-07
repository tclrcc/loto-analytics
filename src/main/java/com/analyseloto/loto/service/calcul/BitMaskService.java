package com.analyseloto.loto.service.calcul;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BitMaskService {

    /**
     * Méthode calcul BitMask
     * @param boules numéros
     * @return valeur BitMask
     */
    public long calculerBitMask(List<Integer> boules) {
        long mask = 0L;
        for (Integer b : boules) mask |= (1L << b);
        return mask;
    }

    /**
     * Méthode calcul BitMask à partir d'un tableau d'entiers
     * @param boules numéros
     * @return valeur BitMask
     */
    public long calculerBitMask(int[] boules) {
        long mask = 0L;
        for (int b : boules) {
            mask |= (1L << b);
        }
        return mask;
    }

    /**
     * Méthode pour décoder les bit mask
     * @param mask mask
     * @return liste des numéros
     */
    public List<Integer> decodeBitMask(long mask) {
        List<Integer> result = new ArrayList<>(5);
        for (int i = 1; i <= 49; i++) {
            if ((mask & (1L << i)) != 0) {
                result.add(i);
            }
        }
        return result;
    }
}
