/**
 * Jak faktura brání akci nad zakázkou.
 *
 * <p>Sdílené dialogem zrušení i mazání — obě akce se na fakturu ptají a musí ji hodnotit
 * stejně. Kdyby si každý dialog stav faktury vykládal po svém, jeden by nabídl storno
 * konceptu tam, kde by ho druhý odmítl, a obsluha by dostala protichůdné informace.
 *
 * <p>Pozor na rozdíl mezi oběma akcemi: `"draft"` i `"issued"` popisují jen **aktivní**
 * fakturu, kterou zakázka právě nese. Pro **mazání** je to nutná, ale ne postačující
 * podmínka — server počítá i faktury historické (stornované a dobropisované), takže
 * jednou vyfakturovanou zakázku nesmaže nikdy. Frontend to uhádnout nemůže; hlásí to
 * až backend hláškou `ORDER_HAS_INVOICE_CANNOT_DELETE`.
 *
 * @param {object} order
 * @returns {"draft"|"issued"|null}
 */
export function invoiceBlock(order) {
    if (!order) return null;
    if (order.invoiceStatus === "DRAFT") return "draft";
    if (order.invoiceStatus === "ISSUED" || order.invoiceStatus === "PAID") return "issued";
    return null;
}
