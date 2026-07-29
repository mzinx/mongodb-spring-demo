import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies API and WebSocket traffic to the Spring Boot backend,
// so the frontend can be served from a different origin during development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'http://localhost:8080', ws: true, changeOrigin: true },
    },
  },
})
