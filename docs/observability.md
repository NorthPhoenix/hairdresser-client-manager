# Observability: one wide event per request

Every request the server handles prints **one line of JSON** to stdout when it ends: the request's *wide event*. It says what happened to the request: which procedure ran, for which Stylist, what the code decided and why, how many database queries it made and how long they took, and, when it failed or was refused, the error with its code and stack. The line is meant to be searched later by people and agents looking for slow requests, failures and things to correct. The decision and its alternatives are in [ADR 0019](adr/0019-wide-events-on-opentelemetry.md).

This document is the contract for everyone who adds to the event: how to add fields, what to call them, what must never be in them, and every field there is.

## How it works

The server is traced with OpenTelemetry. The request's **root span is the event**: Next.js opens a root span for every request it handles (`BaseServer.handleRequest`), and the dev backend opens one with `traceRequest`. An incoming `traceparent` header continues the caller's trace.

`@hcm/telemetry` installs a span processor (`WideEventProcessor`) next to the SDK. It sees every span start and end. A span with no parent in this process opens an event; every span below it belongs to that event. Code anywhere in the call stack adds fields to the current request's event with `annotate`, which finds the event through the active OpenTelemetry context (AsyncLocalStorage), so nothing is passed around. Child spans (Prisma's queries, outgoing `fetch` calls, units of work wrapped in `traced`) are added up into the event: query count and time, operations by model, external calls, the slowest piece of work. When the root span ends, the event is built, the tail sampler decides whether to keep it, and it is printed with `console.log` as one line.

The same spans are exported where an exporter is set up: on Vercel through `@vercel/otel` to Vercel's own trace collection (Vercel Tracing, which the Hobby plan has, once a sampling rule is added under Settings → Tracing), and to any OTLP endpoint named in `OTEL_EXPORTER_OTLP_ENDPOINT`. With neither, nothing is exported and nothing breaks. The annotated fields are also set on the root span, so a trace viewer can search them.

Nothing in telemetry throws into a request. Every function swallows its own failures; `traced` runs its work exactly once even when tracing around it breaks; a failure inside telemetry is counted and reported in the next event as `telemetry.internal_errors`.

Where it is wired:

| Where | What |
| --- | --- |
| `packages/telemetry` (`@hcm/telemetry`) | The API (`annotate`, `increment`, `traced`, `recordError`, `traceRequest`, `hashPrefix`, `requestFields`), the processor, the event builder, the tail sampler and the privacy guard. `@hcm/telemetry/node` sets up the SDK (`registerTelemetry`). |
| `apps/web/instrumentation.ts` | `registerTelemetry({ serviceName: "hcm-web" })` at startup; `onRequestError` records errors that no code caught (pages, route handlers, proxy.ts). TRACE is still refused there before anything else; a refused TRACE has no event. |
| `packages/api/src/trpc.ts` | The outermost procedure step names the procedure, the input's size and fields, the app version and whether the request is signed in. `stylistProcedure` adds the Stylist, `stylistTransactionProcedure` the transaction's timing. `recordProcedureError` is the `onError` of both servers. |
| `apps/web/app/api/trpc/[trpc]/route.ts`, `apps/dev-backend/src/server.ts` | Request header facts (`requestFields`) and `onError: recordProcedureError`. The dev backend wraps each request in `traceRequest`, which replaces its old access log line. |

## Adding to the event

Import from `@hcm/telemetry` (already a dependency of `packages/api`, `apps/web` and `apps/dev-backend`). Never import `@hcm/telemetry/node` outside the two server entry points.

```ts
import { annotate, increment, recordError, traced } from "@hcm/telemetry";

// Fields: IDs, counts, enum values, decisions. Nested objects become dotted keys.
annotate({
  "appointment.id": appointment.id,
  "appointment.client_count": clientIds.length,
  appointment: { is_recurring: recurrence !== null, location_type: input.locationType }
});

// A counter that several places add to.
increment("client.merged_photo_count", photos.length);
```

A decision is a field that names the branch the code took, so that a later search can ask how often each branch happened:

```ts
annotate({ "appointment.overlap_check": overlaps.length === 0 ? "clear" : "overlaps" });
```

### Refusals and failures

A refusal on purpose is thrown as a `TRPCError` as before; `recordProcedureError` records it in the event with its code and `error.expected: true`. To add the domain's reason, record the same error before throwing it. The two records become one:

