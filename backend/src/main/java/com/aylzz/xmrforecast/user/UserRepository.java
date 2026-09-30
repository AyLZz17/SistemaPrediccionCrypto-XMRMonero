package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Roles del usuario en la sesion, para autorizacion a nivel de recurso. */
    @Query("select r from User u join u.roles r where u.id = :userId")
    List<Role> findRolesById(@Param("userId") Long userId);

    long countByStatus(UserStatus status);
}