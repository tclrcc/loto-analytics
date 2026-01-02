package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.Tirage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface TirageRepository extends JpaRepository<Tirage, Long> {
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
    List<Tirage> findByDateTirageIn(Set<LocalDate> datesJouees);
}
