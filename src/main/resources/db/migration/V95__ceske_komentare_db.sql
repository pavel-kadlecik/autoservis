-- =============================================================================
-- V95__ceske_komentare_db.sql
--
-- České popisky databázových objektů (COMMENT ON) pro existující databáze.
-- Vygenerováno z přeložených migrací V1–V94 v pořadí verzí — pozdější popisek
-- přepisuje dřívější, stejně jako při původní aplikaci. Nemění data ani
-- strukturu; nové (prázdné) databáze dostanou tytéž texty už z V1–V94 a tato
-- migrace je jen znovu zapíše. Viz docs/preklad-komentaru.md (etapa E10).
-- =============================================================================

COMMENT ON TABLE security.roles IS 'Role pro Spring Security. Hodnota name se mapuje přímo na GrantedAuthority (ROLE_ADMIN atd.).';
COMMENT ON TABLE security.users IS 'Autentizační záznamy. Mapují se na Spring Security UserDetails.';
COMMENT ON COLUMN security.users.id IS 'BIGSERIAL — Java Long. Odkazují na něj všechna ostatní schémata.';
COMMENT ON COLUMN security.users.password_hash IS 'BCrypt hash. Nikdy prostý text.';
COMMENT ON TABLE security.user_roles IS 'Přiřazení rolí uživatelům.';
COMMENT ON COLUMN security.user_roles.assigned_by IS 'FK -> security.users.id — kdo roli přiřadil (auditní stopa).';
COMMENT ON TABLE security.token_blacklist IS 'JWT tokeny na blacklistu (odhlášené relace).';
COMMENT ON TABLE customer.customers IS 'Byznysový profil zákazníka. Oddělen od autentizačních dat (schéma security).';
COMMENT ON COLUMN customer.customers.id IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customer.customers.user_id IS 'NULL = zákazník bez portálového účtu. Mezischémový FK -> security.users.id.';
COMMENT ON COLUMN customer.customers.created_by IS 'Mezischémový FK -> security.users.id.';
COMMENT ON COLUMN customer.customers.ico IS 'IČO bez mezer, unikátní v celém systému.';
COMMENT ON TABLE customer.addresses IS 'Adresy zákazníků. Jeden zákazník může mít více adres různých typů.';
COMMENT ON COLUMN customer.addresses.id IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customer.addresses.customer_id IS 'FK Long -> customer.customers.id.';
COMMENT ON TABLE customer.contact_persons IS 'Kontaktní osoby — především pro firemní zákazníky.';
COMMENT ON COLUMN customer.contact_persons.id IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customer.contact_persons.customer_id IS 'FK Long -> customer.customers.id.';
COMMENT ON COLUMN customer.contact_persons.user_id IS 'Mezischémový FK -> security.users.id. Vyplněn, když má kontaktní osoba vlastní přístup do portálu.';
COMMENT ON TABLE customer.customer_communications IS 'Záznam komunikace se zákazníky.';
COMMENT ON COLUMN customer.customer_communications.id IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customer.customer_communications.handled_by IS 'Mezischémový FK -> security.users.id.';
COMMENT ON SEQUENCE customer.customer_number_seq IS 'Sekvence pro generování zákaznických čísel. Použití: SELECT nextval(''customer.customer_number_seq'')';
COMMENT ON TABLE warehouse.suppliers IS 'Registr dodavatelů náhradních dílů. Deduplikace podle IČO. Cíl vratek a reklamací.';
COMMENT ON COLUMN warehouse.suppliers.is_active IS 'Soft-delete - dodavatel se nikdy nemaže, pouze deaktivuje.';
COMMENT ON TABLE warehouse.products IS 'Skladová karta - typ dílu. SKU = katalogové číslo dodavatele.';
COMMENT ON COLUMN warehouse.products.quantity_on_hand IS 'Denormalizované aktuální množství. Skutečné množství = SUM(stock_movements). Udržuje trigger.';
COMMENT ON TABLE warehouse.goods_receipts IS 'Příjemka = hlavička jedné dodavatelské PDF faktury. Nese číslo faktury a číslo objednávky.';
COMMENT ON COLUMN warehouse.goods_receipts.supplier_name_snapshot IS 'Název dodavatele zmrazený v okamžiku fakturace (faktura je neměnný doklad).';
COMMENT ON COLUMN warehouse.goods_receipts.reconciliation_ok IS 'Sedí součet položek na celkovou částku? FALSE = nutná ruční kontrola.';
COMMENT ON COLUMN warehouse.goods_receipts.source_pdf IS 'Originální PDF pro daňovou archivaci.';
COMMENT ON TABLE warehouse.goods_receipt_items IS 'Řádky příjemky = šarže. Nositel dohledatelnosti a historie nákupních cen.';
COMMENT ON COLUMN warehouse.goods_receipt_items.quantity_remaining IS 'Zbývající množství šarže. Při RECEIPT inicializováno = quantity_received, dále upravováno triggerem pohybů.';
COMMENT ON TABLE warehouse.stock_movements IS 'Append-only deník pohybů. Zdroj pravdy o skladovém množství. Nikdy se needituje.';
COMMENT ON COLUMN warehouse.stock_movements.quantity IS 'Množství se znaménkem: + příjem, - výdej. Vázáno na movement_type přes CHECK.';
COMMENT ON COLUMN warehouse.products.manufacturer IS 'Výrobce / značka dílu (např. Bosch). Volný text.';
COMMENT ON COLUMN warehouse.products.variant IS 'Varianta / použití (např. „2.0 TDI 2013-2016"). Rozlišuje díly se stejným názvem.';
COMMENT ON COLUMN warehouse.products.note IS 'Poznámka volným textem (tipy k objednávání, alternativy).';
COMMENT ON COLUMN warehouse.products.sale_price IS 'Prodejní cena bez DPH. Nákupní cena zůstává odvozená z goods_receipt_items.';
COMMENT ON COLUMN warehouse.products.min_stock_level IS 'Volitelný práh pro doobjednání. NULL = nehlídá se; při vyplnění je položka docházející, jakmile quantity_on_hand < min_stock_level.';
COMMENT ON COLUMN warehouse.suppliers.registration_number IS 'Národní registrační / identifikační číslo firmy (CZ/SK IČO, případně zahraniční ekvivalent). Nepovinné, unikátní (NULL se neduplikuje).';
COMMENT ON COLUMN warehouse.suppliers.vat_id IS 'Daňové / DPH identifikační číslo z faktury (CZ DIČ, SK IČ DPH, případně zahraniční VAT ID). Nepovinné, bez formátové validace v DB.';
COMMENT ON COLUMN billing.invoices.customer_name_snapshot IS 'Zobrazované jméno zákazníka zmražené k datu vystavení — faktura je neměnný dokument a nesmí sledovat pozdější změny zákazníka.';
COMMENT ON COLUMN billing.invoices.order_number_snapshot IS 'Číslo zakázky zmražené k datu vystavení — drží se na faktuře, aby přežilo změny navázané zakázky.';
COMMENT ON TABLE billing.invoice_party IS 'Zmražený snapshot stran faktury (dodavatel / odběratel). Po vystavení neměnný.';
COMMENT ON COLUMN billing.invoice_party.role IS 'SUPPLIER = vystavitel (naše firma), CUSTOMER = odběratel (příjemce).';
COMMENT ON COLUMN billing.invoice_party.name IS 'Celé jméno / název firmy zmražené k datu vystavení.';
COMMENT ON COLUMN billing.invoice_party.ico IS 'Registrační číslo (IČO). NULL u fyzických osob.';
COMMENT ON COLUMN billing.invoice_party.dic IS 'DIČ (daňové identifikační číslo). NULL u neplátců / fyzických osob.';
COMMENT ON COLUMN billing.invoices.customer_name_snapshot IS 'Denormalizovaná kopie jména strany CUSTOMER pro levné vykreslení seznamu bez JOINu na invoice_party. Zapisuje se jednou při vystavení, neměnná. Zdrojem pravdy pro právní doklad je billing.invoice_party.';
COMMENT ON TABLE billing.company_profile IS 'Jednořádková tabulka s identitou vystavující firmy (dodavatele). Snapshotuje se na každou fakturu v okamžiku vystavení.';
COMMENT ON COLUMN billing.company_profile.bank_account IS 'Tuzemské číslo účtu, např. 123456789/0800.';
COMMENT ON COLUMN billing.company_profile.iban IS 'IBAN pro zahraniční platby. NULL, pokud se nepoužívá.';
COMMENT ON COLUMN billing.company_profile.swift IS 'BIC/SWIFT banky pro zahraniční platby. NULL, pokud se nepoužívá.';
COMMENT ON COLUMN billing.invoices.vehicle_license_plate_snapshot IS 'Zmražená SPZ fakturovaného vozidla k datu vystavení. NULL, pokud vozidlo SPZ nemělo. VIN / značka / model se čtou živě přes zakázku.';
COMMENT ON COLUMN warehouse.goods_receipts.invoice_number IS 'Číslo dokladu: číslo faktury u INVOICE, číslo dodacího listu u DELIVERY_NOTE.';
COMMENT ON COLUMN warehouse.goods_receipts.document_type IS 'Druh zdrojového dokladu, volí uživatel při nahrání (ne AI).';
COMMENT ON COLUMN warehouse.goods_receipts.source_channel IS 'Vstupní kanál, který koncept vytvořil (AI_PDF / MANUAL / ISDOC).';
COMMENT ON COLUMN warehouse.goods_receipts.draft_payload IS 'Kanonický koncept příjemky (řádky, stavy po polích, návrhy párování). Směrodatný ve stavu PENDING_REVIEW; po potvrzení/odmítnutí zmražený snapshot.';
COMMENT ON COLUMN warehouse.goods_receipts.rejection_note IS 'Proč kontrolor koncept odmítl. Vyplněno jen u REJECTED.';
COMMENT ON COLUMN warehouse.products.manufacturer_part_number IS 'Číslo dílu tak, jak ho tiskne výrobce (např. "871.180" u Elringu). Spolu s výrobcem tvoří identitu pro párování.';
COMMENT ON COLUMN warehouse.products.part_number_normalized IS 'Generovaný sloupec: manufacturer_part_number velkými písmeny bez mezer/teček/pomlček. Používá ho párovací kaskáda importu.';
COMMENT ON COLUMN warehouse.products.sku IS 'Hlavní katalogové číslo pro uživatele (SKU prvního dodavatele nebo ruční). Od V40 už NENÍ identitou pro párování - viz supplier_products.';
COMMENT ON TABLE warehouse.supplier_products IS 'Křížová reference položek dodavatele: (dodavatel, katalogové číslo dodavatele) -> skladová karta. Samoučící - potvrzené shody upsertuje review workflow.';
COMMENT ON TABLE warehouse.receipt_delivery_note_refs IS 'Dodací listy, na které se faktura odkazuje (skupinové řádky LKQ). matched_receipt_id + resolution řídí pojistku proti dvojímu naskladnění při potvrzení.';
COMMENT ON VIEW warehouse.v_stock_valuation IS 'Hodnota zásob per aktivní produkt: SUM(zbytek šarže * nákupní cena bez DPH), zaokrouhleno po šarži. Celkový součet si sčítá aplikace.';
COMMENT ON COLUMN warehouse.goods_receipts.cancelled_at IS 'Kdy byla potvrzená příjemka stornována (kompenzační pohyby).';
COMMENT ON COLUMN warehouse.goods_receipts.cancellation_note IS 'Důvod storna - povinný při stornu, jako u zamítnutí.';
COMMENT ON TABLE warehouse.stock_takes IS 'Inventura: soupis k datu, po uzavření generuje korekční pohyby. Jen jedna OPEN naráz.';
COMMENT ON COLUMN warehouse.stock_takes.surplus_receipt_id IS 'Pseudo-příjemka typu STOCK_TAKE založená při uzavření pro inventurní přebytky.';
COMMENT ON COLUMN warehouse.stock_take_items.expected_quantity IS 'Snapshot quantity_on_hand při otevření inventury - jen informativní. Rozdíl se při uzavření počítá proti AKTUÁLNÍMU stavu.';
COMMENT ON COLUMN warehouse.stock_take_items.counted_quantity IS 'Skutečně napočítané množství. NULL = nepočítáno (negeneruje korekci), není to nula.';
COMMENT ON COLUMN warehouse.stock_take_items.surplus_unit_price IS 'Nákupní cena pro případný přebytek. Předvyplněná z nejnovější šarže dílu, uživatel může přepsat.';
COMMENT ON COLUMN billing.invoices.vehicle_vin_snapshot IS 'Zmražený VIN fakturovaného vozidla k datu vystavení (audit K-5). NULL, pokud není znám.';
COMMENT ON COLUMN billing.invoices.vehicle_brand_snapshot IS 'Zmražená značka vozidla k datu vystavení (audit K-5).';
COMMENT ON COLUMN billing.invoices.vehicle_model_snapshot IS 'Zmražený model vozidla k datu vystavení (audit K-5).';
COMMENT ON COLUMN billing.invoices.paid_at IS 'Kdy byla faktura zaplacena (NULL = nezaplaceno). Audit K-9.';
COMMENT ON COLUMN billing.invoices.paid_amount IS 'Zaplacená částka (u plné úhrady = celková částka dokladu).';
COMMENT ON COLUMN billing.invoices.paid_method IS 'Skutečný způsob úhrady, odlišený od předepsaného payment_method.';
COMMENT ON COLUMN warehouse.stock_takes.stock_take_number IS 'Číslo dokladu inventury INV-{rok}-{4 číslice}, resetované per rok; generuje trigger trg_generate_stock_take_number.';
COMMENT ON COLUMN vehicle.vehicles.wheels IS 'Pneu/ráfky per náprava z registru (raw_response->>NapravyPneuRafky). Denormalizovaná cache plněná sync triggerem, aplikace ji nezapisuje. Jen k zobrazení.';
COMMENT ON COLUMN security.users.locked_at IS 'Kdy byl účet uzamčen po překročení počtu neúspěšných přihlášení (NULL = není zamčeno). Od této hodnoty se počítá expirace zámku (lockout.duration). Audit KN-5.';
COMMENT ON COLUMN warehouse.stock_take_items.closed_expected_quantity IS 'Stav skladu v okamžiku uzavření inventury, proti kterému byl spočítán rozdíl (NULL = inventura ještě není uzavřená). Audit KN-2.';
COMMENT ON COLUMN warehouse.stock_take_items.closed_difference IS 'Zjištěný rozdíl (napočítáno − stav) zmrazený při uzavření; záporný = manko, kladný = přebytek. NULL = neuzavřeno, nebo řádek nebyl počítán. Audit KN-2.';
COMMENT ON INDEX billing.uq_credit_notes_original_active IS 'Jeden aktivní opravný daňový doklad na fakturu (KN-8). Stornovaný neblokuje — po stornu lze vystavit nový.';
COMMENT ON VIEW billing.v_invoice_price_totals IS 'Dopočtené souhrny faktury (neukládají se). rounding/total_to_pay = zaokrouhlení hotovostní úhrady na celé Kč mimo základ daně (§36/5 ZDPH, V67/KN-7); u nehotovostní úhrady je rounding 0 a total_to_pay = total_gross.';
COMMENT ON COLUMN billing.invoices.credited_at IS 'Kdy byl k faktuře vystaven opravný daňový doklad (dobropis). NULL = nedobropisovaná. '
    'Dobropisovaná faktura přestává být aktivní fakturou zakázky (uq_invoices_order_active).';
