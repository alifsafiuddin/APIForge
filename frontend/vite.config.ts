/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Where the dev server forwards /api and /actuator. The browser only talks to the Vite
  // origin, so the session cookie (added in the auth phase) stays same-origin.
  const backendUrl = env.APIFORGE_BACKEND_URL || 'http://localhost:8081'

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    build: {
      rolldownOptions: {
        output: {
          // Framework code changes far less often than application code; separate chunks let
          // browsers keep them cached across deployments.
          codeSplitting: {
            groups: [
              { name: 'react', test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/, priority: 30 },
              { name: 'router', test: /node_modules[\\/]react-router[\\/]/, priority: 20 },
              { name: 'ui', test: /node_modules[\\/](radix-ui|@radix-ui|lucide-react)[\\/]/, priority: 20 },
              { name: 'vendor', test: /node_modules[\\/]/, priority: 10 },
            ],
          },
        },
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: backendUrl, changeOrigin: false },
        '/actuator': { target: backendUrl, changeOrigin: false },
      },
    },
    preview: {
      port: 4173,
      strictPort: true,
      proxy: {
        '/api': { target: backendUrl, changeOrigin: false },
        '/actuator': { target: backendUrl, changeOrigin: false },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
      // Longer than the 4 s wait in src/test/setup.ts, so a missing element is reported as such, not as a timeout.
      testTimeout: 15_000,
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/test/**', 'src/main.tsx', 'src/**/*.test.{ts,tsx}', 'src/vite-env.d.ts'],
      },
    },
  }
})
