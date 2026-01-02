package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.LotoTirage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public interface LotoTirageRepository extends JpaRepository<LotoTirage, Long> {
    /**
     * Méthode recherche si le tirage existe déjà pour une date donnée
     * @param date date tirage
     * @return
     */
    boolean existsByDateTirage(LocalDate date);

    /**
     * Méthode recherche tirages selon un Set de dates
     * @param datesJouees set dates
     * @return
     */
    List<LotoTirage> findByDateTirageIn(Set<LocalDate> datesJouees);
}