COMMENT ON COLUMN "order".orders.mileage_km_at_intake IS 'Stav tachometru [km] při příjmu vozu — snímek pro zakázkový list (KN-28). '
    'NULL = nezadáno. Odometr vozidla vede vehicle.mileage_history, tohle je údaj dokladu.';
COMMENT ON COLUMN billing.company_profile.invoice_number_auto IS 'Zda se číslo faktury skládá podle masky a předvyplňuje v dialogu. Vypnuto = volný ruční zápis.';
COMMENT ON COLUMN billing.company_profile.invoice_number_mask IS 'Maska číselné řady faktur; tokeny {RRRR} {RR} {MM} a {N..N} (šířka sekvence), zbytek literály.';
COMMENT ON TABLE schedule.appointments IS 'Objednávky termínů (BOOKING) a blokace dílny (CLOSURE). Objednávka vzniká před zakázkou; '
    'po převodu ukazuje order_id na vzniklou zakázku a status je CONVERTED.';
COMMENT ON COLUMN schedule.appointments.ends_at IS 'Konec termínu. NULL = „zákazník nechá auto, konec neznámý" — délku opravy '
    'nelze před diagnostikou odhadnout. U blokace dílny (CLOSURE) je povinný.';
COMMENT ON TABLE schedule.appointments IS 'Objednávky termínů (BOOKING) a blokace dílny (CLOSURE). Objednávka vzniká před zakázkou; '
    'po převodu ukazuje order_id na vzniklou zakázku a status je CONVERTED. '
    'Bez soft-delete (V76): zrušení = status CANCELLED (zůstává), omyl = DELETE (mizí).';
