# Funkce: Správa uživatelů a hesel

> Funkční dokumentace — **co** funkce dělá a **proč** je postavená takhle.
> Technické detaily vrstev: [databaze.md §1](../databaze.md) (schéma `security.users`/`roles`/`user_roles` — beze změny, tabulky existovaly už od V1) · [backend.md §3](../backend.md) (sekce Security) · [api.md](../api.md) (sekce Uživatelé (admin) + Auth) · [frontend.md §5](../frontend.md) (sekce Správa uživatelů) · [konvence.md §19](../konvence.md) (`@PreAuthorize`).
> Uživatelská nápověda: v aplikaci záložka **Nápověda** (`frontend/…/src/help/sprava-uzivatelu.md`).
> Hloubkový průvodce implementací: [docs/pruvodce/sprava-uzivatelu.md](../pruvodce/sprava-uzivatelu.md).

## Co funkce dělá

Administrátor spravuje přihlašovací účty zaměstnanců přímo v aplikaci — dosud existovala jen autentizace (login/registrace), ale žádný CRUD nad `security.users`:

- **seznam uživatelů** s vyhledáváním, filtrem „jen aktivní" a přehledem přiřazených rolí,
- **založení účtu** — uživatelské jméno, email, počáteční heslo, jedna nebo víc rolí,
- **úprava účtu** — email a role (uživatelské jméno je po založení needitovatelné),
- **de/aktivace účtu** — soft-delete ekvivalent přes sloupec `enabled`,
- **reset hesla adminem** — nastaví nové heslo libovolnému uživateli bez znalosti současného; zároveň účet **odemkne** a **odvolá všechny jeho přihlášené relace**,
- **změna vlastního hesla** — samostatná funkce dostupná komukoli přihlášenému, vyžaduje současné heslo (na rozdíl od admin resetu); rovněž odvolá všechny relace.

**Uzamčení účtu.** Po 10 neúspěšných přihlášeních se účet zamkne a ani správné heslo neprojde.
Zámek **vyprší sám** po 15 minutách (`lockout.duration`); admin reset hesla ho zruší okamžitě.
Do auditu 2026-07-30 byl zámek trvalý — kdokoli mohl deseti požadavky na veřejné přihlášení
natrvalo vyřadit jediný administrátorský účet a obnova vyžadovala zásah přímo v databázi (KN-5).

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Celý `UserController` je `@PreAuthorize hasRole('ADMIN')`** | správa účtů a rolí je citlivá operace vyhrazená administrátorům; druhý příklad rolové autorizace v API vůbec (první je import PDF faktur) |
| **Reset hesla (admin) a změna hesla (self-service) jsou dva různé endpointy** | admin reset nezná/nepotřebuje současné heslo (`POST /users/{id}/reset-password`, jen ADMIN); self-service (`POST /auth/change-password`) naopak současné heslo vyžaduje a je dostupná komukoli — smíchat by znamenalo buď obejít ověření identity, nebo nutit admina znát heslo uživatele |
| **Nelze deaktivovat vlastní účet** (`CANNOT_DEACTIVATE_SELF`) | banální, ale reálná chyba — admin, který si omylem zablokuje jediný přístup, nemá jak se dostat zpět bez zásahu do DB |
| **Nelze deaktivovat posledního `ROLE_ADMIN`** (`CANNOT_DEACTIVATE_LAST_ADMIN`) | ochrana proti úplnému zamčení aplikace bez administrátora; hlídá i případ, kdy „poslední admin" deaktivuje jiného admina, jehož session mezitím reálně pozbyla platnost (viz pruvodce §8) |
| **Uživatelské jméno je po založení needitovatelné** | `username` je identita účtu napříč JWT (`sub`), audit trailem a historickými záznamy; změna by je rozbila. Email needitovatelnost nemá — je to jen kontaktní údaj |
| **Role se přiřazují jako množina ID (`roleIds`), ne jako text** | `security.roles` je existující číselník (`GET /code-lists/roles`); validace platnosti role je tak jen FK constraint, ne string matching |
| **Žádné hard-delete, jen `enabled = FALSE`** | shoduje se s projektovou konvencí soft-delete a s tím, jak už tabulka `security.users` fungovala (`enabled` řídí i Spring Security login) |
| **DB schéma se neměnilo** | `security.users/roles/user_roles` existovaly od `V1__init_security_schema.sql`; chyběla jen aplikační CRUD vrstva nad nimi — žádná nová migrace |

## Chování při chybách

- Duplicitní uživatelské jméno/email při založení → **409** `USER_ALREADY_EXISTS`.
- Duplicitní email při úpravě (jiný uživatel ho už má) → **422** `DUPLICATE_EMAIL`.
- Pokus o deaktivaci vlastního účtu → **422** `CANNOT_DEACTIVATE_SELF`.
- Pokus o deaktivaci posledního aktivního administrátora → **422** `CANNOT_DEACTIVATE_LAST_ADMIN`.
- Špatné současné heslo při self-service změně → **422** `INVALID_CURRENT_PASSWORD`.
- Přístup na `/api/v1/users/**` bez role ADMIN → **403** `ACCESS_DENIED`.

## Mapa implementace

- **DB:** žádná nová migrace — využívá existující `security.users`, `security.roles`, `security.user_roles` (V1).
- **Backend:** `UserMapper(.xml)` (rozšířeno o CRUD a role), `model/dto/user/UserDto` + `UserSearchParams`, `model/converter/UserConverter`, `service/UserService(Impl)`, `controller/UserController`. Self-service heslo: `ChangePasswordRequest`, `AuthenticationService.changePassword`, `AuthController#changePassword`, rozšířený `MeResponse` (pole `roles`).
- **Frontend:** `pages/UsersPage(Create|Edit)`, `components/UserForm`, `UserTable`, `ResetPasswordModal`, `ChangePasswordModal`, `hooks/useUserRowActions`; `Sidebar` — položka „Uživatelé" (jen ADMIN) a odkaz „Změnit heslo" (všichni).
- **Dokumentace:** `docs/api.md` (sekce Uživatelé + `/auth/change-password`), `docs/konvence.md §19` (`@PreAuthorize`), `docs/backend.md` (service/MyBatis/Security), `docs/frontend.md` (routing, komponenty, auth flow), tento dokument, `docs/pruvodce/sprava-uzivatelu.md`, nápověda v aplikaci.

## Historie

- 2026-07-19: navrženo a implementováno na větvi `rje`; ověřeno end-to-end (curl přes všechny endpointy a guardy + reálný průchod v prohlížeči: přihlášení, seznam, založení, deaktivace, změna hesla). Necommitnuto — čeká na rozhodnutí uživatele.
