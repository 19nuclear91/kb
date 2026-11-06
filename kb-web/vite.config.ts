import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { bundleAnalysis } from './scripts/bundle-analysis.ts';

export default defineConfig({
  plugins: [react(), bundleAnalysis()],
  server: { proxy: { '/api': { target: process.env.KB_WEB_GATEWAY ?? 'http://localhost:8080', changeOrigin: false } } },
  build: { sourcemap: false, chunkSizeWarningLimit: 1200, manifest: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    maxWorkers: 2,
    restoreMocks: true,
  },
});