COMMENT ON COLUMN schedule.appointments.vehicle_id IS 'Vozidlo, které přijede. U BOOKING povinné (V78), u CLOSURE musí být NULL.';
COMMENT ON TABLE schedule.opening_hours IS 'Týdenní otevírací doba dílny. Sedm řádků (1 = pondělí … 7 = neděle); '
    'opens_at i closes_at NULL znamená zavřeno celý den.';
COMMENT ON COLUMN schedule.schedule_settings.opening_hours_enabled IS 'Zapíná ohled na otevírací dobu. Dnes znamená „upozorňuj na termín mimo dobu" '
    '(uložit lze i tak — rozhodnutí uživatele 2026-08-04); zavřené dny se ztlumí v kalendáři.';
COMMENT ON COLUMN schedule.appointments.employee_id IS 'Jen pro EVENT (dovolená apod.). NULL = událost bez vazby na zaměstnance.';
COMMENT ON COLUMN warehouse.stock_movements.order_item_id IS 'Položka zakázky, které se pohyb týká. NULL u příjmů a ručních pohybů (ADJUSTMENT, '
    'WRITE_OFF, RETURN). Rozlišuje vydané položky od pouze rezervovaných — rezervace se '
    'z toho odvozuje, neukládá se. Záměrně BEZ cizího klíče: ledger je append-only (V52), '
    'takže ON DELETE by musel buď mazat/měnit pohyb, nebo zablokovat mazání položky. '
    'Id se v PostgreSQL nerecykluje, odkaz proto zůstává jednoznačný i po smazání položky.';
