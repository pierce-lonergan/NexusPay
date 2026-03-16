import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs', 'iife'],
  globalName: 'NexusPay',
  dts: true,
  sourcemap: true,
  clean: true,
  minify: true,
  target: 'es2020',
});