```ts
const refusal = new TRPCError({ code: "CONFLICT", message: "This Appointment overlaps another." });

recordError(refusal, { expected: true, reason: "appointment_overlaps" });
throw refusal;
```

A failure that the code catches and gets over (a log line used to be printed there) is recorded as `handled`. The request keeps its outcome; the event is kept by the sampler and carries the error:

```ts
} catch (error) {
  recordError(error, { handled: true, reason: "photo_storage_delete_failed" });
}
```

An unexpected failure that escapes is recorded by `recordProcedureError` (tRPC) or `onRequestError` (everything else in Next.js). Do not catch it only to record it. `recordError` outside any request, or where telemetry is not set up, prints an `hcm.error` line of its own, so an error is never lost.

### When to make a child span

Use `traced(name, fn)` for a **unit of work that takes time on its own and that you would want to see in a trace**: a group of queries that implement one check, a call to another service, building a Share Image, a loop over many records. Its time counts towards `slowest_span`, and it shows in the exported trace. Use `annotate` for everything else; most code only annotates.

```ts
const overlaps = await traced("appointment.find_overlaps", async (span) => {
  const found = await ctx.db.appointment.findMany({ where: overlapWhere });

  span.annotate({ "overlap.count": found.length }); // on this span only, not on the event
  return found;
});
```

Prisma queries and outgoing `fetch` calls already have spans; do not wrap a single query. Name spans `<domain>.<unit_of_work>` in snake_case.

### Gotchas

- `traced` returns a plain Promise for async work. Never wrap a Prisma query that is passed to the array form of `$transaction([...])`, which needs Prisma's own lazy promises.
- Inside `$transaction(async (tx) => …)` the context carries on: `annotate`, `traced` and Prisma's spans all work, and Prisma's spans sit under `prisma:client:transaction`. A field annotated inside a transaction that rolls back stays in the event: it describes what was tried. `stylistTransactionProcedure` records `tx.outcome`.
- A later value for the same key replaces the earlier one; use `increment` to add up.
- Work that goes on after the response (a promise left running) adds nothing: the event was already printed.
- `annotate` before telemetry is set up, in a unit test or a script does nothing. Tests need no mocks.
- Never pass a whole input object or database row to `annotate`. Name the fields.

## Naming rules

- Keys are lower snake_case words joined by dots: `<namespace>.<field>`. camelCase given to `annotate` is turned into snake_case, but write snake_case.
- Namespaces are the domain's nouns from CONTEXT.md in snake_case: `stylist.`, `client.`, `appointment.`, `service.`, `service_menu.`, `photo.`, `color_formula.`, `family.`, `profile_share.`, `share_image.`, `release.`, `upload.`; and technical ones: `trpc.`, `http.`, `app.`, `auth.`, `tx.`, `next.`.
- The main record of the request is `<entity>.id`; a related one `<entity>.<other>_id` (`appointment.primary_client_id`); several are `<entity>.ids` (an array, at most 20) next to `<entity>.count`. IDs are written as the database has them, never prefixed or shortened.
- Units are in the name, never in the value: `_ms` (milliseconds), `_bytes`, `_count`, `_length` (characters), `_days`, `_minor` (an amount in minor currency units, as stored).
- Booleans read as a statement: `has_phone`, `is_recurring`, `just_created`, `ok`.
- Enum values and reasons are snake_case strings: `"overlaps"`, `"client_has_appointments"`.
- These are written by the event itself and cannot be annotated: `event`, `schema_version`, `timestamp`, `name`, `outcome`, `duration_ms`, `trace_id`, `span_id`, `parent_span_id`, `span_count`, and everything under `service.`, `deployment.`, `instance.`, `runtime.`, `process.`, `db.`, `external.`, `slowest_span.`, `error.`, `sample.`, `telemetry.`.

## Privacy rules

The repository is public and the logs are kept for good. These are hard rules.

- **Allowed:** IDs (cuid), counts, amounts, durations, enum values, booleans, versions, dates of records.
- **Never:** a Client's or Stylist's name, phone, email, notes or any free text, address text, photo URLs, file keys, Clerk tokens, cookies, authorization headers, share tokens. For a share token record the Profile Share's id, or `hashPrefix(token)` as `profile_share.token_hash`.
- Lengths and presence of free text are fine: `client.notes_length: 42`, `client.has_phone: true`.
- When unsure, record a count or a boolean instead of the value.

