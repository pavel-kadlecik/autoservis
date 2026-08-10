/**
 * Uzavřený číselník povolených měrných jednotek (Z-4).
 *
 * Zrcadlí serverovou konfiguraci `warehouse.import.allowed-units`
 * (application.yaml, bean WarehouseImportProperties) — při změně upravit obojí.
 * Validaci vynucuje server (confirm příjemky, create/update produktu);
 * tato konstanta jen omezuje nabídku ve formulářích, ať uživatel nezadá
 * variantu mimo číselník.
 */
export const ALLOWED_UNITS = ["ks", "l", "kg", "bal", "m", "sada", "pár", "hod"];

/**
 * Jednotky nabízené u položky typu **práce** (LABOR).
 *
 * Práce se účtuje buď po hodinách, nebo po kusech — paušálem za úkon, což si vyžádal
 * zákazník (2026-08-03). Zbytek číselníku (kg, litry, sada…) u práce smysl nedává,
 * proto se u ní nenabízí. Dřív byla jednotka u LABOR natvrdo zamčená na „hod".
 */
export const LABOR_UNITS = ["ks", "hod"];
