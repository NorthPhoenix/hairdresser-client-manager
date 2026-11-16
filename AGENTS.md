# Repository Instructions

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `NorthPhoenix/hairdresser-client-manager`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default mattpocock/skills triage label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

Use a single-context domain docs layout. See `docs/agents/domain.md`.

## Production server logs

Vercel's Hobby plan keeps runtime logs for about one hour, so `vercel logs` only reaches back that far. Older logs are archived on the maintainer's server in `~/hcm-logs` (see its `README.md`), copied by a cron job every 15 minutes with a Vercel token of its own:

- **Search them with `hcm-logs`** (on the PATH). It queries `~/hcm-logs/logs.db`, a SQLite index in which every key of a wide event (field catalog: `docs/observability.md`) is a column under its own name. Start with `hcm-logs fields` to see what exists, then for example `hcm-logs search outcome=error --since 7d`, `hcm-logs stats --by trpc.procedure --since 30d` (counts, errors, refusals, duration percentiles), `hcm-logs trace <trace_id or request id>`, or read-only SQL with `hcm-logs sql 'SELECT ... FROM events ...'` (quote dotted names: `"trpc.procedure"`). Plain words in `search` are full-text search over messages and errors. Times are shown in US Central time.
- `~/hcm-logs/runtime/YYYY-MM-DD.jsonl` is the archive the index is built from: one Vercel runtime log record per line, filed by US Central day, timestamps in UTC. It holds only what the app prints (the wide events, plus any other line).
- `~/hcm-logs/gaps.jsonl` lists time ranges whose logs were lost, and `~/hcm-logs/pull.log` shows each run (`hcm-logs status` summarises both). Check them before concluding that something never happened.

When investigating a production problem from the past, use `hcm-logs` instead of `vercel logs`. The archive is read-only for agents: query it, but do not edit or delete its files or run `hcm-logs index --rebuild`.

## Temporary artifacts

When creating local artifacts for the maintainer, such as testing guides or scratch documents, place them under `/temp` at the repository root. `/temp/**` is intentionally gitignored and should not be committed.

## Testing guides

Create a new local testing guide in `/temp` for each issue completed. The guide is for the maintainer to test the actual changed behavior by hand, not to review implementation details or run automated checks.

Testing guides should:

- focus only on user-visible behavior changed by the issue
- provide step-by-step manual flows the maintainer can run locally, such as installing the Android debug build on an emulator or device and interacting with the changed screen
- include concrete expected results after each flow
- cover important edge cases introduced by the change
- avoid broad app regression checklists unless the issue changed broad app behavior
- avoid automated commands such as lint, typecheck, smoke tests, or unit tests unless the maintainer explicitly asks for them
- avoid documentation/schema verification unless the issue itself is documentation/schema-only
