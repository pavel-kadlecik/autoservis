package cz.palo.autoservis.model.enums;

/**
 * Stav pokladního dokladu (PPD) — mapuje se na PostgreSQL ENUM {@code billing.cash_receipt_status} (V68).
 *
 * <p>Pokladní doklad nemá životní cyklus faktury: číslo řady dostane hned při INSERT, koncept
 * ani stav „zaplaceno" nezná (potvrzuje příjem hotovosti, který už nastal). Jediný přechod je
 * <strong>storno</strong> — doklad vystavený omylem. Účetní doklad se nemaže (§35 ZoÚ), zůstává
 * v řadě a jen přestane platit; teprve pak lze k faktuře vystavit nový
 * ({@code uq_cash_receipts_invoice_active} je částečný unikát, V68).
 */
public enum CashReceiptStatus {
    ISSUED,
    CANCELLED
}