The privacy guard in `@hcm/telemetry` enforces what a machine can check, so that a mistake does not leak:

- A key that contains a word naming personal data or contents is dropped: `name`, `phone`, `email`, `note(s)`, `comment`, `text`, `description`, `address`, `street`, `birthday`, `instagram`, `telegram`, `token`, `cookie`, `authorization`, `password`, `secret`, `key(s)`, `url(s)`, `uri`, `href`, `body`, `content`, `query`, `sql`, `header(s)`, and a few more (see `deniedWords` in `packages/telemetry/src/guard.ts`).
- Unless the key is a measure: it ends in `_count`, `_length`, `_size`, `_bytes`, `_ms`, `_total`, `_present`, `_changed`, `_valid` or starts its last part with `has_`/`is_`. Such a key only takes a number or a boolean. `_hash` takes a short hex string, `_kind`/`_type` a short string.
- Strings are cut to 200 characters (error messages 1000, stacks 6000), and anything that looks like an email address or a phone number is replaced with `[email]` or `[phone]`, also in error messages and stacks.
- What the guard drops is named in `telemetry.blocked_keys` (the key and why, never the value). An event with blocked keys is a bug to fix where the annotation is.

## Seeing the events

### Locally (dev backend)

Run the dev backend as `apps/android/README.md` describes, for instance on port 3100. Every request prints one `{"event":"hcm.request",…}` line; the server's start prints an `hcm.process` line.

```sh
PORT=3100 pnpm --filter @hcm/dev-backend start | tee /tmp/hcm-events.jsonl
curl -s http://127.0.0.1:3100/api/trpc/clientProfile.list -H "Authorization: Bearer dev_demo"
grep '^{"event":"hcm.request"' /tmp/hcm-events.jsonl | jq '{procedure: ."trpc.procedure", outcome, duration_ms, db: ."db.query_count"}'
```

`next start` prints the same lines for the web app. To see the spans themselves, run an OTLP collector and set `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`.

### In production

Vercel keeps runtime logs for an hour on the Hobby plan. A cron job on the maintainer's server copies them every 15 minutes into `~/hcm-logs/runtime/YYYY-MM-DD.jsonl` (see `~/hcm-logs/README.md`). Each record is Vercel's envelope, with the printed line in `message`:

```sh
cd ~/hcm-logs/runtime
# Every event of a day, parsed.
jq -c 'select(.message | startswith("{\"event\":\"hcm.")) | .message | fromjson' 2026-11-20.jsonl
# Failures, with their procedure and error.
jq -c '.message | select(startswith("{\"event\":\"hcm.request\"")) | fromjson | select(.outcome == "error") | {timestamp, p: ."trpc.procedure", e: ."error.type", m: ."error.message"}' *.jsonl
# The slowest procedures.
jq -r '.message | select(startswith("{\"event\":\"hcm.request\"")) | fromjson | [."duration_ms", ."trpc.procedure", ."db.query_count"] | @tsv' *.jsonl | sort -rn | head
```

Timestamps in events are UTC. On Vercel the spans are in the project's Traces page once a sampling rule is set (Settings → Tracing); search them by the same field names, such as `trpc.procedure:clientProfile.save`.

### Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `HCM_TELEMETRY` | on | `off` turns off events and export. |
| `HCM_TELEMETRY_EXPORT` | on | `off` keeps the events but exports no spans. |
| `HCM_TELEMETRY_SAMPLE_RATE` | `1` | Share of ordinary requests whose event is printed (0 to 1). Failures, refusals, slow requests and chosen Stylists are always printed. |
| `HCM_TELEMETRY_SLOW_MS` | `1000` | A request at least this long is always printed. |
| `HCM_TELEMETRY_KEEP_STYLISTS` | none | Comma-separated Stylist ids whose requests are always printed. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | none | Also export spans to this OTLP/HTTP collector. |

## Field catalog

`schema_version` is raised whenever a field changes its name, type or meaning. A field that does not apply is left out, not `null`. Types: `string`, `int`, `number` (may have decimals), `bool`, `string[]`, `object`.

### Every event

