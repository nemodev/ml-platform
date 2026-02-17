package com.mlplatform.dto;

import java.util.UUID;

public record UserInfoResponse(
        UUID id,
        String oidcSubject,
        String username,
        String displayName,
        String email
) {
}
