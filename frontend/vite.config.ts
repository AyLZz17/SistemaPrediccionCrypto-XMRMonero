/// <reference types="vitest" />
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * Optional dev-server HTTPS (R-33). Certificates are NOT committed (R-14).
 * Place them at `certs/cert.pem` + `certs/key.pem` (git-ignored) or point
 * VITE_DEV_HTTPS_CERT / VITE_DEV_HTTPS_KEY at your own files.
 */
const certPath = process.env.VITE_DEV_HTTPS_CERT ?? './certs/cert.pem'
const keyPath = process.env.VITE_DEV_HTTPS_KEY ?? './certs/key.pem'
const hasDevCerts =
  existsSync(resolve(process.cwd(), certPath)) && existsSync(resolve(process.cwd(), keyPath))

const pkg = JSON.parse(readFileSync(resolve(process.cwd(), 'package.json'), 'utf8')) as {
  version?: string
}

export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version ?? '0.0.0'),
  },
  server: {
    port: 3000,
    host: true,
    ...(hasDevCerts
      ? { https: { key: readFileSync(keyPath), cert: readFileSync(certPath) } }
      : {}),
  },
  preview: {
    port: 4173,
    host: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          query: ['@tanstack/react-query'],
          charts: ['recharts'],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    env: {
      VITE_API_BASE_URL: 'https://localhost:8443',
      VITE_REFRESH_TOKEN_MODE: 'body',
    },
  },
})
