import PaginationRounded from "../components/PaginatorRounded.jsx";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import {ORDER_STATUS_OPTIONS, getOrderStatusLabel, getOrderStatusTone} from "../api/format.js";
import * as React from "react";
import OrderTable from "../components/OrderTable.jsx";
import OrderFilterModal from "../components/OrderFilterModal.jsx";
import {useAlert} from "../context/AlertContext.jsx";
import {useEffect, useState} from "react";
import {useNavigate, useSearchParams} from "react-router-dom";
import {api, problemMessage} from "../api/api.js";
import LoadErrorState from "../components/LoadErrorState.jsx";
import { useOrderActions } from "../components/orderActions.jsx";

/** Klíč, pod kterým si prohlížeč pamatuje poslední nastavení filtru zakázek. */
const ORDER_FILTER_KEY = "autoservis.orders.filter";

/**
 * Výchozí filtr pro uživatele, který si zatím nic nenastavil: všechny stavy kromě
 * zrušených. Zrušená zakázka je obchodní fakt (zákazník odmítl rozpočet) a v evidenci
 * zůstává, ale v denním seznamu jen překáží. Že se filtruje, je vidět na odznaku
 * nad tabulkou — není to skryté chování.
 */
function defaultFilter() {
    return {
        statuses: ORDER_STATUS_OPTIONS.map(o => o.value).filter(v => v !== "CANCELLED"),
        overdue: false,
    };
}

/** Poslední uložené nastavení, nebo výchozí. Poškozený obsah úložiště filtr neshodí. */
function loadSavedFilter() {
    try {
        const raw = localStorage.getItem(ORDER_FILTER_KEY);
        if (!raw) return defaultFilter();
        const saved = JSON.parse(raw);
        return {
            statuses: Array.isArray(saved.statuses) ? saved.statuses : defaultFilter().statuses,
            overdue: Boolean(saved.overdue),
        };
    } catch {
        return defaultFilter();
    }
}

