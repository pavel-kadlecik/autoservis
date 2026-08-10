package cz.palo.autoservis.security.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Data self-service změny hesla zaslaná právě přihlášeným uživatelem. */
public record ChangePasswordRequest(

        @NotBlank(message = "Současné heslo je povinné")
        String currentPassword,

        @NotBlank(message = "Nové heslo je povinné")
        @Size(min = 8, message = "Nové heslo musí mít alespoň 8 znaků")
        String newPassword
) {}
