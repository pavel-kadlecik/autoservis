const API_BASE = '/api/v1';

export class ApiError extends Error {
    /** @param {number} status @param {object|null} problem RFC 9457 tělo @param {string} rawText */
    constructor(status, problem, rawText) {
        super(rawText || `HTTP ${status}`);   // message = raw text kvůli zpětné kompatibilitě
        this.name = 'ApiError';
        this.status = status;
        this.problem = problem;               // { title, detail, errors: [...] } nebo null
    }
}

/**
 * Složí uživatelskou hlášku z chyby API (audit KN-14 / 11-F-3).
 *
 * Backend vrací u validace RFC 9457 s **konstantním** `detail` („Ověření zadaných údajů selhalo")
 * a konkrétními hláškami v `errors[]` (`GlobalExceptionHandler`). Frontend dřív čítal jen `detail`,
 * takže uživatel u formuláře s patnácti políčky nedostal ani jméno vadného pole — přestože ho
 * server poslal. Tohle je jediné místo, kde se hláška skládá, ať to nedělá dvacet catch bloků
 * po svém.
 *
 * Fallback se použije, když tělo není ProblemDetail: HTML chybová stránka z proxy nebo `TypeError`
 * ze `fetch` při nedostupném serveru. Nikdy se nevrací `err.message` — to je surové tělo odpovědi
 * nebo anglické „Failed to fetch" (konvence §17: fallback vždy česky).
 *
 * @param {unknown} err      odchycená chyba (typicky {@link ApiError})
 * @param {string}  fallback česká hláška pro případ, že server nic použitelného neposlal
 * @returns {string} hláška k zobrazení uživateli
 */
export function problemMessage(err, fallback) {
    const problem = err?.problem;
    if (!problem) {
        return fallback;
    }

    const detail = problem.detail?.trim();
    // Duplicity se zahazují dvakrát: stejná hláška u dvou polí (Set) a hláška shodná s `detail`.
    // Druhý případ je běžný — u 404 posílá `ResourceNotFoundException` tentýž text v `detail`
    // i v `errors[0]`, takže by hláška zněla „Zákazník s ID 9 neexistuje: Zákazník s ID 9
    // neexistuje" (odhaleno prokliknutím, ne testem).
    const fieldMessages = [...new Set((problem.errors ?? [])
        .map(e => e?.message?.trim())
        .filter(Boolean))]
        .filter(message => message !== detail);

    if (fieldMessages.length === 0) {
        return detail || fallback;
    }
    return detail
        ? `${detail}: ${fieldMessages.join(" · ")}`
        : fieldMessages.join(" · ");
}

let refreshPromise = null;

/** Jediný souběžný refresh (rotace tokenů — dva paralelní refreshe by se navzájem zabily). */
export function tryRefresh() {
    if (!refreshPromise) {
        refreshPromise = fetch(`${API_BASE}/auth/refresh`, {
            method: 'POST',
            credentials: 'include',
        })
            .then((r) => r.ok)
            .catch(() => false)
            .finally(() => { refreshPromise = null; });
    }
    return refreshPromise;
}

/**
 * Základní fetch wrapper pro všechna volání API.
 *
 * Chování:
 * - Vždy posílá cookies (credentials: 'include') — autentizace používá HTTP-only JWT cookies.
 * - Na 401 Unauthorized (mimo /auth/* volání): pokusí se jednou o single-flight
 *   refresh (viz `tryRefresh`) a zopakuje původní request; když refresh selže
 *   nebo šlo o druhý pokus (`_retried`), přesměruje na /login.
 * - Pro 204 No Content a prázdná těla vrací null.
 * - Na ne-2xx status vyhazuje ApiError s naparsovaným RFC 9457 ProblemDetail
 *   (když je tělo JSON) dostupným v `.problem` a surovým tělem odpovědi
 *   v `.message` (zpětně kompatibilní fallback pro ne-JSON těla,
 *   např. HTML 502 z proxy).
 * - Když je `options.body` instance FormData (upload souboru), 'Content-Type' se
 *   záměrně NENASTAVUJE — prohlížeč si ho musí vygenerovat sám, včetně
 *   multipart boundary. Ruční nastavení rozbije request na straně serveru.
 *
 * @param {string} path    - cesta API relativní k API_BASE (např. '/customers/1')
 * @param {RequestInit & {_retried?: boolean}} [options] - volby pro fetch (method, body, headers, ...)
 * @returns {Promise<any|null>} naparsovaná JSON odpověď, nebo null pro prázdné odpovědi
 * @throws {ApiError} se `.status`, `.problem` (naparsovaný ProblemDetail nebo null) a `.message` (surový text)
 */
