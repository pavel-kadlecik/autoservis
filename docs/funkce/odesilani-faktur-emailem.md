# Odesílání faktur e-mailem

*Funkční dokument (co + proč). Zavedeno 2026-08-08 na požadavek uživatele: fakturu má jít poslat zákazníkovi e-mailem přímo z aplikace, s PDF dokladu v příloze.*

## Co funkce dělá

1. **Při předání faktury** (akce „Předat zákazníkovi" u vystavené nepředané faktury) se otevře dialog s návrhem e-mailu. Obsluha volí činem: **„Předat a poslat e-mail"** fakturu odešle a orazítkuje předání, **„Předat bez e-mailu"** jen orazítkuje předání (dosavadní chování).
2. **Opakované odeslání** — u předané faktury (vystavené i zaplacené) je akce **„Poslat e-mailem"**: zákazník doklad ztratil, nebo první odeslání šlo na špatnou adresu. Předání už nemění.
3. **Dialog nese předpřipravenou kostru e-mailu**, kterou obsluha může libovolně doplnit nebo přepsat — odešle se přesně potvrzené znění, server nic nedoplňuje:
   - **Komu** — e-mail z karty zákazníka (`primary_email`); není-li vyplněný, zadá ho obsluha ručně (na kartu zákazníka se nezapisuje),
   - **Předmět** — `Faktura {číslo} — {jméno zákazníka ze snapshotu dokladu}` (rozhodnutí uživatele 2026-08-08: příjemce si fakturu pozná podle sebe; kdo ji poslal, říká odesílatel a podpis),
   - **Text** — pozdrav, číslo dokladu, částka k úhradě a splatnost, podpis názvem firmy z **Fakturačních údajů** (`company_profile`) — **ne** ze snapshotu stran dokladu: e-mail se píše teď a nese aktuální název, snapshot může držet stav z doby vytvoření faktury (třeba ještě nevyplněný profil). PDF v příloze snapshot pochopitelně drží dál (rozhodnutí uživatele 2026-08-08).
4. Přílohou je **PDF faktury** (`faktura-{číslo}.pdf`; znaky nepovolené v názvu souboru — typicky lomítko z masky `{NNN}/{RR}` — se nahrazují pomlčkou: `faktura-006-26.pdf`, oprava 2026-08-09) z téhož generátoru jako tisk (`InvoiceDocumentService.renderPdf`) — e-mailem odchází identický doklad.

## Klíčová rozhodnutí

**Úspěšné odeslání razítkuje předání** (`handed_over_at`, V88). V88 zavedla předání jako tvrzení člověka, který ví, že zákazník doklad dostal — protože aplikace fakturu neposílala. Teď ji posílá, takže o odeslání ví jistě: e-mail ve schránce = doklad u zákazníka. **Selhané odeslání předání nenastaví** — „předáno" k dokladu, který nikam nedošel, by byla lež v datech. Opakované odeslání už předané faktury předání nemění.

**Koncept se neposílá** (422 `INVOICE_NOT_ISSUED`) — nemá číslo a není to doklad; stejná logika jako u ručního předání.

**Evidence odeslaných e-mailů se v aplikaci nevede — evidencí je složka Odeslané** (rozhodnutí uživatele 2026-08-08). První testovací odeslání ale ukázalo, že **Seznam poštu odeslanou přes SMTP do Odeslaných sám neukládá** — kopii si tam ukládá poštovní klient. Tuhle roli proto přebírá aplikace: po úspěšném odeslání uloží kopii zprávy vč. PDF přílohy přes **IMAP** (`imap.seznam.cz:993` SSL, stejné přihlášení) do složky Odeslaných — Seznam ji přes IMAP jmenuje `sent` (ověřeno výpisem LIST), hledá se podle obvyklých jmen. Uložení kopie je **best-effort**: e-mail už odešel a podruhé ho poslat nejde, takže výpadek IMAP akci neshodí — jen WARN do logu. V aplikaci zůstává příznak předání.

**SMTP přihlášení je secret v konfiguraci, ne v DB.** Heslo k e-mailu je stejná kategorie jako `DB_PASSWORD` — patří do `application-local.yaml` / env (`MAIL_USERNAME`, `MAIL_PASSWORD`), ne do `company_profile`, kde by leželo v čitelné podobě. Server: `smtp.seznam.cz:465` (SSL), přihlášení celou adresou. Jako heslo se používá **„aplikační heslo"** (Zabezpečení Seznam účtu → Dvoufázové ověření → Aplikační heslo; vyžaduje zapnuté dvoufázové ověření a platí jen pro IMAP/POP3/SMTP/CalDAV — rozhodnutí uživatele 2026-08-08): přežije změnu přihlašovacího hesla schránky a jde samostatně zneplatnit, takže výměna hesla majitelem schránky aplikaci nerozbije. Pozor: se zapnutým dvoufázovým ověřením přestává běžné heslo pro poštovní protokoly fungovat. Prázdný default nechá aplikaci (i testy) nastartovat bez konfigurace — odeslání pak vrátí srozumitelné 422 `EMAIL_NOT_CONFIGURED`.

**Oprávnění jako u předání** — ADMIN/MANAGER (E7/R-6: doklad odesílá vedení, mechanik ne).

**Dva dialogové režimy místo checkboxu.** V režimu předání jsou dvě tlačítka („Předat bez e-mailu" / „Předat a poslat e-mail") — obsluha volí činem a nemůže odeslat omylem předvoleným zaškrtnutím.

## Implementace

| Vrstva | Kde |
|---|---|
| Endpointy | `GET /invoices/{id}/email-draft`, `POST /invoices/{id}/send-email` (`InvoiceController`) — viz [api.md](../api.md) |
| Service | `InvoiceEmailService` / `InvoiceEmailServiceImpl` — návrh kostry + odeslání (`JavaMailSender`, `spring-boot-starter-mail`) |
| DTO | `InvoiceEmailDto.DraftResponse` / `SendRequest` |
| Konfigurace | `spring.mail.*` v `application.yaml` (host/port natvrdo Seznam, login z env s prázdným defaultem) |
| FE | `InvoiceSendEmailModal.jsx` (dva režimy); napojení v `invoiceActions.jsx` a `InvoicesPageDetail.jsx` |
| Testy | `InvoiceEmailServiceTest` — unit s mockovaným SMTP (vazba e-mail → předání, guardy, kostra) |

Žádná DB migrace — funkce nemění schéma.
