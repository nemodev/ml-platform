# UI End-to-End Automation

This project includes a Playwright-based UI journey test that validates core user workflows and catches auth redirect-cycle regressions.

## Modes

- `smoke` (default): fast validation of login, redirect-cycle stability, and core pages.
- `full`: includes heavier workflows (workspace launch/terminate and pipeline trigger).
- `all`: runs both smoke and full tests.

`smoke` is designed to fail fast when the portal enters an auth redirect loop. You should see a clear error like `redirect cycle detected` instead of a long click timeout.

## Scenarios Covered

The suite (`frontend/e2e/specs/portal-user-journeys.spec.ts`) verifies:

1. Login flow and portal shell rendering.
2. Auth callback stability (no repeated authorization loop).
3. Core pages from user perspective: Notebooks, Experiments, Models, Pipelines (`smoke`).
4. Notebooks workflow (launch workspace, validate running state, terminate cleanup) (`full`).
5. Pipelines workflow (open trigger dialog, load notebooks, trigger run, verify run detail) (`full`).

## Run Script

Use the shared script from repository root:

```bash
./infrastructure/scripts/run-ui-e2e.sh
```

Optional arguments:

```bash
./infrastructure/scripts/run-ui-e2e.sh <base_url> <username> <password>
```

Example:

```bash
./infrastructure/scripts/run-ui-e2e.sh http://172.16.100.10:30080 user1 password1
```

Run full workflows:

```bash
./infrastructure/scripts/run-ui-e2e.sh http://172.16.100.10:30080 user1 password1 --mode full
```

Run all scenarios:

```bash
./infrastructure/scripts/run-ui-e2e.sh --mode all
```

## Useful Environment Variables

- `UI_TEST_HEADLESS=true|false` (default: `true`)
- `UI_TEST_INSTALL_BROWSERS=1|0` (default: `1`)
- `UI_TEST_MODE=smoke|full|all` (default: `smoke`)

Example headed run:

```bash
UI_TEST_HEADLESS=false ./infrastructure/scripts/run-ui-e2e.sh
```
