package com.aylzz.xmrforecast.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

    boolean existsByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

    java.util.List<OAuthAccount> findAllByUserId(Long userId);

    /** Numero de cuentas Google vinculadas a un correo, para detectar cuentas duplicadas. */
    @Query("select count(o) from OAuthAccount o where o.providerEmail = :email")
    long countByEmail(String email);
}