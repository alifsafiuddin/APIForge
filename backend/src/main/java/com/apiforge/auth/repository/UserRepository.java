package com.apiforge.auth.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.auth.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Case-insensitive: the column is {@code citext}. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Row lock so concurrent sign-in attempts update the failure counter one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
