---
name: server-logs
description: Use when you want to read server logs of the app.
---

# Server logs

Two kinds of log, which answer different questions:

- **Events**: the server prints one wide event per request, a single JSON line `{"event":"hcm.request", ...}` with the procedure, outcome, duration, database cost, IDs and the reason for a refusal or failure. Every field is in the field catalog of `docs/observability.md`; read it to know what a field means before drawing conclusions from it. Other lines (warnings, crashes) are plain text.
- **Request records**: Vercel's own record of every request (path, status, route, `pathType`, cache result, region), also of requests whose code printed nothing: static files, cached pages, the first page request on a new instance. Use them to count requests, to see the status a visitor got, and to find requests with no event.

## 1. Pick the source by time

Production runs on Vercel, whose plan keeps events for about **one hour** and request records for **12 hours**. A cron job on the maintainer's server copies both every 15 minutes into an archive and indexes them. Run `hcm-logs status` first: its `runtime saved up to` and `requests saved up to` lines are the times up to which each kind is in the archive.

| What you need | Source |
| --- | --- |
| Before that kind's "saved up to" time | The archive: `hcm-logs` (step 2). Always prefer it; it searches by field. Events reach back to 2026-11-14, request records to 2026-11-15 14:52 Central. |
| After it (roughly the last 15–20 minutes), within Vercel's retention | Live from Vercel (step 3). |
| Older than Vercel keeps and not in the archive | Lost. Check `hcm-logs status` and `~/hcm-logs/gaps.jsonl` for the lost range; say so rather than concluding it never happened. |
| A local run (`apps/dev-backend`, `next dev`/`next start`) | The process's own output: the same events, one per line. There are no request records locally. |

A question that spans both (for example "what happened in the last hour") needs both: the archive up to the checkpoint and live logs after it.

Done when you know which sources cover the whole time range in question.

## 2. The archive: `hcm-logs`

`hcm-logs` searches `~/hcm-logs/logs.db`, in which every event key is a column under its own name. Times are shown in US Central time. `--since`/`--until` take `30m`, `24h`, `7d`, a Central day (`2026-11-15`) or a time.

```sh
hcm-logs fields trpc.                                 # which fields exist, their types and counts
hcm-logs search outcome=error --since 7d              # rows matching filters, newest last
hcm-logs search trpc.procedure=clientProfile.save 'duration_ms>500' --fields duration_ms,db.query_count
hcm-logs search 'error.reason?' --since 30d           # rows that have the field at all
hcm-logs search SSL warning                           # plain words: full-text search over messages and errors
hcm-logs stats --by trpc.procedure --since 30d        # count, errors, refusals, p50/p95/max/avg
hcm-logs stats event=hcm.request --by instance.cold_start --metric db.query_ms
hcm-logs requests httpStatus=404 --since 7d          # Vercel's request records
hcm-logs requests --unlogged pathType!=static --since 7d   # requests that printed no event
hcm-logs stats --table requests --by route,httpStatus --since 7d
hcm-logs trace <trace_id | request.id | Vercel requestId>   # events and request record of one request
hcm-logs sql 'SELECT "error.reason", count(*) FROM events WHERE _ms > 0 GROUP BY 1'
```

Filters: `key=value`, `key!=value`, `key>n` (and `>=`, `<`, `<=`), `key~text` (contains), `key?` (has the field), `!key?` (lacks it). Booleans are 1/0 (`instance.cold_start=true` works). `--json` prints one object per line, `--fields '*'` every field, `--limit` defaults to 50. In SQL, quote dotted names (`"trpc.procedure"`); `_ms`, `_time` (UTC), `_kind` (`event`, `line`, `broken_event`), `message` and `vercel.*` (Vercel's envelope) are there too. The `requests` table keeps Vercel's field names (`requestId`, `httpStatus`, `pathType`, ...; `hcm-logs fields --table requests`), and its `requestId` is the events' `"vercel.request_id"`. More in `~/hcm-logs/README.md`.

The archive is read-only for you: query it, but never edit or delete its files, and do not run `pull-logs.py` or `hcm-logs index` (the cron job does both).

## 3. Live logs from Vercel

Only for what the archive does not have yet. Use the archive's own token so nobody has to sign in:

```sh
export PATH="$HOME/.local/share/mise/installs/node/24/bin:$PATH"
VERCEL_TOKEN="$(cat ~/.config/hcm/vercel-logs-token)" ~/.local/share/pnpm/vercel logs \
  -p hairdresser-client-manager --scope nikita-istomins-projects --since 20m --json -n 1000 \
  | jq -c 'select(.type=="log") | .data | {timestamp, requestPath, level, message}'
```

- Each record is under `.data`; the event is the JSON in `.data.message` (`.data.message | fromjson` for the events, which start with `{"event":"hcm.`). Timestamps are UTC; convert to Central time when you report them.
- `--query 'level:error'` (or `requestPath:...`, `requestId:...`, `deploymentId:...`) filters on Vercel's side; the last line is a summary with `pagination.next` when there is more (`--next <cursor>`).
- `vercel logs requests` (same flags) gives the request records; each is under `.data` of a line with `"type":"request"`.
- Run it from outside the repository (`cd /tmp`) so the CLI does not pick up a linked project, and never print or log the token.

If Vercel answers `forbidden` or `permission_denied`, the token has expired or been revoked: tell the maintainer, who makes a new team-scoped token at vercel.com/account/tokens for that file.

## Before concluding that something did not happen

- On Vercel, the first request for a **page** on a new server instance prints no event (only an `hcm.process` start line). Routes, tRPC and the proxy are complete. See "How it works" in `docs/observability.md`. The request records have that request: `hcm-logs requests --unlogged pathType!=static`.
- Events can be sampled when `HCM_TELEMETRY_SAMPLE_RATE` is below 1 (the default is 1; errors, refusals and slow requests are always kept). `sample.rate` on each event says which applied.
- Check `hcm-logs status` for failed pulls and lost ranges in the window.

## Reporting

The repository is public. In issues, pull requests and commits, report counts, field values and IDs of records, never whole raw log lines. Events hold no Client names, phone numbers, notes or addresses by design; if you ever find one in a log, report that as a bug instead of repeating it.
