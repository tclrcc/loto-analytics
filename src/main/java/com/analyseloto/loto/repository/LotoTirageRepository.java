package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.LotoTirage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LotoTirageRepository extends JpaRepository<LotoTirage, Long> {
    /**
     * Recherche si le tirage existe déjà pour une date donnée
     * @param date date tirage
     * @return
     */
    boolean existsByDateTirage(LocalDate date);

    /**
     * Recherche tirages selon un Set de dates
     * @param datesJouees set dates
     * @return
     */
    List<LotoTirage> findByDateTirageIn(Set<LocalDate> datesJouees);

    /**
     * Récupére le tirage le plus récent
     * @return
     */
    Optional<LotoTirage> findTopByOrderByDateTirageDesc();

    // Crée une petite interface (Projection)
    public interface TirageMinimal {
        LocalDate getDateTirage();
        int getBoule1();
        int getBoule2();
        int getBoule3();
        int getBoule4();
        int getBoule5();
        int getNumeroChance();
    }

    // Dans le Repository :
    @Query("SELECT t.dateTirage as dateTirage, t.boule1 as boule1, t.boule2 as boule2, t.boule3 as boule3, t.boule4 as boule4, t.boule5 as boule5, t.numeroChance as numeroChance FROM LotoTirage t ORDER BY t.dateTirage DESC")
    List<TirageMinimal> findAllOptimized();
}
