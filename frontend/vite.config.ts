import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const apiProxyTarget =
  process.env.JOBFLOW_API_PROXY ?? 'http://127.0.0.1:8090';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Proxy /api → Spring Boot so the React app can call /api/... directly
      // without dealing with CORS during local dev.
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
        // Strip browser Origin header before forwarding to Spring. The backend's
        // CORS filter sees a same-origin request (no Origin) and skips its check.
        // Without this, Vite falling back to 5174/5175 when 5173 is held would
        // trigger "Invalid CORS request" until the backend allowlist is updated.
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin');
          });
        },
      },
    },
  },
});
