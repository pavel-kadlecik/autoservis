import * as React from "react";
import {NavLink, Navigate, useParams} from "react-router-dom";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {HELP_ARTICLES} from "../help/index.js";
import PageHeader from "../components/PageHeader.jsx";

/**
 * Úrovně nadpisů se posouvají o jednu níž (U7.1).
 *
 * Stránka má `h1` „Nápověda" v `PageHeader`; kdyby si článek nesl vlastní `h1`,
 * měla by stránka nadpisy dva a čtečka obrazovky by hlásila dva názvy stránky.
 * Vzhled se nemění — velikost řídí třídy `help-h1`…`help-h3`, ne úroveň značky,
 * takže markdown článků zůstává čitelný i mimo aplikaci.
 */
const MARKDOWN_COMPONENTS = {
    h1: props => <h2 className="help-h1" {...props} />,
    h2: props => <h3 className="help-h2" {...props} />,
    h3: props => <h4 className="help-h3" {...props} />,
    // Tabulka dostane vlastní obal se scrollem — široká tabulka jinak roztáhne
    // celou stránku, stejné pravidlo jako `.table-responsive` v aplikaci (§10.7).
    table: props => <div className="help-table"><table {...props} /></div>,
};

/**
 * Uživatelská nápověda — /help/:slug.
 *
 * Články jsou statické markdown soubory přibalené do bundlu (src/help/,
 * Vite import ?raw) a registrované v HELP_ARTICLES. Bez :slug přesměruje
 * na první článek; neznámý slug ukáže seznam dostupných článků.
 */
export default function HelpPage() {

    const {slug} = useParams();

    if (!slug) {
        return <Navigate to={`/help/${HELP_ARTICLES[0].slug}`} replace/>;
    }

    const article = HELP_ARTICLES.find(a => a.slug === slug);

    return (
        <div>
            <PageHeader title="Nápověda" subtitle={article?.title} />
            <div className="row g-3">
                <div className="col-md-3">
                    <div className="list-group shadow-sm help-nav">
                        {HELP_ARTICLES.map(a => (
                            <NavLink key={a.slug} to={`/help/${a.slug}`}
                                     className={({isActive}) =>
                                         "list-group-item list-group-item-action" + (isActive ? " active" : "")}>
                                {a.title}
                            </NavLink>
                        ))}
                    </div>
                </div>
                <div className="col-md-9">
                    <section className="card border-0 shadow-sm">
                        <div className="card-body px-4 py-4 help-article">
                            {article
                                ? <Markdown remarkPlugins={[remarkGfm]} components={MARKDOWN_COMPONENTS}>
                                    {article.content}
                                  </Markdown>
                                : <p className="text-muted mb-0">
                                    Článek nenalezen — vyberte téma ze seznamu vlevo.
                                  </p>}
                        </div>
                    </section>
                </div>
            </div>
        </div>
    );
}
