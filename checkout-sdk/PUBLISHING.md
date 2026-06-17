# Publishing the NexusPay checkout SDK

This monorepo ships three public npm packages. This is the runbook for cutting a
release. **Publishing requires the owner's npm account/token — no one else can
complete this step.**

## What gets published

| Package | Path | npm | Notes |
|---|---|---|---|
| `@nexus-pay/js` | `packages/js` | public | Browser SDK (ESM + CJS + IIFE/CDN) |
| `@nexus-pay/react` | `packages/react` | public | React bindings (peer-deps `react`, `@nexus-pay/js`) |
| `@nexus-pay/node` | `packages/node` | public | Server SDK + webhook verification |
| `nexuspay-checkout` | `packages/checkout` | **never** | Vite demo SPA; `"private": true` |

## License

The published packages are **MIT** — separate from the repository's PolyForm
Noncommercial license. The root `LICENSE` carries a SCOPE NOTE that explicitly
carves out `checkout-sdk/packages/*` as MIT, so they can be embedded freely in
client applications. Each published package has its own `LICENSE` file.

## Prerequisites (owner only)

- An npm account with publish rights to the `@nexuspay` scope.
- For CI provenance: the repo must be **public** and the workflow has
  `id-token: write` (already configured in `release.yml`).

## One-time setup

1. Create a **granular / automation npm token** with publish permission for the
   `@nexuspay` scope (npmjs.com → Access Tokens).
2. Add it as a repository secret named **`NPM_TOKEN`**
   (Settings → Secrets and variables → Actions → New repository secret).
3. (Optional) configure an `npm-publish` environment if you want a manual
   approval gate before publishes run.

## Releasing via CI (recommended)

1. Bump the version in each of the three `packages/*/package.json` (the owner
   owns versioning — keep them in sync).
2. Commit the bump.
3. Tag and push:
   ```bash
   git tag sdk-v0.1.0
   git push origin sdk-v0.1.0
   ```
   **or** go to the Actions tab → **release-sdk** → **Run workflow**
   (`workflow_dispatch`).

The `release-sdk` workflow runs `npm ci` → `npm run build` → `npm publish` for
`@nexus-pay/js`, `@nexus-pay/node`, then `@nexus-pay/react` (react last, since it
peer-depends on js), each with `--access public --provenance`.

## Manual fallback (local, owner machine)

```bash
cd checkout-sdk
npm login                                  # owner's npm creds
npm ci && npm run build
npm publish -w @nexus-pay/js   --access public
npm publish -w @nexus-pay/node --access public
npm publish -w @nexus-pay/react --access public   # last
```

## Dry-run / verify before publishing

```bash
npm publish --dry-run -w @nexus-pay/js
npm publish --dry-run -w @nexus-pay/node
npm publish --dry-run -w @nexus-pay/react
```

Confirm each tarball's file list is **`dist/*` + `README.md` + `LICENSE`** and
contains **no** `src/`, tests, `tsconfig.json`, `tsup.config.ts`, or
`vite.config.ts`.

## Skip-when-no-token

If `NPM_TOKEN` is not set, the `release-sdk` job runs, logs a warning, does
nothing, and exits green. This is intentional so the repo never hard-fails for
contributors or forks that lack publish credentials.

## Troubleshooting

- **`E402` (payment required / private)** — add `--access public` (scoped
  packages default to restricted). Already set in CI and the manual commands.
- **`E403` (forbidden)** — the package name is taken or your account lacks rights
  to the `@nexuspay` scope.
- **Provenance failure** — the repo must be public and the workflow needs
  `id-token: write` (already configured).