COMMENT ON COLUMN vehicle.mileage_history.order_id IS 'Zakázka, při jejímž příjmu odečet vznikl (V70/V84). NULL u ručně zadaných odečtů '
    'z karty vozidla. ON DELETE CASCADE — smazání zakázky odečet odstraní, protože '
    'u omylem založené zakázky (typicky na špatném voze) jde o nesmyslný údaj.';
COMMENT ON COLUMN schedule.appointments.customer_id IS 'Zákazník, který přijede. Volitelný (V85) — termín se domlouvá dřív, než servis '
    'zákazníka eviduje. U CLOSURE a EVENT musí být NULL.';
COMMENT ON COLUMN schedule.appointments.vehicle_id IS 'Vozidlo, které přijede. Volitelné (V85) — po telefonu se často neví, s čím '
    'zákazník dorazí. U CLOSURE a EVENT musí být NULL.';
COMMENT ON COLUMN schedule.appointments.contact_note IS 'Jméno a telefon zákazníka, který není v evidenci (volný text). Jen pro BOOKING; '
    'u zákazníka navázaného přes customer_id se nevyplňuje.';
COMMENT ON COLUMN vehicle.vehicles.fuel_type IS 'Druh paliva / pohonu. Volitelný (V86) — přívěsný vozík nemá motor, takže nemá '
    'ani palivo. NULL znamená „nevyplněno", hodnota OTHER naopak „jiné než uvedené".';
