package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByRole(String role);
    boolean existsByUsername(String username);
    Optional<User> findByEmailOrUsername(String email, String username);
}
