import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout.jsx'
import RequireAuth from './components/RequireAuth.jsx'
import LoginPage from './pages/LoginPage.jsx'
import CustomersPage from './pages/CustomersPage.jsx'
import CustomersPageDetail from './pages/CustomersPageDetail.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import CustomersPageEdit from "./pages/CustomersPageEdit.jsx";
import VehiclesPage from "./pages/VehiclesPage.jsx";
import VehiclesPageEdit from "./pages/VehiclesPageEdit.jsx";
import VehiclesPageDetail from "./pages/VehiclesPageDetail.jsx";
import VehiclesPageCreate from "./pages/VehiclesPageCreate.jsx";
import OrdersPage from "./pages/OrdersPage.jsx";
import OrdersPageEdit from "./pages/OrdersPageEdit.jsx";
import OrdersPageDetail from "./pages/OrdersPageDetail.jsx";
import OrdersPageCreate from "./pages/OrdersPageCreate.jsx";
import SchedulePage from "./pages/SchedulePage.jsx";
import CustomersPageCreate from "./pages/CustomersPageCreate.jsx";
import WarehousePage from "./pages/WarehousePage.jsx";
import ReceiptsPage from "./pages/ReceiptsPage.jsx";
import StockTakesPage from "./pages/StockTakesPage.jsx";
import LowStockPage from "./pages/LowStockPage.jsx";
import StockTakePageDetail from "./pages/StockTakePageDetail.jsx";
import ReceiptReviewPage from "./pages/ReceiptReviewPage.jsx";
import WarehousePageDetail from "./pages/WarehousePageDetail.jsx";
import WarehousePageCreate from "./pages/WarehousePageCreate.jsx";
import WarehousePageEdit from "./pages/WarehousePageEdit.jsx";
import SuppliersPage from "./pages/SuppliersPage.jsx";
import SuppliersPageDetail from "./pages/SuppliersPageDetail.jsx";
import SuppliersPageEdit from "./pages/SuppliersPageEdit.jsx";
import InvoicesPage from "./pages/InvoicesPage.jsx";
import InvoicesPageDetail from "./pages/InvoicesPageDetail.jsx";
import CreditNotesPageDetail from "./pages/CreditNotesPageDetail.jsx";
import OpeningHoursPage from "./pages/OpeningHoursPage.jsx";
import CompanyProfilePage from "./pages/CompanyProfilePage.jsx";
import HelpPage from "./pages/HelpPage.jsx";
import UsersPage from "./pages/UsersPage.jsx";
import UsersPageCreate from "./pages/UsersPageCreate.jsx";
import UsersPageEdit from "./pages/UsersPageEdit.jsx";
import EmployeesPage from "./pages/EmployeesPage.jsx";
import EmployeesPageCreate from "./pages/EmployeesPageCreate.jsx";
import EmployeesPageEdit from "./pages/EmployeesPageEdit.jsx";
import NotFoundPage from "./pages/NotFoundPage.jsx";

export default function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/login" element={<LoginPage />} />

                <Route element={<RequireAuth><Layout /></RequireAuth>}>
                    <Route path="/dashboard" element={<DashboardPage />} />
                    <Route path="/customers" element={<CustomersPage />} />
                    <Route path="/customers/new" element={<CustomersPageCreate />} />
                    <Route path="/customers/:id/detail" element={<CustomersPageDetail />} />
                    <Route path="/customers/:id/edit" element={<CustomersPageEdit />} />

                    <Route path="/vehicles" element={<VehiclesPage />} />
                    <Route path="/vehicles/new" element={<VehiclesPageCreate />} />
                    <Route path="/vehicles/:id/detail" element={<VehiclesPageDetail />} />
                    <Route path="/vehicles/:id/edit" element={<VehiclesPageEdit backPath={'/vehicles'} />} />

                    <Route path="/orders" element={<OrdersPage />} />
                    <Route path="/orders/new" element={<OrdersPageCreate />} />
                    <Route path="/orders/:id/detail" element={<OrdersPageDetail />} />
                    <Route path="/orders/:id/edit" element={<OrdersPageEdit backPath={'/orders'} />} />

                    <Route path="/schedule" element={<SchedulePage />} />

                    <Route path="/warehouse" element={<WarehousePage />} />
                    <Route path="/warehouse/receipts" element={<ReceiptsPage />} />
                    <Route path="/warehouse/receipts/:id/review" element={<ReceiptReviewPage />} />
                    <Route path="/warehouse/low-stock" element={<LowStockPage />} />
                    <Route path="/warehouse/stock-takes" element={<StockTakesPage />} />
                    <Route path="/warehouse/stock-takes/:id" element={<StockTakePageDetail />} />
                    <Route path="/warehouse/new" element={<WarehousePageCreate />} />
                    <Route path="/warehouse/:id/detail" element={<WarehousePageDetail />} />
                    <Route path="/warehouse/:id/edit" element={<WarehousePageEdit backPath={'/warehouse'} />} />

                    <Route path="/suppliers" element={<SuppliersPage />} />
                    <Route path="/suppliers/:id/detail" element={<SuppliersPageDetail />} />
                    <Route path="/suppliers/:id/edit" element={<SuppliersPageEdit backPath={'/suppliers'} />} />

                    <Route path="/invoices" element={<InvoicesPage />} />
                    <Route path="/invoices/:id/detail" element={<InvoicesPageDetail />} />
                    {/* Opravný daňový doklad nemá vlastní seznam — chodí se na něj z faktury. */}
                    <Route path="/credit-notes/:id/detail" element={<CreditNotesPageDetail />} />

                    {/* Fakturační údaje (dřív „Nastavení firmy") nejsou podstránka faktur —
                        je to globální nastavení (U2.1). Stará cesta zůstává jako redirect
                        kvůli uloženým odkazům. */}
                    <Route path="/settings/company" element={<CompanyProfilePage />} />
                    <Route path="/settings/opening-hours" element={<OpeningHoursPage />} />
                    <Route path="/invoices/settings" element={<Navigate to="/settings/company" replace />} />

                    <Route path="/employees" element={<EmployeesPage />} />
                    <Route path="/employees/new" element={<EmployeesPageCreate />} />
                    <Route path="/employees/:id/edit" element={<EmployeesPageEdit />} />

                    <Route path="/users" element={<UsersPage />} />
                    <Route path="/users/new" element={<UsersPageCreate />} />
                    <Route path="/users/:id/edit" element={<UsersPageEdit />} />

                    <Route path="/help" element={<HelpPage />} />
                    <Route path="/help/:slug" element={<HelpPage />} />

                    {/* Bez catch-all vykreslila neznámá adresa prázdnou stránku. */}
                    <Route path="*" element={<NotFoundPage />} />
                </Route>

                <Route path="/" element={<Navigate to="/dashboard" />} />
            </Routes>
        </BrowserRouter>
    )
}
