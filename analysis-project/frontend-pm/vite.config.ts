import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: process.env.VITE_OUT_DIR || '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/v2': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
