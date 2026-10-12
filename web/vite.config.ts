import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    // 分包（T042）结论：**不启用 manualChunks**。
    // 实测（同一份源码，仅构建配置不同）：
    //   · 手动把 @lobehub/ui 归入 vendor 后：入口需预加载整个组件库 → 首屏 gzip 1463 KB（更差，
    //     且把 shiki 的动态语言包合并成 10 MB 巨型共享块并被预加载）；
    //   · 交由 Rollup 按动态导入自动切分：首屏 gzip 583 KB，`/chat` 路由独立懒加载。
    // 结论依据见交付说明与 specs/009-rag-service-home/quickstart.md 的体积核对项。
    chunkSizeWarningLimit: 2000,
  },
  server: {
    port: 5173,
    proxy: {
      // 后端无 CORS 配置，开发期同源代理（plan D5）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
