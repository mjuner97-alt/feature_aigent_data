import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/v2': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/ai': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
