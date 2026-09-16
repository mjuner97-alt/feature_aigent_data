import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/pm/',
  plugins: [react()],
  build: {
    outDir: process.env.VITE_OUT_DIR || '../src/main/resources/static/pm',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/v2': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/redirect': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