COMMENT ON COLUMN warehouse.stock_movements.order_id IS 'Zakázka, kvůli které pohyb vznikl. Bez FK (V87) — zakázku lze smazat a pohyb '
    'v append-only ledgeru zůstává. ID se nerecykluje, odkaz je proto jednoznačný '
    'i po smazání.';
COMMENT ON COLUMN billing.invoices.handed_over_at IS 'Kdy obsluha potvrdila, že doklad dostal zákazník. NULL = nepředáno, takže '
    'fakturu lze ještě smazat. Vystavení tenhle příznak NENASTAVUJE (V88).';
COMMENT ON COLUMN billing.invoices.handed_over_by IS 'Kdo předání potvrdil; SET NULL po smazání uživatele (audit přežije účet).';
COMMENT ON COLUMN billing.company_profile.invoice_gap_check_enabled IS 'Hlídat mezery v číselné řadě faktur a varovat nad seznamem (V89).';
COMMENT ON COLUMN billing.company_profile.invoice_gap_check_from IS 'Číslo faktury, od kterého se hlídá; starší se ignorují (typicky data přenesená '
    'z jiného systému). NULL = hlídat celé aktuální období od pořadí 1.';
COMMENT ON COLUMN vehicle.vehicles.vin IS 'Vehicle Identification Number. Volitelný (V90) — technika bez VIN (zahradní '
    'traktor, sekačka) má NULL. Vyplněný VIN musí splňovat formát (17 znaků) '
    'a unikátnost; bez VIN nefunguje načtení z registru vozidel.';
