# Repository Instructions

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `NorthPhoenix/hairdresser-client-manager`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default mattpocock/skills triage label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

Use a single-context domain docs layout. See `docs/agents/domain.md`.

## Temporary artifacts

When creating local artifacts for the maintainer, such as testing guides or scratch documents, place them under `/temp` at the repository root. `/temp/**` is intentionally gitignored and should not be committed.

## Testing guides

Create a new local testing guide in `/temp` for each issue completed. The guide is for the maintainer to test the actual changed behavior by hand, not to review implementation details or run automated checks.

Testing guides should:

- focus only on user-visible behavior changed by the issue
- provide step-by-step manual flows the maintainer can run locally, such as opening the app in Expo Go and interacting with the changed screen
- include concrete expected results after each flow
- cover important edge cases introduced by the change
- avoid broad app regression checklists unless the issue changed broad app behavior
- avoid automated commands such as lint, typecheck, smoke tests, or unit tests unless the maintainer explicitly asks for them
- avoid documentation/schema verification unless the issue itself is documentation/schema-only
