import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Verification build: never run the main config's static-directory cleanup hook.
export default defineConfig({
  plugins: [vue()],
  resolve: { preserveSymlinks: true },
  build: { outDir: '../target/frontend-recipient-check', emptyOutDir: false },
});
