package com.example.mealcheck.repository;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserDietProfileRepository extends JpaRepository<UserDietProfile, Long> {
    Optional<UserDietProfile> findByUser(UserAccount user);
}
