import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies API and WebSocket traffic to the Spring Boot backend,
// so the frontend can be served from a different origin during development.
//
// The backend endpoint is externalized via env files so local and production
// environments are cleanly separated:
//   - Local dev  (.env.development): VITE_BACKEND_URL points the dev proxy at
//     the local Spring Boot backend (default http://localhost:8080).
//   - Production (.env.production):  the app is served behind nginx which
//     proxies /api and /ws to the backend Service, so no proxy is needed here
//     and the app uses same-origin relative paths.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendUrl = env.VITE_BACKEND_URL || 'http://localhost:8080'

  return {
    plugins: [react()],
    server: {
      port: Number(env.VITE_DEV_PORT) || 5173,
      proxy: {
        '/api': { target: backendUrl, changeOrigin: true },
        '/ws': { target: backendUrl, ws: true, changeOrigin: true },
      },
    },
  }
})
