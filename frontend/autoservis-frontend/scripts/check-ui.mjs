/**
 * Statická kontrola UI konvencí (docs/frontend.md §10).
 *
 * Proč to existuje: `vite build` nic z toho nezachytí. Neznámý identifikátor je
 * v JS legální zápis a spadne až za běhu na stránce, kterou nikdo neotevřel —
 * přesně tak proklouzl `StatusBadge` do ReceiptDraftHeaderForm a shodil detail
 * příjemky. Porušené konvence (vlastní `<h1>`, ručně psaný modal) build nezajímají
 * vůbec, a přitom právě z nich vzniká nejednotnost, kterou plán UI odstraňuje.
 *
 * Spouští se `npm run check` a je povinnou součástí ověření každé fáze plánu.
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const ROOT = "src";

/** Odstraní komentáře a řetězce, ať se v nich nehledají JSX značky a třídy. */
function strip(source) {
    return source
        .replace(/\/\*[\s\S]*?\*\//g, " ")
        .replace(/(^|[^:])\/\/[^\n]*/g, "$1 ")
        .replace(/`(?:\\[\s\S]|[^`\\])*`/g, "``");
}

function walk(dir) {
    const out = [];
    for (const entry of readdirSync(dir)) {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) out.push(...walk(path));
        else if (entry.endsWith(".jsx")) out.push(path);
    }
    return out;
}

const files = walk(ROOT).map(path => {
    const raw = readFileSync(path, "utf8");
    return { path, raw, code: strip(raw), name: path.split(/[\\/]/).pop() };
});

const failures = [];
function fail(rule, path, detail) {
    failures.push(`  [${rule}] ${path}${detail ? " — " + detail : ""}`);
}

// 1. Komponenta použitá v JSX bez importu → runtime ReferenceError.
for (const { path, raw, code } of files) {
    const used = new Set([...code.matchAll(/<([A-Z][A-Za-z0-9_]*)/g)].map(m => m[1]));
    if (used.size === 0) continue;

    const imported = new Set();
    for (const m of raw.matchAll(/import\s+([^;]+?)\s+from/gs)) {
        for (const part of m[1].replace(/\*\s+as\s+/g, "").match(/[A-Za-z0-9_$]+/g) ?? []) {
            imported.add(part);
        }
    }
    const local = new Set([
        ...[...code.matchAll(/(?:function|class)\s+([A-Z][A-Za-z0-9_]*)/g)].map(m => m[1]),
        ...[...code.matchAll(/(?:const|let|var)\s+([A-Z][A-Za-z0-9_]*)\s*=/g)].map(m => m[1]),
    ]);

    const missing = [...used].filter(n => !imported.has(n) && !local.has(n) && n !== "React").sort();
    if (missing.length > 0) fail("import", path, `použito bez importu: ${missing.join(", ")}`);
}

// 1b. Pojmenovaný import z vlastního modulu, který ten modul neexportuje.
//     Stejná třída chyby jako výše, jen u funkcí místo komponent — a pravidlo 1
//     ji nechytí, protože funkce se v JSX neobjevují jako <Značka>.
//     (Přesně takhle by proklouzl formatQuantity importovaný z format.js,
//     kde ještě neexistoval.)
for (const { path, raw } of files) {
    for (const m of raw.matchAll(/import\s*\{([^}]+)\}\s*from\s*["'](\.[^"']+)["']/g)) {
        const target = resolve(dirname(path), m[2]);
        if (!existsSync(target)) {
            fail("import", path, `neexistující modul ${m[2]}`);
            continue;
        }
        const source = readFileSync(target, "utf8");
        for (const raw of m[1].split(",")) {
            const name = raw.split(/\s+as\s+/)[0].trim();
            if (!name) continue;
            const exported = new RegExp(
                `export\\s+(?:async\\s+)?(?:function|const|let|var|class)\\s+${name}\\b`
                + `|export\\s*\\{[^}]*\\b${name}\\b`
            ).test(source);
            if (!exported) fail("import", path, `${m[2]} neexportuje ${name}`);
        }
    }
}

// 1c. Setter `setXxx` volaný, ale nikde v souboru nezavedený.
//     Typicky zbytek po odstraněném `useState` — obsluha tlačítka pak spadne na
//     ReferenceError a dialog se prostě neotevře. Build ani pravidla výše to
//     nechytí (není to import ani JSX značka). Přesně takhle přestalo fungovat
//     „Zamítnout" na kontrole příjemky po U6.2.
for (const { path, code } of files) {
    const zavedene = new Set();
    // useState destrukturalizace i běžná deklarace/parametr
    for (const m of code.matchAll(/\[\s*\w+\s*,\s*(set[A-Z]\w*)\s*\]/g)) zavedene.add(m[1]);
    for (const m of code.matchAll(/(?:const|let|var|function)\s+(set[A-Z]\w*)\b/g)) zavedene.add(m[1]);
    for (const m of code.matchAll(/\b(set[A-Z]\w*)\s*[,)}:]/g)) zavedene.add(m[1]);   // props
    // `(?<![.\w])` vynechá volání na objektu (`localStorage.setItem`, `this.setState`) —
    // ta nejsou lokální proměnné a tohle pravidlo o nich nic neví.
    for (const m of code.matchAll(/(?<![.\w])(set[A-Z]\w*)\s*\(/g)) {
        const name = m[1];
        if (name === "setTimeout" || name === "setInterval") continue;
        if (!zavedene.has(name)) fail("setter", path, `${name}() se volá, ale nikde se nezavádí`);
    }
}

// 2. Nadpis stránky vlastní výhradně PageHeader — jinak odsazení závisí na tom,
//    co je zrovna pod nadpisem (nadpisy nemají marginy, viz css/reset.css).
const H1_ALLOWED = new Set(["PageHeader.jsx", "ErrorBoundary.jsx", "LoginPage.jsx"]);
for (const { path, code, name } of files) {
    if (H1_ALLOWED.has(name)) continue;
    if (/<h1[\s>]/.test(code)) fail("nadpis", path, "vlastní <h1> místo <PageHeader>");
}

// 3. Modál staví jen komponenta Modal (Esc, focus trap, zámek scrollu).
for (const { path, code, name } of files) {
    if (name === "Modal.jsx") continue;
    if (/modal\s+show\s+d-block/.test(code)) fail("modal", path, "ručně psaný modal místo <Modal>");
}

// 3b. Kdo staví celoobrazovkovou vrstvu (`position-fixed`/`modal`/`backdrop`),
//     musí ji poslat portálem do body — jinak ji uvězní stacking kontext předka
//     (sidebar má `position: sticky`) a její z-index neplatí vůči obsahu stránky.
//     Přesně takhle se `ChangePasswordModal` vykresloval ZA kartami formuláře.
const OVERLAY_ALLOWED = new Set(["ErrorBoundary.jsx"]);   // celostránkový fallback, žádný portál nepotřebuje
for (const { path, code, name } of files) {
    if (OVERLAY_ALLOWED.has(name)) continue;
    const staviVrstvu = /className="[^"]*\b(modal-backdrop|position-fixed)\b/.test(code)
        || /className="modal fade show d-block"/.test(code);
    if (staviVrstvu && !/createPortal/.test(code)) {
        fail("portal", path, "celoobrazovková vrstva bez createPortal (uvězní ji stacking kontext)");
    }
}

// 4. Badge jen přes StatusBadge — plné text-bg-* se nepoužívá (rozhodnutí R-3).
for (const { path, code } of files) {
    if (/text-bg-/.test(code)) fail("badge", path, "text-bg-* místo <StatusBadge tone=…>");
}

// 5. Tlačítko se nesmí zkracovat — text-truncate na tlačítku dělá z popisku „…“.
for (const { path, code } of files) {
    for (const m of code.matchAll(/className="[^"]*\bbtn\b[^"]*"/g)) {
        if (m[0].includes("text-truncate")) fail("responsivita", path, "text-truncate na tlačítku");
    }
}

// 6. `IconButton` musí mít přístupný název. MUI ikony jsou `aria-hidden`, takže tlačítko
//    s ikonou a bez `aria-label` nemá pro odečítač žádné jméno — a přes `TableRowActionMenu`
//    vedou všechny řádkové akce v aplikaci (audit 11-F-8, WCAG 4.1.2).
for (const { path, code } of files) {
    for (const m of code.matchAll(/<IconButton\b[^>]*>/g)) {
        if (!/aria-label/.test(m[0])) fail("a11y-nazev", path, "<IconButton> bez aria-label");
    }
}

// 7. Hlavička tabulky potřebuje `scope="col"` — jinak odečítač nespojí buňku se sloupcem.
//    Ručně psané tabulky ho neměly ani jedna, přestože TD-44 tvrdila opak (audit 11-F-16).
for (const { path, code } of files) {
    for (const head of code.matchAll(/<thead[\s\S]*?<\/thead>/g)) {
        for (const th of head[0].matchAll(/<th(?![\w-])[^>]*>/g)) {
            if (!/scope=/.test(th[0])) fail("a11y-scope", path, "<th> v <thead> bez scope=\"col\"");
        }
    }
}

if (failures.length > 0) {
    console.error("Porušené UI konvence (docs/frontend.md §10):\n");
    console.error(failures.join("\n"));
    console.error("");
    process.exit(1);
}

console.log(`check-ui: OK — ${files.length} souborů, 10 pravidel bez nálezu.`);
