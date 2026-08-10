import * as React from "react";
import {useState, useEffect, useRef, useCallback, useId} from "react";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Vrací debouncovanou kopii zadané hodnoty.
 * Vrácená hodnota se aktualizuje až po uplynutí zadané prodlevy
 * bez další změny. Každá nová změna časovač resetuje.
 *
 * @param {*} value - hodnota k debouncování
 * @param {number} delay - prodleva v milisekundách
 * @returns {*} debouncovaná hodnota
 */
function useDebounce(value, delay) {
    const [debounced, setDebounced] = useState(value);

    useEffect(() => {
        const timer = setTimeout(() => setDebounced(value), delay);
        return () => clearTimeout(timer);
    }, [value, delay]);

    return debounced;
}

/**
 * Autocomplete input, který páruje viditelné textové pole se skrytým polem s ID.
 *
 * Životní cyklus výběru:
 *   1. Uživatel píše  →  debounce  →  fetch  →  otevře se dropdown
 *   2. Uživatel vybere položku  →  viditelný input = item.value, skrytý input = item.id
 *   3. Uživatel upraví text  →  skrytý input se hned vyprázdní, spustí se nový fetch
 *
 * Očekávaná odpověď REST endpointu:
 *   GET {endpoint}?q={query}&limit={limit}
 *   → { data: [{ id, value, description }], hasMore: boolean }
 *
 * @param {string}   endpoint           - URL REST endpointu (bez query parametrů)
 * @param {string}   name               - atribut name skrytého inputu (klíč formuláře)
 * @param {string}   label              - text popisku viditelného inputu
 * @param {number}   [minChars=1]       - minimum znaků před fetchem; 0 = fetch při prvním fokusu
 * @param {number}   [limit=10]         - maximum položek požadovaných ze serveru
 * @param {number}   [debounceMs=400]   - prodleva debounce v milisekundách
 * @param {string}   [placeholder=""]  - text placeholderu
 * @param {Function} [onSelect]         - callback(item | null) volaný při výběru či zrušení výběru
 * @param {Function} [appendParams]     - volitelný callback vracející další query parametry
 *                                        zužující hledání: () => [{ key, value }, ...]. Volá se
 *                                        čerstvě při každém fetchi, takže vždy odráží aktuální
 *                                        stav rodiče (např. současnou hodnotu sousedního selectu).
 * @param {string}   [initialValue=""] - předvyplněný viditelný text (pro editační režim)
 * @param {string}   [initialSelectedId=""] - předvybrané ID (pro editační režim)
 * @param {boolean}  [required=false]   - přidá k popisku značku povinného pole (U5.2).
 *                                        Hvězdička se dřív psala do textu popisku
 *                                        ("Zákazník *"), takže ji čtečka četla jako
 *                                        součást názvu pole.
 */
