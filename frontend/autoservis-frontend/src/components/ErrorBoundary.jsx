import { Component } from "react";

/**
 * Zachytí pád libovolné stránky uvnitř layoutu a místo bílé obrazovky ukáže
 * kartu s vysvětlením a cestou ven. Sidebar zůstává funkční, protože boundary
 * obaluje jen <Outlet>, ne celý layout.
 *
 * Musí být class komponenta — React nemá hook ekvivalent componentDidCatch.
 */
export default class ErrorBoundary extends Component {

    constructor(props) {
        super(props);
        this.state = { error: null };
    }

    static getDerivedStateFromError(error) {
        return { error };
    }

    componentDidCatch(error, info) {
        // Do konzole, ať je pád dohledatelný i po zavření karty. Nikam se neodesílá.
        console.error("Pád stránky zachycen ErrorBoundary:", error, info);
    }

    handleRetry = () => {
        this.setState({ error: null });
    };

    render() {
        const { error } = this.state;
        if (!error) return this.props.children;

        return (
            <div className="d-flex justify-content-center py-5">
                <section className="card border-0 shadow-sm" style={{ maxWidth: "40rem" }}>
                    <div className="card-body p-4">
                        <h1 className="h4 mb-2">
                            <i className="bi bi-exclamation-triangle text-danger me-2"></i>
                            Něco se pokazilo
                        </h1>
                        <p className="text-muted">
                            Stránku se nepodařilo zobrazit. Zkuste to prosím znovu, nebo se vraťte
                            na přehled.
                        </p>

                        {import.meta.env.DEV && (
                            <pre className="bg-body-secondary rounded p-3 small text-danger mb-3"
                                 style={{ whiteSpace: "pre-wrap" }}>
                                {String(error?.message ?? error)}
                            </pre>
                        )}

                        <div className="d-flex gap-2">
                            <button type="button" className="btn btn-primary" onClick={this.handleRetry}>
                                Zkusit znovu
                            </button>
                            <button type="button" className="btn btn-outline-secondary"
                                    onClick={() => { window.location.href = "/dashboard"; }}>
                                Zpět na Dashboard
                            </button>
                        </div>
                    </div>
                </section>
            </div>
        );
    }
}
