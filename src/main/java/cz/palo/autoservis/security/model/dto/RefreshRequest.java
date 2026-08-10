package cz.palo.autoservis.security.model.dto;

import jakarta.validation.constraints.NotBlank;

/** Tělo requestu pro endpoint obnovy tokenů. */
public record RefreshRequest(
        @NotBlank(message = "Refresh token je povinný")
        String refreshToken
) {}
