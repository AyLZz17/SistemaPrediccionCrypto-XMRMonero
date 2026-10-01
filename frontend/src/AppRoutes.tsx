import { Suspense, lazy, type ReactElement } from 'react'
import { Route, Routes } from 'react-router-dom'
import { AuthenticatedLayout } from './components/layout/AuthenticatedLayout'
import { PublicLayout } from './components/layout/PublicLayout'
import { GuestOnlyRoute, ProtectedRoute, RoleRoute } from './components/routes/ProtectedRoute'
import { SkeletonPanel, StatusDot } from './components/ui'
import LandingPage from './pages/LandingPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import ResetPasswordPage from './pages/ResetPasswordPage'
import GoogleCallbackPage from './pages/GoogleCallbackPage'
import VerifyEmailPage from './pages/VerifyEmailPage'
import NotFoundPage from './pages/NotFoundPage'
import TermsPage from './pages/TermsPage'
import PrivacyPage from './pages/PrivacyPage'
import DataPolicyPage from './pages/DataPolicyPage'
import CookiesPage from './pages/CookiesPage'
import LegalNoticePage from './pages/LegalNoticePage'

/*
 * Both layouts always render <Footer />, so the mandatory footer is structurally
 * guaranteed on every route and every visible state (see Footer.tsx).
 *
 * Role policy (R-35, re-enforced server-side):
 *   VIEWER : dashboard, market, predictions, jobs, account
 *   ANALYST: + experiments, metrics, models
 *   ADMIN  : + /admin, /admin/audit, /admin/logs
 */
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const MarketPage = lazy(() => import('./pages/MarketPage'))
const PredictionsPage = lazy(() => import('./pages/PredictionsPage'))
const ExperimentsPage = lazy(() => import('./pages/ExperimentsPage'))
const MetricsPage = lazy(() => import('./pages/MetricsPage'))
const ModelComparisonPage = lazy(() => import('./pages/ModelComparisonPage'))
const JobsPage = lazy(() => import('./pages/JobsPage'))
const AccountPage = lazy(() => import('./pages/AccountPage'))
const AdminPage = lazy(() => import('./pages/AdminPage'))
const AuditPage = lazy(() => import('./pages/AuditPage'))
const LogsPage = lazy(() => import('./pages/LogsPage'))

function RouteFallback() {
  return (
    <div className="space-y-4" role="status" aria-busy="true">
      <span className="sr-only">Cargando vista...</span>
      <div className="flex items-center gap-3">
        <StatusDot tone="active" label="Cargando modulo" pulse />
      </div>
      <SkeletonPanel />
      <SkeletonPanel />
    </div>
  )
}

const guarded = (element: ReactElement, minimum?: 'ANALYST' | 'ADMIN') =>
  minimum ? <RoleRoute minimum={minimum}>{element}</RoleRoute> : element

export function AppRoutes() {
  return (
    <Routes>
      {/* ---------------------------------------------------------- public */}
      <Route
        element={
          <PublicLayout>
            <LandingPage />
          </PublicLayout>
        }
        path="/"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <GuestOnlyRoute>
              <LoginPage />
            </GuestOnlyRoute>
          </PublicLayout>
        }
        path="/login"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <GuestOnlyRoute>
              <RegisterPage />
            </GuestOnlyRoute>
          </PublicLayout>
        }
        path="/register"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <ForgotPasswordPage />
          </PublicLayout>
        }
        path="/forgot-password"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <ResetPasswordPage />
          </PublicLayout>
        }
        path="/reset-password"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <GoogleCallbackPage />
          </PublicLayout>
        }
        path="/auth/callback"
      />
      <Route
        element={
          <PublicLayout variant="auth">
            <VerifyEmailPage />
          </PublicLayout>
        }
        path="/verify-email"
      />

      {/* ------------------------------------------------------ documentos legales
          Publicos por definicion: son la contraparte de lo que se acepta en el
          registro y el pie de pagina los enlaza desde cualquier pantalla. */}
      <Route
        element={
          <PublicLayout>
            <TermsPage />
          </PublicLayout>
        }
        path="/terms"
      />
      <Route
        element={
          <PublicLayout>
            <PrivacyPage />
          </PublicLayout>
        }
        path="/privacy"
      />
      <Route
        element={
          <PublicLayout>
            <DataPolicyPage />
          </PublicLayout>
        }
        path="/data-policy"
      />
      <Route
        element={
          <PublicLayout>
            <CookiesPage />
          </PublicLayout>
        }
        path="/cookies"
      />
      <Route
        element={
          <PublicLayout>
            <LegalNoticePage />
          </PublicLayout>
        }
        path="/legal-notice"
      />

      {/* --------------------------------------------------- authenticated */}
      <Route
        element={
          <ProtectedRoute>
            <AuthenticatedLayout />
          </ProtectedRoute>
        }
      >
        <Route
          path="dashboard"
          element={
            <Suspense fallback={<RouteFallback />}>
              <DashboardPage />
            </Suspense>
          }
        />
        <Route
          path="market"
          element={
            <Suspense fallback={<RouteFallback />}>
              <MarketPage />
            </Suspense>
          }
        />
        <Route
          path="predictions"
          element={
            <Suspense fallback={<RouteFallback />}>
              <PredictionsPage />
            </Suspense>
          }
        />
        <Route
          path="experiments"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <ExperimentsPage />
            </Suspense>,
            'ANALYST',
          )}
        />
        <Route
          path="metrics"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <MetricsPage />
            </Suspense>,
            'ANALYST',
          )}
        />
        <Route
          path="models"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <ModelComparisonPage />
            </Suspense>,
            'ANALYST',
          )}
        />
        <Route
          path="jobs"
          element={
            <Suspense fallback={<RouteFallback />}>
              <JobsPage />
            </Suspense>
          }
        />
        <Route
          path="account"
          element={
            <Suspense fallback={<RouteFallback />}>
              <AccountPage />
            </Suspense>
          }
        />
        <Route
          path="admin"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <AdminPage />
            </Suspense>,
            'ADMIN',
          )}
        />
        <Route
          path="admin/audit"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <AuditPage />
            </Suspense>,
            'ADMIN',
          )}
        />
        <Route
          path="admin/logs"
          element={guarded(
            <Suspense fallback={<RouteFallback />}>
              <LogsPage />
            </Suspense>,
            'ADMIN',
          )}
        />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default AppRoutes
