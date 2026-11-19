import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

// Trang quản trị tải riêng (người dùng thường không phải tải phần code này)
// eslint-disable-next-line react-refresh/only-export-components -- file khởi động, không cần fast refresh
const AdminPage = lazy(() => import('./AdminPage.jsx'))
const isAdminRoute = window.location.pathname.replace(/\/+$/, '') === '/admin'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {isAdminRoute ? (
      <Suspense fallback={null}>
        <AdminPage />
      </Suspense>
    ) : (
      <App />
    )}
  </StrictMode>,
)
