package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.Tirage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface TirageRepository extends JpaRepository<Tirage, Long> {
    boolean existsByDateTirage(LocalDate date);
    List<Tirage> findByDateTirageIn(Set<LocalDate> datesJouees);
}