| Field | Type | Meaning |
| --- | --- | --- |
| `event` | string | `hcm.request` (an HTTP request), `hcm.task` (work with a root span of its own outside a request), `hcm.process` (the server process: start, failures), `hcm.error` (an error recorded where no event was running). Always the first field, so `message` starts with `{"event":"hcm.`. |
| `schema_version` | int | Version of this catalog. Now 1. |
| `timestamp` | string | When the request started (ISO 8601, UTC). For `hcm.process` and `hcm.error`, when it was printed. |
| `service.name` | string | `hcm-web` or `hcm-dev-backend`. |
| `deployment.env` | string | `VERCEL_ENV` (`production`, `preview`, `development`), else `production` under `NODE_ENV=production`, else `local`. |
| `deployment.id` | string | `VERCEL_DEPLOYMENT_ID`. |
| `deployment.region` | string | `VERCEL_REGION`, the region of the function. |
| `deployment.git_sha` | string | `VERCEL_GIT_COMMIT_SHA`. |
| `deployment.git_ref` | string | `VERCEL_GIT_COMMIT_REF`, the branch. |
| `runtime.node_version` | string | `process.version`. |
| `instance.id` | string | Random id of this server process (8 hex). Requests with the same id ran in the same instance. |

### Requests and tasks (`hcm.request`, `hcm.task`)

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | string | The root span's name: `POST /api/trpc/[trpc]`, `GET` for Next.js's proxy span, the `traced` name for a task. |
| `outcome` | string | `success`; `refused` (a 4xx or an expected error); `error` (a 5xx, an unexpected error, or a failed root span). |
| `duration_ms` | number | Time from the root span's start to its end. |
| `http.method` | string | Request method. |
| `http.route` | string | Route pattern (`/api/trpc/[trpc]`, `/profile-shares/[token]`), `proxy` for Next.js's proxy span. Never the path. |
| `http.status_code` | int | Status of the response. |
| `next.span_type` | string | Next.js's type of the root span, usually `BaseServer.handleRequest`. Web only. |
| `next.proxy` | bool | The event is Next.js's run of proxy.ts, which has a root span (a trace) of its own apart from the route's. |
| `trace_id` | string | OpenTelemetry trace id (32 hex): the same in Vercel's trace view and in an incoming `traceparent`. |
| `span_id` | string | The root span's id (16 hex). |
| `parent_span_id` | string | The caller's span id, when a `traceparent` came in. |
| `trace.continued` | bool | Whether the request continued an incoming `traceparent`. |
| `instance.request_seq` | int | The how-many-th request of this instance (requests only). |
| `instance.cold_start` | bool | The first request of this instance. |
| `instance.uptime_ms` | int | Age of the instance when the request started. |
| `span_count` | int | Child spans that ended during the request. |
| `db.query_count` | int | SQL statements Prisma sent (`prisma:client:db_query` spans). |
| `db.query_ms` | number | Their total time. |
| `db.slowest_query_ms` | number | The slowest one. |
| `db.operation_count` | int | Prisma Client operations (`prisma:client:operation`), such as one `findMany`. |
| `db.operations` | object | Operations by name: `{"Client.findMany": 2, "Stylist.upsert": 1, "executeRaw": 1}` (at most 40 names). |
| `db.transaction_count` | int | Prisma transactions. |
| `db.error_count` | int | Queries whose span failed. |
| `external.call_count` | int | Outgoing HTTP calls (`fetch` spans): UploadThing, Clerk, the release manifest. |
| `external.call_ms` | number | Their total time. |
| `external.slowest_ms` | number | The slowest one. |
| `external.error_count` | int | Calls that failed or answered 4xx/5xx. |
| `external.hosts` | string[] | Hosts called (never paths). |
| `slowest_span.name` | string | The slowest unit of work: `db:<operation>` for a query, `http:<host>` for a call, or a `traced` name. |
| `slowest_span.ms` | number | Its duration. |
| `sample.reason` | string | Why the event was kept: `error`, `refused`, `slow`, `stylist`, `rate`. |
| `sample.rate` | number | The chance it had to be kept: 1 except for `rate`, where it is `HCM_TELEMETRY_SAMPLE_RATE`. Weigh a count by `1 / sample.rate`. |
| `telemetry.blocked_keys` | string[] | Fields the privacy guard dropped, as `key:why` (`personal`, `reserved`, `value`, `too_deep`). |
| `telemetry.internal_errors` | int | Failures inside telemetry in this instance so far (swallowed). |
| `telemetry.truncated` | bool | The line was over 16,000 characters and was shortened (stack first, then the largest fields). |

### Errors (any event)

