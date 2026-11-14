# Repository Instructions

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `NorthPhoenix/hairdresser-client-manager`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default mattpocock/skills triage label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

Use a single-context domain docs layout. See `docs/agents/domain.md`.

## Production server logs

Vercel's Hobby plan keeps runtime logs for about one hour, so `vercel logs` only reaches back that far. Older logs are archived on the maintainer's server in `~/hcm-logs` (see its `README.md`), copied by a cron job every 15 minutes:

- `~/hcm-logs/runtime/YYYY-MM-DD.jsonl` holds one Vercel runtime log record per line, filed by US Central day, with timestamps in UTC. It holds only what the app prints with `console.*`; there is no status line per request.
- `~/hcm-logs/gaps.jsonl` lists time ranges whose logs were lost, and `~/hcm-logs/pull.log` shows each run. Check both before concluding that something never happened.

When investigating a production problem from the past, read the archive instead of `vercel logs`. The archive is read-only for agents; do not edit or delete its files.

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