export default function OrdersPage() {

    // Zúžení na jedno vozidlo nebo zákazníka přichází z URL (KN-27) — sem vedou odkazy
    // „Zobrazit všechny" z karet servisní historie, takže je to deep-link, ne jen stav filtru.
    const [searchParams, setSearchParams] = useSearchParams();
    const vehicleId = searchParams.get("vehicleId");
    const customerId = searchParams.get("customerId");
    const [scope, setScope] = useState(null);

    const [search, setSearch] = useState("");
    // Filtr stavů je pole — „ukaž mi rozpracované" znamená víc stavů zároveň, což
    // rozbalovací seznam neuměl. Výchozí nastavení skrývá zrušené: obchodní fakt, že
    // zákazník odmítl rozpočet, má zůstat v evidenci, ale v denním seznamu překáží.
    const [statuses, setStatuses] = useState(() => loadSavedFilter().statuses);
    const [overdue, setOverdue] = useState(() => loadSavedFilter().overdue);
    const [showFilter, setShowFilter] = useState(false);
    const [orders, setOrders] = useState([]);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(10);
    const [sort, setSort] = useState({by: 'createdAt', desc: true});
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse] = useState({totalElements: 0});
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14) — prázdno uživatel
    // čte jako „nic tu není" a podle toho jedná.
    const [loadError, setLoadError] = useState("");
    const [reloadKey, setReloadKey] = useState(0);

    const navigate = useNavigate();
    const {addAlert} = useAlert();

    // Kolik filtrů je zapnutých — číslo na tlačítku říká, že seznam není úplný, i když
    // odznaky odroluješ z dohledu.
    const activeFilterCount = statuses.length + (overdue ? 1 : 0);

    useEffect(() => {
        const params = new URLSearchParams({
            page,
            pageSize: size,
            sortBy: sort.by,
            sortDesc: sort.desc,
        });

        if (search) params.append('search', search);
        statuses.forEach(st => params.append('statuses', st));
        if (overdue) params.append('overdue', 'true');
        if (vehicleId) params.append('vehicleId', vehicleId);
        if (customerId) params.append('customerId', customerId);

        async function loadOrders() {
            try {
                const data = await api.get(`/orders?${params.toString()}`);
                setOrders(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setOrders([]);
                setLoadError(problemMessage(err, "Zakázky se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadOrders, 400);
        return () => clearTimeout(timer);
    }, [search, statuses, overdue, page, size, sort, vehicleId, customerId, reloadKey]);

    // Poslední nastavení filtru přežije zavření záložky i restart — obsluha si ho nastaví
    // jednou a nemusí ho po každém přihlášení klikat znovu.
    useEffect(() => {
        try {
            localStorage.setItem(ORDER_FILTER_KEY, JSON.stringify({statuses, overdue}));
        } catch {
            // Soukromý režim nebo plné úložiště — filtr prostě nepřežije relaci.
        }
    }, [statuses, overdue]);

    // Popisek zúžení — bez něj by seznam mlčky ukazoval výsek a uživatel by nevěděl proč.
    // Selhání načtení popisku filtr neruší, jen se pojmenuje obecně.
    useEffect(() => {
        async function loadScopeLabel() {
            if (vehicleId) {
                try {
                    const v = await api.get(`/vehicles/${vehicleId}`);
                    setScope({kind: 'vozidlo', label: `${v.brand} ${v.model} (${v.licensePlate ?? v.vin ?? v.machineSerialNumber ?? 'bez identifikace'})`});
                } catch {
                    setScope({kind: 'vozidlo', label: `#${vehicleId}`});
                }
                return;
            }
            if (customerId) {
                try {
                    const c = await api.get(`/customers/${customerId}`);
                    setScope({kind: 'zákazník', label: c.displayName});
                } catch {
                    setScope({kind: 'zákazník', label: `#${customerId}`});
                }
                return;
            }
            setScope(null);
        }

        loadScopeLabel();
    }, [vehicleId, customerId]);

    function clearScope() {
        setSearchParams({});
        setPage(1);
    }

    function handleSortChange(by, desc) {
        setSort({by, desc});
        setPage(1);
    }

    /**
     * Rychlá změna stavu přímo ze seznamu.
     *
     * Seznam se jen přenačte — filtr, stránka ani řazení se neztratí, na rozdíl od cesty
     * přes editační formulář, která uživatele odnavigovala pryč.
     *
     * Co neprojde (zrušená zakázka, znovuotevření s fakturou, chybějící díl na skladě),
     * odmítne backend a jeho hláška se zobrazí — frontend tyhle podmínky uhádnout nemůže,
     * závisí na stavu databáze.
     */
    // Akce nad zakázkou drží sdílený hook — seznam, detail i editace nabízejí tutéž sadu
    // a nesmí se rozejít (viz orderActions.jsx).
    const { run: runOrderAction, dialogs: orderDialogs } = useOrderActions({
        onChanged: () => setReloadKey(prev => prev + 1),
        onDeleted: () => setReloadKey(prev => prev + 1),
    });

    function handlePageChange(event, value) {
        setPage(value);
    }

    function handlePageSizeChange(event) {
        setSize(Number(event.target.value));
        setPage(1);
    }

    return (
        <div>
            <PageHeader
                title="Zakázky"
                actions={
                    <button className="btn btn-primary" onClick={() => navigate('/orders/new')}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nová zakázka
                    </button>
                }
            />

            {scope && (
                <div className="d-flex align-items-center flex-wrap gap-2 mb-3">
                    <span className="text-muted small">
                        Zobrazeny jen zakázky pro {scope.kind} <strong className="text-body">{scope.label}</strong>.
                    </span>
                    <button type="button" className="btn btn-sm btn-outline-secondary" onClick={clearScope}>
                        <i className="bi bi-x-lg me-1" aria-hidden="true"></i>Zrušit zúžení
                    </button>
                </div>
            )}

            <ListToolbar >
                <div className="d-flex align-items-center gap-2 ">
                    <SearchFilter
                        id="orderSearch"
                        label="Hledat zakázku"
                        placeholder="Číslo zakázky, zákazník nebo vozidlo"
                        value={search}
                        onChange={(value) => { setSearch(value); setPage(1); }}
                        hint="Hledá podle SPZ, VIN, čísla zakázky, jména a telefonu zákazníka, názvu firmy, značky a modelu vozidla i popisu zakázky."
                        className="col-12 col-xl-6"
                    />

                    {/* Filtr je za tlačítkem, ne rozbalený v liště: zaškrtávátek je osm a nad
                    tabulkou by zabrala víc místa než samotná data. Co je zapnuté, říkají
                    odznaky pod lištou, takže filtrování nikdy není skryté. */}
                    <div className="col-auto h-100">
                        <button type="button" className="btn btn-outline-secondary btn-sm"
                                onClick={() => setShowFilter(true)}>
                            <i className="bi bi-funnel me-1" aria-hidden="true"></i>
                            Filtr{activeFilterCount > 0 && <> ({activeFilterCount})</>}
                        </button>
                    </div>
                </div>
            </ListToolbar>

            {/* Stav filtru nad tabulkou — obsluha musí vidět, proč seznam neukazuje všechno. */}
            {(statuses.length > 0 || overdue) && (
                <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
                    <span className="text-muted small">Filtr:</span>
                    {overdue && (
                        <span className="badge bg-warning-subtle text-warning-emphasis">
                            Po termínu
                            <button type="button" className="btn-close btn-close-sm ms-2"
                                    aria-label="Zrušit filtr po termínu"
                                    onClick={() => {
                                        setOverdue(false);
                                        setPage(1);
                                    }}></button>
                        </span>
                    )}
                    {statuses.map(st => (
                        <span key={st}
                              className={`badge bg-${getOrderStatusTone(st)}-subtle text-${getOrderStatusTone(st)}-emphasis`}>
                            {getOrderStatusLabel(st)}
                            <button type="button" className="btn-close btn-close-sm ms-2"
                                    aria-label={`Odebrat stav ${getOrderStatusLabel(st)}`}
                                    onClick={() => {
                                        setStatuses(prev => prev.filter(v => v !== st));
                                        setPage(1);
                                    }}></button>
                        </span>
                    ))}
                    <button type="button" className="btn btn-link btn-sm p-0"
                            onClick={() => {
                                setStatuses([]);
                                setOverdue(false);
                                setPage(1);
                            }}>
                        Zrušit filtr
                    </button>
                </div>
            )}

            <OrderFilterModal
                show={showFilter}
                statuses={statuses}
                overdue={overdue}
                onClose={() => setShowFilter(false)}
                onApply={(nextStatuses, nextOverdue) => {
                    setStatuses(nextStatuses);
                    setOverdue(nextOverdue);
                    setPage(1);
                    setShowFilter(false);
                }}
            />

            {orderDialogs}

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setReloadKey(prev => prev + 1)}/>
            ) : (
                <>
                    <OrderTable
                        orders={orders}
                        sort={sort}
                        onSortChange={handleSortChange}
                        onAction={runOrderAction}
                        filtered={Boolean(search) || statuses.length > 0 || overdue || Boolean(scope)}
                    />

                    <PaginationRounded
                        itemCount={response.totalElements}
                        totalPages={totalPages}
                        pageSize={size}
                        page={page}
                        handlePageCount={handlePageSizeChange}
                        handleChange={handlePageChange}
                    />
                </>
            )}
        </div>
    );
}
