package com.mlplatform.repository;

import com.mlplatform.model.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, java.util.UUID> {
    Optional<User> findByOidcSubject(String oidcSubject);
}
