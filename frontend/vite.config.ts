import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In development the Vite server proxies /api to the Spring Boot backend, so the browser talks to
// a single origin and no CORS configuration is needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.FINCORE_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
