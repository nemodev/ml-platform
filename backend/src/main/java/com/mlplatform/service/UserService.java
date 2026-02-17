package com.mlplatform.service;

import com.mlplatform.model.User;
import com.mlplatform.repository.UserRepository;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User syncFromJwt(Jwt jwt) {
        String subject = firstNonBlank(
                jwt.getClaimAsString("sub"),
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email")
        );
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("No stable user identifier claim found");
        }

        String username = claimOrFallback(jwt, "preferred_username", "unknown-user");
        String displayName = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");

        User user = userRepository.findByOidcSubject(subject).orElseGet(User::new);
        user.setOidcSubject(subject);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setLastLogin(Instant.now());

        return userRepository.save(user);
    }

    private String claimOrFallback(Jwt jwt, String claimName, String fallback) {
        String value = jwt.getClaimAsString(claimName);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