COMMENT ON COLUMN vehicle.vehicles.machine_serial_number IS 'Výrobní/sériové číslo stroje bez VIN (V90). Volný text — formáty výrobců '
    'se liší, unikátnost se nevynucuje (různí výrobci mohou čísla sdílet).';
COMMENT ON COLUMN billing.invoices.purchase_order_number IS 'Číslo objednávky zákazníka — nákupní objednávka / PO (V91). Volný text, '
    'zadává se ručně při vytvoření faktury; NULL = neuvedeno. Tiskne se na '
    'fakturu, aby ji odběratel spároval ve svém systému.';
COMMENT ON COLUMN billing.company_profile.cash_receipt_number_mask IS 'Maska číselné řady pokladních dokladů; tokeny {RRRR} {RR} {MM} a {N..N} (šířka sekvence), zbytek literály.';
COMMENT ON COLUMN billing.company_profile.cash_receipt_gap_check_enabled IS 'Hlídat mezery v číselné řadě pokladních dokladů a varovat v dialogu vystavení (V92).';
COMMENT ON COLUMN billing.company_profile.cash_receipt_gap_check_from IS 'Číslo dokladu, od kterého se hlídá; starší se ignorují (typicky data přenesená '
    'z jiného systému). NULL = hlídat celé aktuální období od pořadí 1.';
COMMENT ON COLUMN billing.company_profile.cash_receipt_number_source IS 'Zdroj čísla PPD v dialogu vystavení: MASK = návrh dle masky, INVOICE = číslo hrazené '
    'faktury, MANUAL = prázdné pole. Zapsat lze v každém režimu libovolné unikátní číslo.';
COMMENT ON COLUMN "order".orders.received_at IS 'Datum přijetí vozidla do servisu (zadává uživatel, tiskne se na zakázkovém listu)';
