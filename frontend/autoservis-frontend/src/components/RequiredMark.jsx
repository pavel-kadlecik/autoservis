import * as React from "react";

/**
 * Značka povinného pole. Hvězdička je `aria-hidden` — čtečce povinnost sdělí
 * atribut `required` na samotném poli, jinak by ji ohlásila dvakrát.
 */
export default function RequiredMark() {
    return <span className="text-danger" aria-hidden="true">*</span>;
}
