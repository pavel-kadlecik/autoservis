import * as React from 'react';
import {useState} from 'react';
import {Menu, MenuItem, ListItemIcon, ListItemText, IconButton} from '@mui/material';
import MoreVertIcon from '@mui/icons-material/MoreVert';

/**
 * Řádkové menu tabulek (tři tečky) — jediná cesta ke všem akcím se záznamem.
 *
 * @param {Object}   rowData  - řádek, ke kterému menu patří (kvůli názvu tlačítka)
 * @param {Function} onAction(actionId, rowData)
 * @param {Array}    actions  - [{id, label, icon, color?}]
 * @param {string}   [rowLabel] - čím se řádek jmenuje v přístupném názvu; bez něj se
 *                               zkusí obvyklá pole záznamu
 */
export default function TableRowActionMenu({ rowData, onAction, actions, rowLabel }) {
    const [anchorEl, setAnchorEl] = useState(null);
    const open = Boolean(anchorEl);

    /**
     * MUI `IconButton` je `<button>`, jehož jediným obsahem je ikona s `aria-hidden` — bez
     * `aria-label` neměl **žádný přístupný název** a odečítač u desetiřádkové tabulky přečetl
     * desetkrát „tlačítko" (audit 11-F-8, WCAG 4.1.2). Název proto nese i identifikaci řádku,
     * ať je z něj poznat, ke kterému záznamu akce patří.
     */
    const label = rowLabel
        ?? rowData?.orderNumber ?? rowData?.invoiceNumber ?? rowData?.stockTakeNumber
        ?? rowData?.displayName ?? rowData?.fullName ?? rowData?.name
        ?? rowData?.sku ?? rowData?.licensePlate ?? rowData?.username
        ?? rowData?.id;

    const handleOpen = (event) => {
        event.preventDefault();
        event.stopPropagation();
        setAnchorEl(event.currentTarget);
    };

    const handleClose = (e) => {
        if (e) e.stopPropagation();
        setAnchorEl(null);
    };

    const handleItemClick = (action, e) => {
        if (e) e.stopPropagation();
        onAction(action, rowData);
        handleClose();
    };

    return (
        <>
            <IconButton
                size="small"
                onClick={handleOpen}
                id={`action-button-${rowData?.id}`}
                aria-label={label ? `Akce — ${label}` : "Akce"}
                aria-haspopup="menu"
                aria-expanded={open}
                title="Akce"
            >
                <MoreVertIcon fontSize="small"/>
            </IconButton>

            <Menu
                anchorEl={anchorEl}
                open={open}
                onClose={handleClose}
                onClick={(e) => e.stopPropagation()}
            >
                {actions.map(a =>
                    <MenuItem
                        key={a.id}
                        onClick={(e) => handleItemClick(a.id, e)}
                        sx={{ color: a.color || 'inherit' }}
                    >
                        <ListItemIcon sx={{ color: a.color || 'inherit' }}>
                            {a.icon}
                        </ListItemIcon>
                        <ListItemText>
                            {a.label}
                        </ListItemText>
                    </MenuItem>
                )}
            </Menu>
        </>
    );
}
