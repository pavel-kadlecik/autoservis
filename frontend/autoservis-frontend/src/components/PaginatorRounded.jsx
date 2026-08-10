import Pagination from '@mui/material/Pagination';

/**
 * Patička seznamu: volba velikosti stránky, rozsah „od – do z celkem" a stránkování.
 *
 * Nemá třídu `card-footer` — seznam není karta, takže Bootstrapí `card-footer`
 * se tu opíralo o kontext, který neexistuje. Vlastní `.list-footer` v `index.css`.
 */
export default function PaginationRounded({itemCount, totalPages, pageSize, page, handleChange, handlePageCount}) {

    const from = ((page - 1) * pageSize) + 1;
    const to = Math.min(((page - 1) * pageSize) + pageSize, itemCount);

    return (

        <div className="list-footer d-flex align-items-center justify-content-between py-2">

            <div className="page-count d-flex align-items-center">
                <select name="pageSize" className="form-select" value={pageSize}
                        aria-label="Počet záznamů na stránku" onChange={handlePageCount}>
                    <option value="2" >2</option>
                    <option value="5" >5</option>
                    <option value="10">10</option>
                    <option value="20">20</option>
                    <option value="50">50</option>
                </select>

                <small className="text-muted ms-3 text-nowrap" id="pagination-info">{from} - {to} z {itemCount}</small>
            </div>
            <Pagination count={totalPages} variant="outlined" shape="rounded" page={page} onChange={handleChange} />
        </div>

    );
}