| Field | Type | Meaning |
| --- | --- | --- |
| `error.type` | string | The error's class or name: `TRPCError`, `PrismaClientKnownRequestError`, `TypeError`. |
| `error.message` | string | Its message, guarded and cut to 1000 characters. Never sent to the client. |
| `error.code` | string | Its code: a tRPC code for a refusal (`CONFLICT`), Prisma's for a database failure (`P2002`), Node's for a network failure (`ECONNRESET`). |
| `error.reason` | string | The domain's reason, from `recordError(…, { reason })`. |
| `error.retriable` | bool | Whether sending the request again could pass; set for known connection, timeout and conflict codes. |
| `error.expected` | bool | A refusal on purpose (`true`) or a failure (`false`). |
| `error.handled` | bool | A failure the code got over; the outcome did not change. |
| `error.cause_type`, `error.cause_message` | string | The error's `cause`. |
| `error.stack` | string | Stack of the error, then `Caused by:` and the cause's stack. Not kept for refusals. |
| `error.count` | int | Errors recorded during the request. The fields above describe the first failure, else the first refusal. |

### Process events (`hcm.process`)

| Field | Type | Meaning |
| --- | --- | --- |
| `process.phase` | string | `start` (telemetry set up, the instance starts), `telemetry_failed` (setting up failed; the server runs without telemetry), `uncaught_exception`, `unhandled_rejection` (the process is about to fail). |
| `instance.started_at` | string | When the instance started (ISO 8601, UTC). |
| `telemetry.init_ms` | number | Time setting up telemetry took. |
| `telemetry.export` | bool | Whether spans are exported. |
| `telemetry.sample_rate`, `telemetry.slow_ms` | number | The sampler's settings. |

### Request facts (web and dev backend)

| Field | Type | Meaning |
| --- | --- | --- |
| `request.id` | string | Vercel's request id (`x-vercel-id`), as in Vercel's logs. |
| `http.user_agent` | string | The `User-Agent`, cut to 120 characters. |
| `http.request_bytes` | int | `Content-Length` of the request. |
| `next.route_path`, `next.route_type`, `next.router_kind`, `next.render_source` | string | From Next.js's `onRequestError`: the route pattern and what failed (`render`, `route`, `action`, `proxy`). |
| `next.error_digest` | string | The digest Next.js shows in place of the error in a page. |

### tRPC (every procedure)

| Field | Type | Meaning |
| --- | --- | --- |
| `trpc.procedure` | string | The procedure's path: `clientProfile.save`. |
| `trpc.type` | string | `query` or `mutation`. |
| `trpc.input_bytes` | int | Size of the input as JSON. |
| `trpc.input_fields` | string[] | Names of the input's top-level fields (never their values). |
| `trpc.ok` | bool | Whether the procedure succeeded. |
| `trpc.error_code` | string | The tRPC code when it did not: `UNAUTHORIZED`, `BAD_REQUEST`, `INTERNAL_SERVER_ERROR`. |
| `trpc.duration_ms` | int | Time of the procedure, middlewares included. |
| `auth.signed_in` | bool | Whether the request carried a valid sign-in. |
| `auth.user_id` | string | The Clerk user id (`dev_<name>` on the dev backend). |
| `app.version_code` | int | `X-App-Version-Code` of the Android app. |
| `app.version` | string | `X-App-Version-Name`: `1.16.0`. |
| `stylist.id` | string | The Stylist of the request (`stylistProcedure` and below). |
| `stylist.just_created` | bool | The request created the Stylist (the first request of a new account). |
| `stylist.device_defaults_valid` | bool | Whether the device's language and timezone in the input were valid. |
| `tx.outcome` | string | `committed` or `rolled_back` (`stylistTransactionProcedure`). |
| `tx.begin_ms` | int | Time until the transaction had begun (waiting for a connection). |
| `tx.lock_wait_ms` | int | Time waiting for the Stylist's lock (ADR 0014). |
| `tx.duration_ms` | int | Time of the whole transaction. |
| `tx.unused_file_count` | int | Stored files of deleted Appointment Photos to delete after the commit. |

### Domain fields

Each area adds its own table here as it is instrumented: the field, its type and its meaning, in the same form as above. Keep the table next to the others for the same namespace.

<!-- Domain field tables go below this line, one heading per area (Appointments, Clients, Service Menu, Profile Shares, uploads, …). -->
