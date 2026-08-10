import {formatCurrency, formatNumber, getOrderItemTypeLabel, withVat} from "../api/format.js";
import StatusBadge from "./StatusBadge.jsx";
import OrderItemStock from "./OrderItemStock.jsx";
import OrderItemName from "./OrderItemName.jsx";
import React from "react";
import {DndContext, PointerSensor, useSensor, useSensors} from '@dnd-kit/core';
import {SortableContext, arrayMove, useSortable, verticalListSortingStrategy} from '@dnd-kit/sortable';
import {CSS} from '@dnd-kit/utilities';

function SortableRow({item, onEdit, onDelete}) {
    const {attributes, listeners, setNodeRef, transform, transition, isDragging} = useSortable({id: item.id});

    const style = {
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.5 : 1,
        cursor: 'grab',
    };

    return (
        <tr ref={setNodeRef} style={style} {...attributes} {...listeners}>
            <td><StatusBadge tone="secondary">{getOrderItemTypeLabel(item.itemType)}</StatusBadge></td>
            <td><OrderItemName item={item}/></td>
            <td className="text-end">{formatNumber(item.quantity)}</td>
            <td>{item.unit}</td>
            <td><OrderItemStock item={item}/></td>
            <td className="text-end text-muted">{formatCurrency(item.purchasePrice)}</td>
            <td className="text-end fw-semibold">{formatCurrency(item.unitPrice)}</td>
            <td className="text-end">{item.vatRate} %</td>
            {/* Ceny s DPH: zákazník uvažuje v částce, kterou zaplatí, takže při domlouvání
                ceny je tohle to jediné číslo, které ho zajímá. Souhrn pod tabulkou částky
                s DPH měl, jednotlivé řádky ne. */}
            <td className="text-end">{formatCurrency(withVat(item.unitPrice, item.vatRate))}</td>
            <td className="text-end fw-semibold">
                {formatCurrency(withVat(Number(item.quantity) * Number(item.unitPrice), item.vatRate))}
            </td>
            <td className="text-end" onPointerDown={e => e.stopPropagation()}>
                <button type="button" className="btn btn-sm btn-outline-secondary me-1"
                        onClick={() => onEdit(item)}>Upravit</button>
                <button type="button" className="btn btn-sm btn-outline-danger"
                        onClick={() => onDelete(item.id)}>Smazat</button>
            </td>
        </tr>
    );
}

export default function OrderItemTable({items, onEdit, onDelete, onReorder}) {

    const sensors = useSensors(
        useSensor(PointerSensor, {activationConstraint: {distance: 8}})
    );

    function handleDragEnd(event) {
        const {active, over} = event;
        if (!over || active.id === over.id) return;

        const oldIndex = items.findIndex(i => i.id === active.id);
        const newIndex = items.findIndex(i => i.id === over.id);
        const newItems = arrayMove(items, oldIndex, newIndex);

        onReorder(newItems);

    }

    return (
        <>
            <h2 className="mb-4 border-bottom pb-3">Položky zakázky</h2>

            {items.length > 0 ? (
                <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
                    <SortableContext items={items.map(i => i.id)} strategy={verticalListSortingStrategy}>
                        <div className="table-responsive">
                        <table className="table table-sm table-hover mb-3">
                            <caption className="text-muted small">Sloupce „Nákup" a „Cena" jsou bez DPH; poslední dva sloupce s DPH.</caption>
                            <thead className="table-light">
                            <tr>
                                <th scope="col">Typ</th><th scope="col">Název</th>
                                <th scope="col" className="text-end">Mn.</th><th scope="col">Jedn.</th>
                                <th scope="col">Sklad</th>
                                <th scope="col" className="text-end">Nákup</th>
                                <th scope="col" className="text-end">Cena</th>
                                <th scope="col" className="text-end">DPH</th>
                                <th scope="col" className="text-end">Cena/ks s DPH</th>
                                <th scope="col" className="text-end">Celkem s DPH</th>
                                <th scope="col"></th>
                            </tr>
                            </thead>
                            <tbody>
                            {items.map(item => (
                                <SortableRow key={item.id} item={item} onEdit={onEdit} onDelete={onDelete}/>
                            ))}
                            </tbody>
                        </table>
                        </div>
                    </SortableContext>
                </DndContext>
            ) : (
                <p className="text-muted fst-italic mb-3">Zakázka nemá žádné položky.</p>
            )}
        </>
    );
}