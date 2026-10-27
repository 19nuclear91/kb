import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': { target: process.env.KB_WEB_GATEWAY ?? 'http://localhost:8080', changeOrigin: false } } },
  build: { sourcemap: false, chunkSizeWarningLimit: 1200 },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    maxWorkers: 2,
    restoreMocks: true,
  },
});
