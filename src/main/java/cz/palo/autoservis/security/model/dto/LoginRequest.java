package cz.palo.autoservis.security.model.dto;

/** Přihlašovací údaje zaslané klientem. */
public record LoginRequest(String username, String password) {}
