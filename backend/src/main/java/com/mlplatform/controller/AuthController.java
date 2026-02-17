package com.mlplatform.controller;

import com.mlplatform.dto.UserInfoResponse;
import com.mlplatform.model.User;
import com.mlplatform.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/userinfo")
    public UserInfoResponse userInfo(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.syncFromJwt(jwt);
        return new UserInfoResponse(
                user.getId(),
                user.getOidcSubject(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail()
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
