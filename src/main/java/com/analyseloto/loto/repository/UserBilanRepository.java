package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBilan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserBilanRepository extends JpaRepository<UserBilan, Long> {
    Optional<UserBilan> findTopByUserOrderByDateBilanDesc(User user);

    List<UserBilan> findByUserOrderByDateBilanAsc(User user);
}
