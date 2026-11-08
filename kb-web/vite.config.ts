import vue from '@vitejs/plugin-vue';
import ElementPlus from 'unplugin-element-plus/vite';
import { defineConfig } from 'vitest/config';
import { bundleAnalysis } from './scripts/bundle-analysis.ts';

const testing = process.env.VITEST === 'true';

export default defineConfig({
  plugins: [vue(), ...(testing ? [] : [ElementPlus({}), bundleAnalysis()])],
  server: { proxy: { '/api': { target: process.env.KB_WEB_GATEWAY ?? 'http://localhost:8080', changeOrigin: false } } },
  build: { sourcemap: false, chunkSizeWarningLimit: 1200, manifest: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.ts'],
    maxWorkers: 2,
    restoreMocks: true,
    // 让 Vite 处理 Element Plus 的 ESM → async-validator CJS 互操作，与生产构建一致。
    // 原生 Node 外部导入会把默认导出当作对象，导致 ElForm 校验异常被吞并误判成功。
    server: { deps: { inline: ['element-plus'] } },
  },
});
