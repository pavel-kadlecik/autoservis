package cz.palo.autoservis.model.dto.user;

import cz.palo.autoservis.model.dto.RoleDto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO pro adminskou správu uživatelských účtů ({@code security.users}).
 */
public class UserDto {

    /** Request DTO pro založení nového uživatelského účtu. */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Uživatelské jméno je povinné")
        @Size(min = 3, max = 20, message = "Uživatelské jméno musí mít 3 až 20 znaků")
        private String username;

        // @NotBlank, ne @NotNull — @NotNull i @Email propustí "", které pak spadne
        // až na DB CHECK chk_users_email jako 422 místo srozumitelné 400.
        @NotBlank(message = "Email je povinný")
        @Email(message = "Neplatný formát emailu")
        @Size(max = 255)
        private String email;

        @NotNull(message = "Heslo je povinné")
        @Size(min = 8, message = "Heslo musí mít alespoň 8 znaků")
        private String password;

        @NotEmpty(message = "Uživatel musí mít alespoň jednu roli")
        private List<Integer> roleIds;
    }

    /** Request DTO pro úpravu existujícího účtu. Uživatelské jméno je neměnné. */
    @Data
    public static class UpdateRequest {

        @NotBlank(message = "Email je povinný")
        @Email(message = "Neplatný formát emailu")
        @Size(max = 255)
        private String email;

        @NotEmpty(message = "Uživatel musí mít alespoň jednu roli")
        private List<Integer> roleIds;
    }

    /** Request DTO pro adminem spuštěný reset hesla — aktuální heslo se nevyžaduje. */
    @Data
    public static class ResetPasswordRequest {

        @NotNull(message = "Nové heslo je povinné")
        @Size(min = 8, message = "Heslo musí mít alespoň 8 znaků")
        private String newPassword;
    }

    /** Response DTO pro stránkované seznamové endpointy. */
    @Data
    public static class ListResponse {
        private Long id;
        private String username;
        private String email;
        private boolean enabled;
        private List<String> roles;
        private OffsetDateTime lastLoginAt;
        private OffsetDateTime createdAt;
    }

    /** Response DTO pro detailové endpointy. */
    @Data
    public static class DetailResponse {
        private Long id;
        private String username;
        private String email;
        private boolean enabled;
        private boolean accountNonLocked;
        private int failedLoginAttempts;
        private List<RoleDto> roles;
        private OffsetDateTime lastLoginAt;
        private OffsetDateTime passwordChangedAt;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }
}
