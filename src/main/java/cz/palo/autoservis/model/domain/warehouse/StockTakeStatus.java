package cz.palo.autoservis.model.domain.warehouse;

/** Stav inventury. Mapuje se na warehouse.stock_take_status (V44). */
public enum StockTakeStatus {
    OPEN,       // probíhá — soupis lze vyplňovat
    CLOSED,     // uzavřena, korekční pohyby vygenerovány
    CANCELLED   // zrušena bez efektu na sklad
}