async function apiFetch(path, options = {}) {
    const isFormData = options.body instanceof FormData;

    const headers = isFormData
        ? { ...(options.headers || {}) }
        : { 'Content-Type': 'application/json', ...(options.headers || {}) };

    const response = await fetch(`${API_BASE}${path}`, {
        ...options,
        credentials: 'include',
        headers,
    });

    if (response.status === 401 && path !== '/auth/login') {
        // 401 z /auth/login není „vypršelá session", ale špatné přihlašovací
        // údaje (nebo zamčený účet) — nechá se propadnout do throw ApiError níže,
        // aby LoginPage mohla zobrazit hlášku; redirect by ji zahodil.
        const isAuthCall = path.startsWith('/auth/');
        if (!isAuthCall && !options._retried && await tryRefresh()) {
            return apiFetch(path, { ...options, _retried: true });
        }
        window.location.href = '/login';
        return;
    }

    if (response.status === 204) return null;

    const text = await response.text();
    if (!response.ok) {
        let problem = null;
        try { problem = text ? JSON.parse(text) : null; } catch { /* ne-JSON tělo (proxy, HTML) */ }
        throw new ApiError(response.status, problem, text);
    }
    if (!text) return null;
    return JSON.parse(text);
}

/** Typovaný API klient pro všechny backendové endpointy. */
export const api = {
    get:    (path)            => apiFetch(path, { method: 'GET' }),
    post:   (path, body)      => apiFetch(path, { method: 'POST',   body: JSON.stringify(body) }),
    put:    (path, body)      => apiFetch(path, { method: 'PUT',    body: JSON.stringify(body) }),
    delete: (path)            => apiFetch(path, { method: 'DELETE' }),

    /**
     * Nahraje soubor (nebo libovolný multipart formulář) jako multipart/form-data.
     * FormData sestavuje volající (např. `const fd = new FormData(); fd.append('file', file)`).
     * Content-Type NENASTAVOVAT ručně — apiFetch ho vynechává, aby si prohlížeč
     * mohl vygenerovat multipart boundary sám.
     *
     * @param {string} path
     * @param {FormData} formData
     */
    upload: (path, formData)  => apiFetch(path, { method: 'POST', body: formData }),

    /**
     * Stáhne binární zdroj (např. PDF) s credentials a vrátí
     * object URL pro <iframe>/<a>. Volající musí při úklidu zavolat URL.revokeObjectURL().
     * Používá přímý fetch (ne apiFetch) — na 401 se stejně jako apiFetch pokusí
     * jednou o single-flight refresh a zopakuje request, jinak přesměruje na /login.
     *
     * @param {string} path - cesta API relativní k API_BASE
     * @param {boolean} [_retried] - interní příznak, nenastavovat ručně
     * @returns {Promise<string|null>} object URL, nebo null při jakékoli chybové odpovědi (nejen 404)
     */
    getBlob: async (path, _retried = false) => {
        const response = await fetch(`${API_BASE}${path}`, { credentials: 'include' });
        if (response.status === 401) {
            if (!_retried && await tryRefresh()) {
                return api.getBlob(path, true);
            }
            window.location.href = '/login';
            return null;
        }
        if (!response.ok) return null;
        const blob = await response.blob();
        return URL.createObjectURL(blob);
    },
};
