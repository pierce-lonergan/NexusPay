import { defineConfig } from 'tsup';

export default defineConfig([
  // Library entry: ESM + CJS + d.ts. Owns `clean` so a build wipes dist first.
  {
    entry: ['src/index.ts'],
    format: ['esm', 'cjs'], // no iife: server package
    dts: true,
    sourcemap: true,
    clean: true,
    target: 'node18',
  },
  // CLI bin: emit dist/bin/nexuspay.cjs. CJS + `.cjs` extension because the
  // package is `"type":"module"` — a `.cjs` shebang script is unambiguously
  // CommonJS and avoids ESM-shebang interop edge cases. `clean:false` so this
  // block does NOT wipe the library output above.
  {
    entry: { 'bin/nexuspay': 'src/bin/nexuspay.ts' },
    format: ['cjs'],
    outExtension: () => ({ js: '.cjs' }),
    banner: { js: '#!/usr/bin/env node' },
    dts: false,
    sourcemap: false,
    clean: false,
    target: 'node18',
  },
]);
