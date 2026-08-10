package cz.palo.autoservis.security.model.dto;

/**
 * Pár tokenů vydávaný při úspěšném přihlášení nebo obnově tokenů.
 *
 * @param accessToken  krátkodobý JWT pro autorizaci API (posílá se v HTTP-only cookie)
 * @param refreshToken dlouhodobý neprůhledný token pro získání nového access tokenu
 */
public record TokenResponse(
        String accessToken,
        String refreshToken
) {}
