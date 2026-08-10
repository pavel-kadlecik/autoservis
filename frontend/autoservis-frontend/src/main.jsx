import {StrictMode} from 'react'
import {createRoot} from 'react-dom/client'
import 'bootstrap/dist/css/bootstrap.min.css'
import 'bootstrap/dist/js/bootstrap.bundle.min.js'
import './index.css'
import App from './App.jsx'
import "./css/reset.css"
import "./css/help.css"
import {AlertProvider} from "./context/AlertContext.jsx";

createRoot(document.getElementById('root')).render(
    <StrictMode>
        <AlertProvider>
            <App/>
        </AlertProvider>
    </StrictMode>
)