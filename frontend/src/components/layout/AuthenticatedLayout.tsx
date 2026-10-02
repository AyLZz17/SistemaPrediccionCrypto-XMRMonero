import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Footer } from './Footer'
import { Header } from './Header'
import { Sidebar } from './Sidebar'

/**
 * Shell for every authenticated route.
 *
 * The `<Footer />` lives here, at the layout level, so no protected route can
 * render without it (it is structurally impossible for a child route to escape
 * the layout).
 */
export function AuthenticatedLayout() {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="flex min-h-screen flex-col bg-deep lg:flex-row">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-sm focus:border focus:border-accent-cyan/60 focus:bg-surface-2 focus:px-4 focus:py-2 focus:text-sm focus:text-ink"
      >
        Saltar al contenido principal
      </a>

      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />

      <div className="flex min-w-0 flex-1 flex-col">
        <Header onMenuClick={() => setMenuOpen(true)} />

        <main id="main-content" className="flex-1 px-4 py-6 sm:px-6 lg:px-8" tabIndex={-1}>
          <div className="mx-auto w-full max-w-content animate-fade-in">
            <Outlet />
          </div>
        </main>

        <Footer />
      </div>
    </div>
  )
}
