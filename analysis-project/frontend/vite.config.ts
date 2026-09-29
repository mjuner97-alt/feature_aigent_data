import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import fs from 'node:fs';
import path from 'node:path';

// 清空 static/ 但保留 pm/ 子目录 (frontend-pm 的构建产物, base=/pm/)
function cleanStaticKeepPm(): Plugin {
  return {
    name: 'clean-static-keep-pm',
    buildStart() {
      const outDir = path.resolve(__dirname, '../src/main/resources/static');
      if (!fs.existsSync(outDir)) return;
      for (const entry of fs.readdirSync(outDir)) {
        if (entry === 'pm') continue;
        fs.rmSync(path.join(outDir, entry), { recursive: true, force: true });
      }
    },
  };
}

export default defineConfig({
  plugins: [vue(), cleanStaticKeepPm()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: false,
    rollupOptions: {
      output: {
        // 拆分大型第三方依赖为独立 vendor chunk，减小主包体积
        manualChunks: {
          echarts: ['echarts'],
          vue: ['vue', 'vue-router'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/v2': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
      '/ai': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
      '/redirect': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
    },
  },
});