export function AutocompletePair({
                                     endpoint,
                                     name,
                                     label,
                                     minChars = 1,
                                     limit = 10,
                                     debounceMs = 400,
                                     placeholder = "",
                                     onSelect,
                                     appendParams,
                                     initialValue = "",
                                     initialSelectedId = "",
                                     required = false,
                                 }) {
    // useId dává stabilní unikátní ID pro propojení label ↔ input (htmlFor / id).
    // Funguje správně i s více instancemi na jedné stránce a se SSR.
    const inputId = useId();

    const [inputValue, setInputValue] = useState(initialValue);
    const [selectedId, setSelectedId] = useState(initialSelectedId);
    const [items, setItems] = useState([]);
    const [hasMore, setHasMore] = useState(false);
    const [isOpen, setIsOpen] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState(null);
    const [focusedIndex, setFocusedIndex] = useState(-1);
    const [isActive, setIsActive] = useState(false);

    const abortRef = useRef(null);
    const rootRef = useRef(null);

    const debouncedQuery = useDebounce(inputValue, debounceMs);

    // Načtení výsledků při změně debouncovaného dotazu.
    useEffect(() => {
        if (!isActive) return;
        if (selectedId) return;

        if (debouncedQuery.length < minChars) {
            setItems([]);
            setHasMore(false);
            setIsOpen(false);
            return;
        }

        // Zrušit případný rozběhnutý request, aby nevznikaly race conditions.
        abortRef.current?.abort();
        abortRef.current = new AbortController();

        const url = new URL(endpoint, window.location.origin);
        url.searchParams.set("q", debouncedQuery);
        url.searchParams.set("limit", String(limit));
        // appendParams je funkce (ne pole) — voláme ji čerstvě při každém fetchi,
        // ať vždy odráží aktuální stav rodiče (viz JSDoc výše).
        (appendParams?.() ?? []).forEach(param => url.searchParams.set(param.key, param.value));

        setIsLoading(true);
        setError(null);

        fetch(url, {signal: abortRef.current.signal})
            .then((res) => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return res.json();
            })
            .then(({data, hasMore}) => {
                setItems(data ?? []);
                setHasMore(Boolean(hasMore));
                setIsOpen(true);
                setFocusedIndex(-1);
            })
            .catch((err) => {
                if (err.name === "AbortError") return;
                setError(err.message);
                setItems([]);
                setIsOpen(true);
            })
            .finally(() => setIsLoading(false));

        return () => abortRef.current?.abort();
    }, [debouncedQuery, selectedId, isActive, endpoint, limit, minChars, appendParams]);

    // Zavření dropdownu, když uživatel klikne mimo komponentu.
    useEffect(() => {
        const onMouseDown = (e) => {
            if (rootRef.current && !rootRef.current.contains(e.target)) {
                setIsOpen(false);
                setFocusedIndex(-1);
            }
        };
        document.addEventListener("mousedown", onMouseDown);
        return () => document.removeEventListener("mousedown", onMouseDown);
    }, []);

    // Aktivace komponenty při prvním fokusu od uživatele.
    // Při minChars=0 tím spustí i úvodní fetch.
    const handleFocus = useCallback(() => {
        if (!isActive) setIsActive(true);
    }, [isActive]);

    // Vymazání vybraného ID hned, jakmile uživatel změní text v poli.
    const handleChange = useCallback(
        (e) => {
            setInputValue(e.target.value);
            if (selectedId) {
                setSelectedId("");
                onSelect?.(null);
            }
        },
        [selectedId, onSelect]
    );

    // Potvrzení výběru položky (kliknutí myší nebo Enter z klávesnice).
    const handleSelect = useCallback(
        (item) => {
            setInputValue(item.value);
            setSelectedId(String(item.id));
            setIsOpen(false);
            setFocusedIndex(-1);
            onSelect?.(item);
        },
        [onSelect]
    );

    // Navigace klávesnicí uvnitř dropdownu.
    const handleKeyDown = useCallback(
        (e) => {
            if (!isOpen) return;
            switch (e.key) {
                case "ArrowDown":
                    e.preventDefault();
                    setFocusedIndex((i) => Math.min(i + 1, items.length - 1));
                    break;
                case "ArrowUp":
                    e.preventDefault();
                    setFocusedIndex((i) => Math.max(i - 1, -1));
                    break;
                case "Enter":
                    e.preventDefault();
                    if (focusedIndex >= 0 && items[focusedIndex]) {
                        handleSelect(items[focusedIndex]);
                    }
                    break;
                case "Escape":
                    setIsOpen(false);
                    setFocusedIndex(-1);
                    break;
                default:
                    break;
            }
        },
        [isOpen, items, focusedIndex, handleSelect]
    );

    return (
        <div ref={rootRef} className="position-relative">

            {/* Skrytý input nese ID vybraného záznamu při odeslání formuláře. */}
            <input type="hidden" name={name} value={selectedId}/>

            <label htmlFor={inputId} className="form-label">
                {label}{required && <> <RequiredMark /></>}
            </label>

            <div className="position-relative">
                <input
                    id={inputId}
                    type="text"
                    value={inputValue}
                    placeholder={placeholder}
                    onChange={handleChange}
                    onFocus={handleFocus}
                    onKeyDown={handleKeyDown}
                    autoComplete="off"
                    spellCheck={false}
                    role="combobox"
                    // hodnotu drží skrytý input, takže tohle pole nemůže mít `required`
                    // (prohlížeč by ho validoval podle napsaného textu, ne podle výběru).
                    // Povinnost proto hlásíme čtečce přes aria-required.
                    aria-required={required || undefined}
                    aria-expanded={isOpen}
                    aria-autocomplete="list"
                    aria-haspopup="listbox"
                    aria-busy={isLoading}
                    className={[
                        "form-control",
                        isLoading ? "pe-4" : "",
                        selectedId ? "border-success bg-success-subtle" : "",
                    ]
                        .filter(Boolean)
                        .join(" ")}
                />

                {isLoading && (
                    <div className="position-absolute top-50 end-0 translate-middle-y pe-2">
                        <div className="spinner-border spinner-border-sm text-secondary" role="status">
                            <span className="visually-hidden">Načítání…</span>
                        </div>
                    </div>
                )}
            </div>

            {isOpen && (
                <ul
                    role="listbox"
                    className="list-group list-group-flush position-absolute w-100 mt-1 rounded border shadow-sm z-3 overflow-auto"
                    style={{maxHeight: "280px"}}
                >
                    {error && (
                        <li className="list-group-item text-danger small py-2">
                            Chyba při načítání: {error}
                        </li>
                    )}

                    {!error && items.length === 0 && (
                        <li className="list-group-item text-muted small py-2">
                            Žádné výsledky
                        </li>
                    )}

                    {!error && items.map((item, i) => (
                        <li
                            key={item.id}
                            role="option"
                            aria-selected={i === focusedIndex}
                            // onMouseDown místo onClick: preventDefault zabrání tomu, aby blur
                            // na inputu proběhl dřív, než se výběr potvrdí — jinak by se
                            // dropdown zavřel dřív, než by onClick stihl vystřelit.
                            onMouseDown={(e) => {
                                e.preventDefault();
                                handleSelect(item);
                            }}
                            onMouseEnter={() => setFocusedIndex(i)}
                            className={[
                                "list-group-item",
                                "list-group-item-action",
                                "py-2",
                                i === focusedIndex ? "active" : "",
                            ]
                                .filter(Boolean)
                                .join(" ")}
                            style={{cursor: "pointer"}}
                        >
                            <div className="fw-medium small">{item.value}</div>
                            <div className={`small ${i === focusedIndex ? "text-white-50" : "text-muted"}`}>
                                {item.description}
                            </div>
                            {/* Třetí řádek plní jen některé našeptávače (vozidla → VIN). */}
                            {item.detail && (
                                <div className={`small ${i === focusedIndex ? "text-white-50" : "text-muted"}`}>
                                    {item.detail}
                                </div>
                            )}
                        </li>
                    ))}

                    {!error && hasMore && (
                        <li
                            className="list-group-item text-center text-muted small bg-light py-2"
                            style={{cursor: "default"}}
                        >
                            Zobrazeno {limit} z více výsledků — upřesněte hledání
                        </li>
                    )}
                </ul>
            )}
        </div>
    );
}

export default AutocompletePair;
