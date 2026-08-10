import * as React from "react";

/**
 * Pás dlaždic {@link MetricCard} pod hlavičkou detailu (U4.1).
 *
 * Existuje kvůli jedné věci: aby odsazení pásu od obsahu pod ním bylo na všech
 * detailech stejné a patřilo komponentě, ne stránce.
 */
export default function MetricRow({ children }) {
    return <div className="row g-2 mb-4">{children}</div>;
}
