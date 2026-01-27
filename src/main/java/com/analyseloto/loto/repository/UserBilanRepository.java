package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBilan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserBilanRepository extends JpaRepository<UserBilan, Long> {
    Optional<UserBilan> findTopByUserOrderByDateBilanDesc(User user);

    List<UserBilan> findByUserOrderByDateBilanAsc(User user);

    Optional<UserBilan> findByUserAndDateBilan(User user, LocalDate dateBilan);

    Optional<UserBilan> findTopByUserAndDateBilanBeforeOrderByDateBilanDesc(User user, LocalDate date);
}
