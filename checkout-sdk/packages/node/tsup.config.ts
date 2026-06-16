import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'], // no iife: server package
  dts: true,
  sourcemap: true,
  clean: true,
  target: 'node18',
});
