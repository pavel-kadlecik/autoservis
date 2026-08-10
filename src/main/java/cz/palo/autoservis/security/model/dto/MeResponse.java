package cz.palo.autoservis.security.model.dto;

import java.util.List;

/** Základní informace o právě přihlášeném uživateli. */
public record MeResponse(
        Long id,
        String username,
        String email,
        List<String> roles
) {}
