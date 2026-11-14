# Observability: one wide event per request

Every request the server handles prints **one line of JSON** to stdout when it ends: the request's *wide event*. It says what happened to the request: which procedure ran, for which Stylist, what the code decided and why, how many database queries it made and how long they took, and, when it failed or was refused, the error with its code and stack. The line is meant to be searched later by people and agents looking for slow requests, failures and things to correct. The decision and its alternatives are in [ADR 0019](adr/0019-wide-events-on-opentelemetry.md).

This document is the contract for everyone who adds to the event: how to add fields, what to call them, what must never be in them, and every field there is.

## How it works

The server is traced with OpenTelemetry. The request's **root span is the event**: Next.js opens a root span for every request it handles (`BaseServer.handleRequest`), and the dev backend opens one with `traceRequest`. An incoming `traceparent` header continues the caller's trace.

`@hcm/telemetry` installs a span processor (`WideEventProcessor`) next to the SDK. It sees every span start and end. A span with no parent in this process opens an event; every span below it belongs to that event. Which event a root span opens:

- `hcm.request`: a span of kind SERVER (Next.js's `BaseServer.handleRequest`, the dev backend's `traceRequest`), and the span in which Next.js runs proxy.ts (`next.span_type` `Middleware.execute`), which on Vercel is the root of a function of its own and of kind INTERNAL.
- None: any other span of Next.js's own (tracer `next.js`, or with a `next.span_type`), such as the `start response` that Next.js sometimes begins after the request's span ended. It is the framework's work outside any request and says nothing about one; the spans under it open no event either.
- `hcm.task`: anything else, such as work this app started with `traced` outside any request.

A span started under a request that already ended (a promise left running) adds nothing and opens nothing, and neither does any span started under it. Code anywhere in the call stack adds fields to the current request's event with `annotate`, which finds the event through the active OpenTelemetry context (AsyncLocalStorage), so nothing is passed around. Child spans (Prisma's queries, outgoing `fetch` calls, units of work wrapped in `traced`) are added up into the event: query count and time, operations by model, external calls, the slowest piece of work. When the root span ends, the event is built, the tail sampler decides whether to keep it, and it is printed with `console.log` as one line.

The same spans are exported where an exporter is set up: on Vercel through `@vercel/otel` to Vercel's own trace collection (Vercel Tracing, which the Hobby plan has, once a sampling rule is added under Settings → Tracing), and to any OTLP endpoint named in `OTEL_EXPORTER_OTLP_ENDPOINT`. With neither, nothing is exported and nothing breaks. The annotated fields are also set on the root span, so a trace viewer can search them.

The frameworks write attributes of their own that the privacy guard never sees, so the processor cleans **every** span as it ends (`packages/telemetry/src/spans.ts`), in `onEnding`, while the span can still be changed. The exporting processors of `@vercel/otel` only take spans in `onEnd`, which the SDK calls after every processor's `onEnding`, so an exporter always sees the cleaned span, whatever the order of the processors:

- The path of the request (`http.target`, `url.path`, `vercel.matched_path`) becomes the route pattern (`http.route`/`next.route`), or is removed when there is none (Next.js's proxy span); `url.query` is removed. Next.js puts the path with its query there (`/profile-shares/<token>?lang=en`).
- A whole address (`http.url`, `url.full`, `http.referer`, `resource.name`, and any other attribute whose value is an address) keeps only its scheme and host. The fetch instrumentations put the address of a stored photo there.
- An outgoing HTTP call (kind CLIENT with a method) is named `fetch <METHOD> <host>` (or `http …`), never `fetch GET <address>`; an address in any other span's name is cut to scheme and host. An event never shows the raw name of such a span.
- The message and stack of a recorded exception and the status message go through the same cleaning as `error.message` and `error.stack` (below), also when the framework recorded them.

Nothing in telemetry throws into a request. Every function swallows its own failures; `traced` runs its work exactly once even when tracing around it breaks; a failure inside telemetry is counted and reported in the next printed event as `telemetry.internal_errors`.

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

When working a field out reads data that may be missing (a row without a relation, a list that may be empty), pass a function that returns the fields. It runs inside `annotate`'s own try/catch, so a failure while computing leaves the fields out and is counted in `telemetry.internal_errors`, like any other telemetry failure; the request goes on. It is not called at all when no event is running, so the work is free in tests. Prefer it over a local `try`/`catch` wrapper.

```ts
annotate(() => ({ "appointment.client_count": appointment.participants.length, "appointment.note_length": appointment.note?.length ?? 0 }));
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
- Work that goes on after the response (a promise left running) adds nothing: the event was already printed. Spans it starts, and spans they start, are left out too.
- `annotate` before telemetry is set up, in a unit test or a script does nothing. Tests need no mocks.
- Never pass a whole input object or database row to `annotate`. Name the fields.

## Naming rules

- Keys are lower snake_case words joined by dots: `<namespace>.<field>`. camelCase given to `annotate` is turned into snake_case, but write snake_case.
- Namespaces are the domain's nouns from CONTEXT.md in snake_case: `stylist.`, `client.`, `appointment.`, `appointment_service.` (a Service on an Appointment; `service.` is reserved for the service that wrote the event), `service_menu.`, `photo.`, `color_formula.`, `family.`, `profile_share.`, `share_image.`, `release.`, `upload.`; and technical ones: `trpc.`, `http.`, `app.`, `auth.`, `tx.`, `next.`.
- The main record of the request is `<entity>.id`; a related one `<entity>.<other>_id` (`appointment.primary_client_id`); several are `<entity>.ids` (an array, at most 20) next to `<entity>.count`. IDs are written as the database has them, never prefixed or shortened.
- Units are in the name, never in the value: `_ms` (milliseconds), `_bytes`, `_count`, `_length` (characters), `_days`, `_minor` (an amount in minor currency units, as stored).
- Booleans read as a statement: `has_phone`, `is_recurring`, `just_created`, `ok`.
- Enum values and reasons are snake_case strings: `"overlaps"`, `"client_has_appointments"`.
- These are written by the event itself and cannot be annotated: `event`, `schema_version`, `timestamp`, `name`, `outcome`, `duration_ms`, `trace_id`, `span_id`, `parent_span_id`, `span_count`, and everything under `service.`, `deployment.`, `instance.`, `runtime.`, `process.`, `db.`, `external.`, `slowest_span.`, `error.`, `sample.`, `telemetry.`.

## Privacy rules

The repository is public and the logs are kept for good. These are hard rules.

- **Allowed:** IDs (cuid), counts, amounts, durations, enum values, booleans, versions, dates of records.
- **Never:** a Client's or Stylist's name, phone, email, notes or any free text, address text, photo URLs, file keys, Clerk tokens, cookies, authorization headers, share tokens. For a share token record the Profile Share's id, or `hashPrefix(token)` as `profile_share.token_hash`.
- Lengths and presence of free text are fine: `client.note_length: 42`, `client.has_phone: true`.
- When unsure, record a count or a boolean instead of the value.

The privacy guard in `@hcm/telemetry` enforces what a machine can check, so that a mistake does not leak:

- A key that contains a word naming personal data or contents is dropped: `name`, `phone`, `email`, `note(s)`, `comment`, `text`, `description`, `address`, `street`, `birthday`, `instagram`, `telegram`, `token`, `cookie`, `authorization`, `password`, `secret`, `key(s)`, `url(s)`, `uri`, `href`, `body`, `content`, `query`, `sql`, `header(s)`, and a few more (see `deniedWords` in `packages/telemetry/src/guard.ts`).
- Unless the key is a measure: it ends in `_count`, `_length`, `_size`, `_bytes`, `_ms`, `_total`, `_present`, `_changed`, `_valid` or starts its last part with `has_`/`is_`. Such a key only takes a number or a boolean. `_hash` takes a short hex string, `_kind`/`_type` a short string.
- Strings are cut to 200 characters (error messages 1000, stacks 6000), and anything that looks like an email address or a phone number is replaced with `[email]` or `[phone]`, also in error messages and stacks. A phone number is digits that stand apart from the words around them: with a `+` at least 8 digits; with spaces, hyphens or brackets at least 10; a bare run 10 to 12 (`89123456789`, `4155550123`); dotted only when grouped like `415.555.0123`. Digits joined to a word (`8k2xz-1731590000000-3f8a`, `Chrome/131.0.6778.135`), longer bare runs (millisecond timestamps) and versions are left alone. `request.id` and `http.user_agent`, which name the platform and the software, are only cut, never searched.
- Prisma prints the arguments of a call it refused in the error's message (a Client's name, phone, notes): between ``Invalid `prisma.client.create()` invocation:`` and the explanation at the end. In `error.message`, `error.stack`, `error.cause_message` and on exported spans that part is replaced with `[arguments removed]`; the call and the explanation are kept.
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
| `event` | string | `hcm.request` (an HTTP request, also Next.js's run of proxy.ts), `hcm.task` (work with a root span of its own outside a request that is not the framework's; see "How it works"), `hcm.process` (the server process: start, failures), `hcm.error` (an error recorded where no event was running). Always the first field, so `message` starts with `{"event":"hcm.`. |
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
| `name` | string | The root span's name: `POST /api/trpc/[trpc]`, the method (`GET`) for Next.js's proxy span (on Vercel too, where the span is named `middleware GET`), the `traced` name for a task. An outgoing call is named by its host (`fetch GET webfixture.ufs.sh`), never by its address. |
| `outcome` | string | `success`; `refused` (a 4xx or an expected error); `error` (a 5xx, an unexpected error, or a failed root span). |
| `duration_ms` | number | Time from the root span's start to its end. |
| `http.method` | string | Request method. |
| `http.route` | string | Route pattern (`/api/trpc/[trpc]`, `/profile-shares/[token]`), `proxy` for Next.js's proxy span. Never the path. |
| `http.status_code` | int | Status of the response. For proxy.ts, the status of what it answers (`405` for a refused method, `404` for a link that shows nothing, `200` when it passes the request on), which proxy.ts annotates: Next.js reports 200 for the proxy whatever it answers. A status that code annotates replaces the framework's, in the event and on the exported span. |
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
| `telemetry.internal_errors` | int | Failures inside telemetry (swallowed) since the previous printed event of this instance. A failure in an event that the sampler dropped is reported in the next one that is printed. |
| `telemetry.truncated` | bool | The line was over 16,000 characters and was shortened (stack first, then the largest fields). |

### Errors (any event)

| Field | Type | Meaning |
| --- | --- | --- |
| `error.type` | string | The error's name or class: `TRPCError`, `PrismaClientKnownRequestError`, `TypeError`, `ClerkAPIResponseError`. Its `name` when that says more than `Error`, else the name a class keeps in a static `kind` (Clerk's), else the class's name; a name the production build shortened (`a`, `e`) is never used, which leaves `Error`. |
| `error.message` | string | Its message, guarded and cut to 1000 characters, with the arguments Prisma prints removed. Never sent to the client. |
| `error.code` | string | Its code: a tRPC code for a refusal (`CONFLICT`), Prisma's for a database failure (`P2002`, or `errorCode` of a failure to connect, `P1001`), Clerk's (`api_response_error`, or the reason of a refused token, `token-expired`), Node's for a network failure (`ECONNRESET`). Else the cause's code. |
| `error.api_code` | string | The code of the first error in an API's answer, as Clerk's errors carry it (`errors[0].code`, such as `authentication_invalid`). |
| `error.http_status` | int | The HTTP status of the answer the error reports, as Clerk's errors carry it (`status`, such as `401`). |
| `error.reason` | string | The domain's reason, from `recordError(…, { reason })`. |
| `error.retriable` | bool | Whether sending the request again could pass; set for known connection, timeout and conflict codes. |
| `error.expected` | bool | A refusal on purpose (`true`) or a failure (`false`). |
| `error.handled` | bool | A failure the code got over; the outcome did not change. |
| `error.cause_type`, `error.cause_message` | string | The error's `cause`, named and cleaned like the error. |
| `error.stack` | string | Stack of the error, then `Caused by:` and the cause's stack, with the arguments Prisma prints removed. Not kept for refusals. |
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
| `request.id` | string | Vercel's request id (`x-vercel-id`), as in Vercel's logs. Recorded as it is (not searched for phone numbers). |
| `http.user_agent` | string | The `User-Agent`, cut to 120 characters. Recorded as it is (not searched for phone numbers). |
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
| `stylist.just_created` | bool | The request created the Stylist (the first request of a new account). Two first requests of a new account that overlap are both `true`: the upsert's answer does not tell an insert from an update. |
| `stylist.device_defaults_valid` | bool | Whether the device's language and timezone in the input were valid. |
| `tx.outcome` | string | `committed` or `rolled_back` (`stylistTransactionProcedure`). |
| `tx.begin_ms` | int | Time until the transaction had begun (waiting for a connection). |
| `tx.lock_wait_ms` | int | Time waiting for the Stylist's lock (ADR 0014). |
| `tx.duration_ms` | int | Time of the whole transaction. |
| `tx.unused_file_count` | int | Stored files of deleted Appointment Photos to delete after the commit. |

### Domain fields

What the code adds to the event for each part of the domain. Rows are sorted by namespace within an area. A key that two areas write is listed once, with "Also written by" where it applies. Only IDs, counts, lengths, enum values, booleans and amounts are recorded here, never a name, phone number, address, note or other free text. Appointments, Clients, Service Menu and the rest are written by `packages/api`; Profile Share pages, the proxy and uploads by `apps/web` (and `packages/api` helpers).

#### Appointments

| Field | Type | Meaning |
| --- | --- | --- |
| `appointment.changed_field_count` | int | Their number. `update`. |
| `appointment.changed_fields` | string[] | Fields the save changed: `primary_client`, `status`, `starts_at`, `ends_at`, `location_type`, `location_address`, `note`, `total_override`, `tip`. Empty for a no-op save. `update`. |
| `appointment.client_count` | int | Its Clients. The Appointment as the reply shows it; in `removeParticipant`, before the removal. Also written by `authorizePhotoUpload` (Profile Shares). |
| `appointment.color_formula_count` | int | Color Formulas on all its Services. The Appointment as the reply shows it. |
| `appointment.conflict_candidate_count` | int | Scheduled Appointments read for the overlap check: every one that starts before this one ends, past unresolved ones included, so it grows with them. `create`, `update`. |
| `appointment.conflict_count` | int | Scheduled Appointments it overlaps (the overlap warning). `create`, `update`. |
| `appointment.conflict_ids` | string[] | Their ids (at most 20). `create`, `update`. |
| `appointment.copy_default_source_id` | string | The first one listed: the source `copyServices` takes when none is named. `completedSources` with a target. |
| `appointment.copy_source_age_days` | number | Days since the source started: how long ago the copied visit was. `copyServices`. |
| `appointment.copy_source_choice` | string | `named` (the Stylist picked the source) or `latest` (the last Completed Appointment with something to copy). `copyServices`. |
| `appointment.copy_source_id` | string | The source Appointment. `copyServices`. |
| `appointment.copy_source_service_count` | int | Its Services, of all its Clients. `copyServices`. |
| `appointment.copy_source_status` | string | Its status (a named source that is no longer Completed is refused). `copyServices`. |
| `appointment.count` | int | Appointments read and returned. `list`, `home`, `completedSources`. |
| `appointment.delete_result` | string | `deleted`, or `not_found` (already deleted, such as a delete sent again, or not the Stylist's; the reply is the same). `delete`. |
| `appointment.dropped_client_id_count` | int | Group Client ids left out because they were sent twice or repeat the primary Client. `create`. |
| `appointment.duration_minutes` | int | End minus start, when it has an end time. The Appointment as the reply shows it. |
| `appointment.final_total_minor` | int | The custom total, else the sum: the price of its Services, without the Tip. The Appointment as the reply shows it. |
| `appointment.has_copy_target` | bool | The Appointment to copy into was named, so only sources with something to copy are listed. `completedSources`. |
| `appointment.has_custom_address` | bool | At home, at an address the Stylist typed rather than the primary Client's. The Appointment as the reply shows it. |
| `appointment.has_end_time` | bool | It has an end time. The Appointment as the reply shows it. |
| `appointment.has_location_address` | bool | The new Appointment has an address at all (an empty salon address or a Client without one leaves it empty). `create`. |
| `appointment.has_total_override` | bool | A custom total replaces the sum. The Appointment as the reply shows it. |
| `appointment.id` | string | The Appointment the request is about: from the input as soon as the procedure starts (so a refusal has it), else from the row read. For `copyServices` and `completedSources`, the Appointment copied into. Every procedure except `list` and `home`. Also written by `authorizePhotoUpload`, `onUploadComplete` (Profile Shares). |
| `appointment.is_address_saved_to_client` | bool | When saving the typed address to the primary Client was asked: whether it was (false: that Client already had an address). `create`, `update`. |
| `appointment.location_save_result` | string | Why the typed address was or was not saved to the primary Client: `saved`, `no_address`, `client_has_address`, `client_not_found` (`saveAddressToClient`). `create`, `update`. |
| `appointment.is_back_to_scheduled` | bool | An Appointment with an outcome was set back to Scheduled. `update`. |
| `appointment.is_noop_save` | bool | The save changed nothing (such as the same form saved twice); the row is still written. `update`. |
| `appointment.is_primary_client_changed` | bool | Removing the Client made another Client primary. `removeParticipant`. |
| `appointment.is_repeat` | bool | The request key matched an Appointment created before: the same request sent again, answered with that Appointment. Only when a request key was sent. `create`. |
| `appointment.listed_client_count`, `appointment.listed_service_count`, `appointment.listed_color_formula_count`, `appointment.listed_photo_count` | int | Clients, Services, Color Formulas and photos in all of them: what the reply's size follows. `list`, `home`, `completedSources`. |
| `appointment.location_source` | string | Where the saved address came from: `salon`, `typed`, `typed_saved_to_client` (typed and saved as the primary Client's address), `client` (the primary Client's), `unchanged` (`update` only: the save kept it). `create`, `update`. |
| `appointment.location_type` | string | `in_salon` or `at_home`. The Appointment as the reply shows it. |
| `appointment.menu_service_count` | int | Its Services started from a Service Menu Item. The Appointment as the reply shows it. |
| `appointment.note_length` | int | Characters of its note (0 without one). The Appointment as the reply shows it. |
| `appointment.overlap_check` | string | `clear`, `overlaps`, or `skipped` (`update`: not Scheduled, or neither moved nor set back to Scheduled). `create`, `update`. |
| `appointment.pending_photo_count`, `appointment.failed_photo_count` | int | Its photos waiting for upload, and failed. Where the reply has photos. |
| `appointment.photo_count` | int | Its Appointment Photos, where the reply has photos; in `delete`, the photos deleted with it. |
| `appointment.previous_status` | string | The status before the save. `update`. With `appointment.status` it gives the move (`scheduled` to `completed`). |
| `appointment.primary_client_id` | string | Its primary Client, as the reply shows it; in `create` also a Client just created inline; in `removeParticipant` the primary Client after the removal. |
| `appointment.primary_client_source` | string | `existing` (picked) or `inline` (a minimal Client created with the Appointment). `create`. |
| `appointment.range_days` | number | Length of the requested date range in days. `list`, `home`. |
| `appointment.service_count` | int | Its Services. The Appointment as the reply shows it. |
| `appointment.service_total_before_minor` | int | Sum of the Service prices before a change that adds or reprices Services. `addService`, `addMenuServices`, `updateService`, `copyServices`. |
| `appointment.service_total_change_minor` | int | How much the change raises (or, negative, lowers) that sum. Same procedures. With the one above it explains `appointment_total_above_maximum`. |
| `appointment.service_total_minor` | int | Sum of its Service prices. The Appointment as the reply shows it. |
| `appointment.start_local_hour` | int | Hour (0 to 23) it starts at in the Stylist's timezone: starts from 21 to before 6 are likely AM/PM mix-ups. `create`; `update` when `startsAt` was sent. |
| `appointment.starts_in_days` | number | Days from now to its start, to a tenth; negative for a visit written down afterwards. `create`; `update` when `startsAt` was sent. |
| `appointment.status` | string | `scheduled`, `completed`, `canceled`, `no_show`. The Appointment as the reply shows it. Also written by `authorizePhotoUpload` (Profile Shares). |
| `appointment.stored_photo_count` | int | Photos of a deleted Appointment that had a stored file. `delete`. |
| `appointment.tip_minor` | int | Its Tip, when it has one. The Appointment as the reply shows it. |
| `appointment.travel_fee_change` | string | What the Travel Fee did: `added`, `removed`, `none` (looked at, nothing changed: no Travel Fee item, one already there, or the total would be too large), `not_applicable` (the save cannot change it). `create`, `update`. |
| `appointment.travel_fee_minor` | int | Their prices together. `create`, `update`. |
| `appointment.travel_fee_service_count` | int | Services the Travel Fee added or removed. `create`, `update`. |
| `appointment.unresolved_count` | int | Of them, past Appointments still Scheduled (waiting for an outcome). `home`. |
| `appointment_service.added_minor` | int | Default prices of the Services added, together. `addMenuServices`. |
| `appointment_service.changed_fields` | string[] | Fields the edit changed: `name`, `price`, `note`. `updateService`. |
| `appointment_service.client_id` | string | The Client it is for. `addService`, `updateService`, `deleteService`. |
| `appointment_service.color_formula_count` | int | Color Formulas the Service had before this one. `addColorFormula`. |
| `appointment_service.copied_client_count` | int | Clients they are for. `copyServices`. |
| `appointment_service.copied_count` | int | Services copied (for a request sent again, the ones the first one copied). `copyServices`. |
| `appointment_service.copied_minor` | int | Their prices together. `copyServices`. |
| `appointment_service.id` | string | The Service: created (`addService`), edited (`updateService`), deleted (`deleteService`), or given a Color Formula (`addColorFormula`, `updateColorFormula`, `deleteColorFormula`). |
| `appointment_service.is_menu_price_changed`, `appointment_service.is_menu_name_changed` | bool | Started from an item, with a price or name other than the item's. `addService`. |
| `appointment_service.is_noop_save` | bool | The edit changed nothing. `updateService`. |
| `appointment_service.is_repeat` | bool | The request key matched Services added before: the request sent again. Only when a request key was sent. `addService`, `addMenuServices`, `copyServices`. |
| `appointment_service.is_travel_fee` | bool | Its item is the Travel Fee. `addService` with an item. |
| `appointment_service.menu_item_count` | int | Distinct Service Menu Items picked. `addMenuServices`. |
| `appointment_service.menu_item_id` | string | Its Service Menu Item, when it has one. `addService`, `updateService`, `deleteService`, `addColorFormula`. |
| `appointment_service.menu_item_ids` | string[] | Their ids (at most 20): which items are added together. `addMenuServices`. |
| `appointment_service.name_length`, `appointment_service.note_length` | int | Characters of its name (when sent) and note. `addService`, `updateService`. |
| `appointment_service.pick_client_count` | int | Clients they are for. `addMenuServices`. |
| `appointment_service.pick_count` | int | Services asked for. `addMenuServices`. |
| `appointment_service.picks_kind` | string | Form of the input: `one_client` (clientId and menuItemIds) or `clients` (several Clients at once). `addMenuServices`. |
| `appointment_service.previous_price_minor` | int | Its price before the edit. `updateService`. |
| `appointment_service.price_minor` | int | Its price (after the edit for `updateService`). `addService`, `updateService`, `deleteService`. |
| `appointment_service.price_source` | string | `sent` (the app sent a price), `menu_default` (the item's default price), `none` (neither: 0). `addService`. |
| `appointment_service.source` | string | `menu` (started from a Service Menu Item) or `ad_hoc`. `addService`, `deleteService`. |
| `appointment_service.travel_fee_count` | int | Of them, Services from the Travel Fee item. `addMenuServices`. |
| `client.appointment_service_count`, `client.appointment_photo_count` | int | The Client's Services and photos in the Appointment (removal is refused when either is above 0). `removeParticipant`. |
| `client.id` | string | The Client added to or removed from the Appointment. `addParticipant`, `removeParticipant`. Also written by every procedure except `list`, `visitDates` (Clients). Also written by `findShareLink` (Profile Shares). Also written by `authorizePhotoUpload`, `onUploadComplete` (Profile Shares). |
| `color_formula.changed_fields` | string[] | `formula`, `placement`. `updateColorFormula`. |
| `color_formula.copied_count` | int | Color Formulas copied with them. `copyServices`. |
| `color_formula.formula_length` | int | Characters of the formula text. `addColorFormula`, `updateColorFormula`. |
| `color_formula.has_placement` | bool | It has a placement. `addColorFormula`, `updateColorFormula`. |
| `color_formula.id` | string | The Color Formula created, edited or deleted. `addColorFormula`, `updateColorFormula`, `deleteColorFormula`. |
| `color_formula.is_noop_save` | bool | The edit changed nothing. `updateColorFormula`. |
| `color_formula.is_repeat` | bool | The request key matched a formula added before. Only when a request key was sent. `addColorFormula`. |
| `photo.category` | string | `before`, `after`, `other` (after the edit for `updatePhoto`). Same procedures. Also written by `authorizePhotoUpload`, `onUploadComplete` (Profile Shares). |
| `photo.changed_fields` | string[] | `category`, `status`. `updatePhoto`. |
| `photo.client_id` | string | The Client it is attached to. Same procedures. |
| `photo.has_file_key`, `photo.has_url`, `photo.has_thumbnail_url` | bool | The app sent the stored file's key, URL and thumbnail URL (never their values). `addPhoto`; `photo.has_file_key` also `deletePhoto` (a stored file to delete after the commit). |
| `photo.id` | string | The photo created, edited or deleted. `addPhoto`, `updatePhoto`, `deletePhoto`. |
| `photo.is_noop_save` | bool | The edit changed nothing. `updatePhoto`. |
| `photo.is_repeat` | bool | A photo of this Appointment already has the request key or the file: the request sent again. `addPhoto`. |
| `photo.previous_category`, `photo.previous_status` | string | Before the edit: with the two above, how uploads move from `pending_upload` to `stored` or `failed`. `updatePhoto`. |
| `photo.repeat_kind` | string | How the repeat was recognised: `request_key` or `file`. `addPhoto`. |
| `photo.status` | string | `pending_upload`, `stored`, `failed` (after the edit for `updatePhoto`). Same procedures. |
| `photo.width`, `photo.height` | int | Pixels, when sent. `addPhoto`. |

#### Clients, Families and Client Merge

| Field | Type | Meaning |
| --- | --- | --- |
| `client.appointment_count` | int | Appointments the Client was in or primary Client of. Written by `delete`. |
| `client.appointment_deleted_count` | int | Of them, deleted with the Client (it was their only Client). Written by `delete`. |
| `client.appointment_kept_count` | int | Of them, kept for their other Clients. Written by `delete`. |
| `client.appointment_primary_changed_count` | int | Of those kept, ones where another Client became primary. Written by `delete`. |
| `client.changed_field_count` | int | Their number. Written by `save` (update). |
| `client.changed_fields` | string[] | Details the update changes, by name only: `name`, `language`, `phone`, `email`, `address`, `note`, `referrer`. Empty for a no-op save. Written by `save` (update). |
| `client.count` | int | Clients answered: in the Clients View (`list`), or Clients with a last or next visit (`visitDates`). Written by `list`, `visitDates`. |
| `client.delete_result` | string | `deleted`, or `not_found` (already deleted, such as a delete sent again, or not the Stylist's; the reply is the same). Written by `delete`. |
| `client.deleted_count` | int | Client rows the delete removed (0 or 1). Written by `delete`. |
| `client.distinct_pair_result` | string | `created`, or `existing` (said again, nothing changes). Written by `markDistinct`. |
| `client.found_count` | int | How many of the two Clients were found, when one was not (refused with `client_not_found`). Written by `markDistinct`. |
| `client.has_expected_updated_at` | bool | The form sent the `updatedAt` it was loaded with (ADR 0015); `save.freshness` from `input.ts` says how it compared. Written by `save`. |
| `client.has_family` | bool | The Client is in a Family (`family`); the deleted Client was (`delete`). Written by `family`, `delete`. |
| `client.has_phone`, `client.has_email`, `client.has_address` | bool | The saved Client has the detail (not blank). Written by `save`. |
| `client.has_request_key` | bool | A request key was sent (a new Client). Written by `save`. |
| `client.has_search` | bool | The list was searched. Written by `list`. |
| `client.history_appointment_count` | int | Appointments in the Client Profile's history (every Appointment the Client is in). Written by `appointmentHistory`. |
| `client.history_color_formula_count` | int | Color Formulas on those Services. Written by `appointmentHistory`. |
| `client.history_completed_count`, `client.history_scheduled_count` | int | Of them, Completed and Scheduled. Written by `appointmentHistory`. |
| `client.history_group_count` | int | Of them, group Appointments (more than one Client). Written by `appointmentHistory`. |
| `client.history_photo_count` | int | Stored Appointment Photos of this Client in the reply. Written by `appointmentHistory`. |
| `client.history_service_count` | int | Services of this Client sent in the reply. Written by `appointmentHistory`. |
| `client.history_service_read_count` | int | Services read, those of other Clients of group Appointments included (the visit's total needs them). Written by `appointmentHistory`. |
| `client.is_repeat` | bool | Whether the request key matched a Client created before. Only when a request key was sent. Written by `save`. |
| `client.language` | string | The Client's language as saved (`ru`/`en`). Written by `save`. |
| `client.language_source` | string | `sent`, or `stylist` (not sent: the Stylist's language). Written by `save`. |
| `client.name_length` | int | Characters of the saved name. Written by `save`. |
| `client.note_length` | int | Characters of the Client Note as saved (`save`), or of the kept Client's note after the merge (`merge`; above the limit it is refused with `merged_note_too_long`). Written by `save`, `merge`. |
| `client.other_client_id` | string | The other Client said to be a different person. Written by `markDistinct`. |
| `client.photo_count` | int | The Client's Appointment Photos, deleted with them (their files: `photo.unused_file_count`). Written by `delete`. |
| `client.referred_by_id` | string | The Referrer sent, when one is. Written by `save`. |
| `client.referrer_change` | string | What the save does to the Referrer: `not_stated` (left as it is), `unchanged`, `set` (none before), `changed`, `cleared`; for a new Client `set` or `none`. Written by `save`. |
| `client.save_kind` | string | `create` (no id sent) or `update`. Written by `save`. |
| `client.save_result` | string | `created`; `repeat` (the request key matched a Client created before: the same request sent again, answered with that Client, or refused with `changed_repeat` when its values differ); `updated`; `noop` (an update that changes nothing, such as the same form saved twice; the row is still written). Written by `save`. |
| `client.search_length` | int | Characters of the search (trimmed), when there is one. Written by `list`. |
| `client.visit_row_count` | int | Appointment rows (one per Client of a completed or upcoming Appointment) read to answer one date per Client: the cost of `visitDates`, which grows with the Stylist's history. Written by `visitDates`. |
| `client.with_distinct_pairs_count` | int | Listed Clients said to be different from another Client whose phone number matches. Written by `list`. |
| `client.with_family_count` | int | Listed Clients in a Family. Written by `list`. |
| `client.with_last_visit_count`, `client.with_next_appointment_count` | int | Clients answered with a last visit, and with an upcoming Scheduled Appointment. Written by `visitDates`. |
| `client.with_profile_share_count` | int | Listed Clients with an active Profile Share. Written by `list`. |
| `client.with_referrer_count` | int | Listed Clients with a Referrer. Written by `list`. |
| `client_merge.appointment_count` | int | Appointments the removed Client was in or primary Client of. Written by `merge`. |
| `client_merge.appointment_ids` | string[] | Their ids (at most 20). Written by `merge`. |
| `client_merge.distinct_pair_count` | int | Pairs of "different people" the removed Client was in. Written by `merge`. |
| `client_merge.distinct_pair_moved_count` | int | Of them, recorded anew for the kept Client (not with the kept Client themselves, and not one they had already). Written by `merge`. |
| `client_merge.earlier_merge_count` | int | Earlier merges into the removed Client, now merges into the kept one. Written by `merge`. |
| `client_merge.family_merge` | string | `none`, `kept_family` (only the kept Client had one), `joined_removed_family` (the kept Client takes the removed one's Family and role), `same_family`, `families_combined` (the removed Client's Family goes into the kept one's). Written by `merge`. |
| `client_merge.family_moved_client_count` | int | Clients moved from the removed Client's Family into the kept one's (`families_combined`). Written by `merge`. |
| `client_merge.is_note_combined` | bool | Both had a Client Note, different: the removed one's is added under the kept one's. Written by `merge`. |
| `client_merge.is_note_taken` | bool | Only the removed Client had a Client Note: the kept Client takes it. Written by `merge`. |
| `client_merge.kept_family_id`, `client_merge.removed_family_id` | string | The two Clients' Families before the merge, when they had one. `family.*` from `dissolveIfAlone` afterwards describes the removed Client's Family. Written by `merge`. |
| `client_merge.kept_has_profile_share` | bool | The kept Client has an active Profile Share (so the removed Client's stops working). Written by `merge`. |
| `client_merge.participant_added_count` | int | Ones that named the removed Client as primary without listing them: the kept Client is added. Written by `merge`. |
| `client_merge.participant_moved_count` | int | Ones where the kept Client takes the removed Client's place. Written by `merge`. |
| `client_merge.primary_changed_count` | int | Ones where the kept Client becomes primary. Written by `merge`. |
| `client_merge.primary_past_at_home_count` | int | Of those, ones that happened at the removed Client's home: the address is kept as one of its own. Written by `merge`. |
| `client_merge.primary_past_kept_place_count` | int | Of those, ones that happened in the salon or at a typed address: the place stays. Written by `merge`. |
| `client_merge.primary_scheduled_count` | int | Of those, Scheduled ones (the rule for any change of primary Client; at home it may move to the kept Client's address). Written by `merge`. |
| `client_merge.profile_share_moved_count` | int | The removed Client's Profile Shares (revoked ones too) moved to the kept Client. Written by `merge`. |
| `client_merge.profile_share_revoked_count` | int | The removed Client's active Profile Shares revoked for it. Written by `merge`. |
| `client_merge.referral_moved_count` | int | Clients the removed Client recommended, now recommended by the kept one. Written by `merge`. |
| `client_merge.referrer_source` | string | The kept Client's Referrer after the merge: `kept` (their own), `removed` (taken from the removed Client), `none`. Written by `merge`. |
| `client_merge.removed_client_id` | string | The Client merged into `client.id` and deleted. Written by `merge`. |
| `client_merge.result` | string | `merged`, or `repeat` (the same merge sent again: the removed Client is gone and the merge is recorded; answered with the kept Client as it is now, ADR 0015). Written by `merge`. |
| `client_merge.service_moved_count`, `client_merge.photo_moved_count` | int | Services and Appointment Photos moved to the kept Client. Written by `merge`. |
| `client_merge.shared_appointment_count` | int | Of them, ones both Clients were in: the removed Client's place is dropped. Written by `merge`. |
| `client_merge.taken_fields` | string[] | Details the kept Client takes from the removed one because they had none: `phone`, `email`, `address`. Written by `merge`. |
| `family.add_path` | string | `same_family` (already together: at most the role changes), `joined_client_family` (the member joins the Client's Family, leaving their own), `joined_member_family` (the Client joins the member's), `new_family`. Written by `addFamilyMember`. |
| `family.added_client_id` | string | The Client added to the Family (`memberId`). Written by `addFamilyMember`. |
| `family.changed_client_count` | int | Clients the change wrote (with those taken out of a Family that was deleted). Written by `addFamilyMember`, `leaveFamily`. |
| `family.id` | string | The Family the request is about: the Client's (`family`, `setFamilyRole`), the one the two are in after the change (`addFamilyMember`, written after `leaveFamily`'s own). In `leaveFamily`, `merge` and `delete` it is written by `family.ts`. Written by `family`, `addFamilyMember`, `setFamilyRole`. Also written by `dissolveIfAlone` (Service Menu). |
| `family.is_noop` | bool | The same call sent again: nothing changed. Written by `addFamilyMember`. |
| `family.is_role_changed` | bool | The member's role changes. Written by `addFamilyMember`. |
| `family.is_role_stated` | bool | A role was sent. Written by `addFamilyMember`. |
| `family.leave_result` | string | `left`, or `not_in_family` (nothing to leave, such as the call sent again). Written by `leaveFamily`. |
| `family.member_count` | int | Clients in the Family (`family`). Written by `family`. |
| `family.member_previous_family_id` | string | The other Family the member leaves to join the Client's. Written by `addFamilyMember`. |
| `family.reply_client_count` | int | Clients the reply sends back as the list states them. Written by `addFamilyMember`, `setFamilyRole`, `leaveFamily`. |
| `family.role_change` | string | `set`, `changed`, `cleared`, `unchanged`. Never the role. Written by `setFamilyRole`. |
| `family.role_length` | int | Characters of the new role (0: none). Written by `setFamilyRole`. |
| `family.visit_count` | int | Visits (Completed Appointments) of all members together. Written by `family`. |
| `family.with_role_count` | int | Members with a role. Written by `family`. |
| `profile_share.changed_option_count` | int | Their number. Written by `updateProfileShare`. |
| `profile_share.changed_options` | string[] | Choices the update changes: `include_color_formulas`, `include_after_photos`, `include_other_photos`. Empty: the same choices sent again. Written by `updateProfileShare`. |
| `profile_share.create_result` | string | `created`, or `existing` (the Client has an active one: the request sent again, answered with it as it is). Written by `createProfileShare`. |
| `profile_share.id` | string | The Client's active Profile Share: the one created, answered, changed, or whose choices a Share Image follows. Written by `createProfileShare`, `updateProfileShare`, `buildProfileShareImage`. Also written by `findShareLink` (Profile Shares). |
| `profile_share.include_color_formulas`, `.include_after_photos`, `.include_other_photos` | bool | What the share shows after the request. `loadProfileShareContent` writes them for a Share Image. Written by `createProfileShare`, `updateProfileShare`. Also written by `loadProfileShareContent` (Profile Shares). |
| `profile_share.language` | string | Language of the created share: the Client's (`ru`/`en`). Written by `createProfileShare`. Also written by `findShareLink`, page, image route, `unavailableShareResponse` (Profile Shares). |
| `profile_share.revoked_count` | int | Active Profile Shares revoked (0: revoked already, such as a revoke sent again). Written by `revokeProfileShare`. |
| `profile_share.stated_option_count` | int | Choices of what the share shows that the request states (0 to 3); the rest are defaults (create), kept (update) or the share's (Share Image). Written by `createProfileShare` (created), `updateProfileShare`, `buildProfileShareImage`. |
| `share_image.has_profile_share` | bool | The Client has an active Profile Share whose choices the Share Image follows; without one, unstated choices are the defaults. Written by `buildProfileShareImage`. |

#### Service Menu, stats, Stylist settings and shared helpers

| Field | Type | Meaning |
| --- | --- | --- |
| `family.left_client_id`, `family.had_role` | string, bool | The Client taking leave and whether they had a role. Written by `leaveFamily`. |
| `family.remaining_member_count`, `.dissolved`, `.released_client_count` |  | The Family a helper looked at, the members left in it, whether it was deleted, Clients released. Written by `dissolveIfAlone`. |
| `release_list.has_download_url` | bool | The reply names a download. Written by `appRelease.releases`. |
| `release_list.published_source` | string | `not_configured`, `unknown`, `none`, `published`. Written by `appRelease.releases`. |
| `release_list.up_to_version_code` | int | Newest release offered. Written by `appRelease.releases`. |
| `request.repeat` | string | A repeated creation: `same` (answered like the first) or `changed` (refused). Written by `refuseChangedRepeat`. |
| `request.repeat_changed_fields`, `request.repeat_changed_field_count` | string[], int | Which input fields differed (names only). Written by `refuseChangedRepeat`. |
| `request.repeat_entity` | string | Entity of the key, as the event names it: `appointment`, `appointment_service`, `photo`, `color_formula`, `client`, `service_menu`. Written by `refuseChangedRepeat`. |
| `save.freshness` | string | A save with `expectedUpdatedAt`: `current`, `outdated` (refused), `same_values` (old form, no change, passes). Written by `refuseOutdatedSave`. |
| `service_menu.changed_fields` | string[] | `name`, `price`, `group`, `travel_fee` that an edit changed. Written by `serviceMenu.save`. |
| `service_menu.delete_result` | string | `deleted` or `not_found`. Written by `serviceMenu.delete`. |
| `service_menu.is_formula_list_truncated` | bool | The 1000-row limit cut the list. Written by `serviceMenu.formulaNames`. |
| `service_menu.formula_service_count`, `.formula_name_count` | int | Services read with a Color Formula; distinct names. Written by `serviceMenu.formulaNames`. |
| `service_menu.group_input` | string | `unchanged` (left out), `set`, `cleared`. Written by `serviceMenu.save`. |
| `service_menu.has_request_key` | bool | The save carried a request key. Written by `serviceMenu.save`. |
| `service_menu.id` | string | The item. Written by `serviceMenu.save`, `.delete`. |
| `service_menu.is_empty` | bool | No items. Written by `serviceMenu.list`. |
| `service_menu.is_new` | bool | Save without an id. Written by `serviceMenu.save`. |
| `service_menu.is_noop_save` | bool | An edit changed nothing. Written by `serviceMenu.save`. |
| `service_menu.is_travel_fee` | bool | The saved item is the Travel Fee. Written by `serviceMenu.save`. |
| `service_menu.item_count`, `.group_count`, `.grouped_item_count`, `.travel_fee_item_count`, `.used_item_count`, `.formula_item_count` | int | The Stylist's Service Menu: items, distinct groups, items in a group, Travel Fee items, items used by a Service, items with a Color Formula. Written by `serviceMenu.list`. |
| `service_menu.name_length` | int | Characters in the name. Written by `serviceMenu.save`. |
| `service_menu.price_minor`, `service_menu.price_before_minor` | int | Default price as sent; before an edit. Written by `serviceMenu.save`. |
| `service_menu.save_result` | string | `created`, `updated`, `repeat_returned_first`. Written by `serviceMenu.save`. |
| `service_menu.travel_fee_input` | string | `unchanged`, `marked`, `unmarked`. Written by `serviceMenu.save`. |
| `service_menu.unmarked_travel_fee_count` | int | Other items that lost the Travel Fee mark. Written by `unmarkOtherTravelFees`. |
| `stats.at_home_count`, `stats.custom_total_count`, `stats.tip_count` | int | Completed Appointments at home, with a custom total, with a Tip. Written by `stats.summary`. |
| `stats.completed_count`, `stats.canceled_count`, `stats.no_show_count`, `stats.scheduled_count`, `stats.booked_count`, `stats.unresolved_count` | int | Appointments of the period by outcome; booked = scheduled from now, unresolved = scheduled with a start in the past. Written by `stats.summary`. |
| `stats.earned_minor`, `stats.services_minor`, `stats.tips_minor`, `stats.booked_minor`, `stats.unresolved_minor` | int | Amounts in minor units. Written by `stats.summary`. |
| `stats.has_date` | bool | The request named a day (else today's period). Written by `stats.summary`. |
| `stats.is_current_period` | bool | The period holds now. Written by `stats.summary`. |
| `stats.is_empty` | bool | Nothing at all in the period. Written by `stats.summary`. |
| `stats.menu_item_count`, `stats.grouped_item_count` | int | Distinct Service Menu items in the lines; those with a group. Written by `stats.summary`. |
| `stats.period` | string | `week`, `month` or `year`. Written by `stats.summary`. |
| `stats.period_offset_days` | int | Days from now to the period's start (negative: past). Written by `stats.summary`. |
| `stats.range_days` | int | Days the period covers. Written by `stats.summary`. |
| `stats.service_line_count` | int | Service lines aggregated (also on span `stats.aggregate_period`, with `stats.appointment_count`). Written by `stats.summary`. |
| `stats.top_service_count` | int | Entries in the top list (at most 5). Written by `stats.summary`. |
| `stylist.changed_fields` | string[] | `language`, `timezone`, `salon_address`. Written by `stylist.saveSettings`. |
| `stylist.has_expected_updated_at` | bool | The form sent `expectedUpdatedAt`. Written by `stylist.saveSettings`. |
| `stylist.is_noop_save` | bool | Nothing changed. Written by `stylist.saveSettings`. |
| `stylist.is_onboarded`, `stylist.has_salon_address`, `stylist.photo_storage_configured` | bool | Onboarding done; a salon address is set; photo storage exists on the server. Written by `stylist.bootstrap`. |
| `stylist.language`, `stylist.timezone` | string | Settings now (enums/IANA names). Written by `stylist.bootstrap`, `.saveSettings`. |
| `stylist.onboarding_completed_now` | bool | This save completed the onboarding. Written by `stylist.saveSettings`. |
| `stylist.salon_address_length` | int | Length of the saved address. Written by `stylist.saveSettings`. |
| `stylist.salon_appointment_updated_count` | int | Scheduled in-salon Appointments given the new address. Written by `stylist.saveSettings`. |
| `travel_fee.decision` | string | `added`, `removed`, `no_item` (no Travel Fee item), `already_present`, `total_too_large`, `none_to_remove`. Written by `addTravelFee`, `removeTravelFee`. |
| `travel_fee.item_id`, `travel_fee.item_price_minor`, `travel_fee.removed_count` | string, int, int | The item, its price, Services removed. Written by `addTravelFee`, `removeTravelFee`. |

#### Profile Shares, Share Images, uploads, pages, the proxy and releases

| Field | Type | Meaning |
| --- | --- | --- |
| `app.version_changed` | bool | The write changed the version code. Written by `recordAppVersion`. |
| `app.version_name_valid` | bool | The version name header was valid. Written by `appVersionFromHeaders`. |
| `app.version_state` | string | `valid`, `absent` (app before 1.4.0) or `invalid` header. Written by `appVersionFromHeaders`. |
| `app.version_write` | string | `written`, `skipped_no_version`, `skipped_seen_within_hour`, `failed` (handled error `app_version_write_failed`). Written by `recordAppVersion`. |
| `auth.signed_in`, `auth.user_id` | bool, string | As for tRPC; the upload route has no procedure chain. Written by `authorizePhotoUpload`. |
| `page.kind` | string | `home` (the index), `profile_share` (a Profile Share page that is shown) or `unavailable` (not-found page). Written by `app/page.tsx`, `app/profile-shares/[token]/page.tsx`, `app/not-found.tsx`. |
| `page.language` | string | Language the page was drawn in. Written by same. |
| `photo.kept_file_count` | int | Files kept because another Appointment Photo still names them. Written by `markFilesUnused`. |
| `photo.unused_file_count` | int | Stored files that no Appointment Photo names any more (to delete after the commit). Written by `markFilesUnused`. |
| `photo_storage.delete_file_count` | int | Files sent to storage for deletion. Written by `deleteUnusedFiles`. |
| `photo_storage.delete_result` | string | `deleted`, `timed_out` (5 s) or `failed`; failures are handled errors `photo_storage_delete_timed_out` / `photo_storage_delete_failed`. Written by `deleteUnusedFiles`. |
| `profile_share.account_lookup` | string | Where the Stylist's account name came from: `cache`, `provider`, `stale_cache` (provider failed, old name used), `failed` (no name, the share is shown without it). Written by `accountName`, `loadProfileShareContent`. |
| `profile_share.has_account_name` | bool | The account has a name (`cache`/`provider` lookups). Written by `accountName`. |
| `profile_share.has_last_completed` | bool | There is a last completed Appointment to show. Written by `loadProfileShareContent`. |
| `profile_share.has_stylist_name`, `.has_salon_address` | bool | Whether the share shows them. Written by `loadProfileShareContent`. |
| `profile_share.image_served` | bool | A Share Image was built and sent. Written by image route. |
| `profile_share.language_source` | string | Where the answer's language came from: `query` (`?lang=`), `client`, `default`. Written by page, image route, `unavailableShareResponse`. |
| `profile_share.link_state` | string | `malformed_token` (not a token, no query made), `unknown`, `revoked`, `working`, `lookup_failed` (the proxy's lookup threw; the page then loads the link itself). Written by `findShareLink`, `lookUpSharePage`. |
| `profile_share.page_result` | string | `shown` or `not_found` (the page found the link unavailable by itself, after the proxy let it through). Written by share page. |
| `profile_share.photo_bytes` | int | `Content-Length` of the stored photo, when given. Written by `openSharePhoto`. |
| `profile_share.photo_count` | int | Photos included in the share. Written by `loadProfileShareContent`. |
| `profile_share.photo_media_type` | string | The stored photo's content type when it is a picture type, else `other`. Written by `openSharePhoto`. |
| `profile_share.photo_open` | string | What opening the stored photo gave: `opened`, `storage_refused` (not ok / no body), `not_a_picture`, `failed` (error or timeout). Written by `openSharePhoto`. |
| `profile_share.photo_outside_storage_count` | int | Stored photos left out because their address is not in this server's photo storage. Written by `loadProfileShareContent`. |
| `profile_share.photo_request` | string | Outcome of a photo address: `served`, `not_modified` (ETag matched), `photo_not_shared` (no photo of this name in the share), `link_unavailable`, `storage_failed` (502). Written by photos route. |
| `profile_share.photo_storage_status` | int | HTTP status photo storage answered with. Written by `openSharePhoto`. |
| `profile_share.service_count`, `.color_formula_count` | int | Services and Color Formulas of the last completed Appointment shown. Written by `loadProfileShareContent`. |
| `profile_share.shared_photo_count` | int | Photos the share shows (photos route). Written by photos route. |
| `profile_share.timezone_fallback` | bool | The Stylist's stored timezone is unknown to this server and UTC was used to format times. Written by `formatAppointmentTime`. |
| `profile_share.token_hash` | string | Short hash of the link's token: tells the requests for one link apart (proxy, page, picture, photos). Written by `findShareLink`. |
| `profile_share.unavailable_response` | bool | The picture/photo address answered the plain-text 404 for a link that shows nothing. Written by `unavailableShareResponse`. |
| `profile_share.upcoming_count` | int | Upcoming Appointments in the share. Written by `loadProfileShareContent`. |
| `profile_share.view` | string | `page`, `image` or `photo`: which address of the share was asked for. Written by page, image route, photos route. |
| `proxy.decision` | string | `method_refused` (405), `rewrite_to_unavailable` (link shows nothing, answered 404 by not-found), `passed_on`. Written by `proxy`, `passOn`. |
| `proxy.has_authorization_header`, `proxy.has_cookie_header` | bool | The request carried a sign-in header (never its value). Written by `proxy`. |
| `proxy.is_share_page` | bool | The address is a Profile Share page (so a lookup was made). Written by `passOn`. |
| `proxy.language`, `proxy.language_source` | string | Language put in the header for the not-found page and where it came from (`query`, `client`, `default`). Written by `passOn`. |
| `proxy.refused_method` | string | The method that was refused. Written by `proxy`. |
| `proxy.share_shows_nothing` | bool | Result of the lookup (absent on non-share addresses). Written by `passOn`. |
| `proxy.sign_in_fallback` | bool | Clerk could not decode the sign-in; the request was answered as if it had none (`true` only when it happened; paired with a handled error `clerk_token_undecodable`). Written by `proxy`. |
| `release.is_capped`, `release.capped_at_version_code` | bool, int | Releases newer than the published APK were held back (ADR 0018). Written by `releasesSince`. |
| `release.is_oldest_standing_in` | bool | Nothing was published at or below the cap; the oldest release stood in. Written by `releasesSince`. |
| `release.latest_version_code`, `release.count`, `release.since_version_code` | int | Latest offered release, how many newer releases were returned, and the app's own version. Written by `releasesSince`. |
| `release.manifest_result` | string | `read`, `failed_used_stale`, `failed_none`. Written by reader. |
| `release.manifest_source` | string | `cache` (fresh answer reused), `fetched` (this request started a read), `joined_reading` (waited for another's read). Written by published release reader. |
| `release.manifest_status` | int | HTTP status of the manifest (on its span). Written by reader. |
| `release.published_version_code` | int | Version code the server believes is published. Written by reader. |
| `share_image.height_px` | int | Height of the picture. Written by `buildShareImageSvg`. |
| `share_image.is_height_capped` | bool | The picture stopped listing Services because it was already tall. Written by `buildShareImageSvg`. |
| `share_image.language` | string | Language the picture was drawn in. Written by `buildShareImageSvg`. |
| `share_image.photo_cache_hit_count` | int | Photos taken from the in-memory cache. Written by `readSharePhoto`. |
| `share_image.photo_read_count` | int | Photos read from storage (cache misses). Written by `readSharePhoto`. |
| `share_image.photo_skip_reason` | string | Last skip reason: `storage_status`, `too_large`, `read_failed`. Written by `readSharePhoto`. |
| `share_image.photo_skipped_count` | int | Photos not used (storage refused, too large, read failed). Written by `readSharePhoto`. |
| `share_image.photo_source_bytes`, `.photo_output_bytes` | int | Total bytes read and bytes after rotating/shrinking. Written by `readSharePhoto`. |
| `share_image.photos_requested_count`, `.photos_embedded_count`, `.photos_left_out_count` | int | Photos asked for (max 6), drawn, and left out because they could not be read. Written by `buildShareImage`. |
| `share_image.services_cut_count` | int | Services left out (limit or height). Written by `buildShareImageSvg`. |
| `share_image.svg_length` | int | Length of the SVG in characters. Written by `buildShareImage`. |
| `share_image.upcoming_cut_count` | int | Upcoming Appointments left out for the picture's limit. Written by `buildShareImageSvg`. |
| `stylist.id` | string | Owners of the share (also set by `loadProfileShareContent`). Written by `findShareLink`. Also written by `authorizePhotoUpload` (Profile Shares). |
| `upload.action_type` | string | UploadThing's `actionType` (`upload`, `failure`, `multipart-complete`, or `none`). Written by route `POST`. |
| `upload.completed` | bool | The file arrived and the completion callback ran. Written by `onUploadComplete`. |
| `upload.decision` | string | `authorized` or `refused`. Written by `authorizePhotoUpload`. |
| `upload.file_count`, `upload.total_bytes`, `upload.largest_bytes` | int | Files the app asks to upload, their total and largest size. Written by `middleware`. |
| `upload.file_size_bytes` | int | Size of the arrived file. Written by `onUploadComplete`. |
| `upload.file_type` | string | Type of the (first) file as the app declares it, or as the storage reports it on completion. Written by `middleware`, `onUploadComplete`. |
| `upload.is_callback` | bool | The storage service reporting that a file arrived (`uploadthing-hook: callback`). Written by route `POST`. |
| `upload.refusal_code` | string | `FORBIDDEN`, `NOT_FOUND` or `BAD_REQUEST` when refused. The refusal is also an expected error with reason `not_signed_in`, `appointment_not_found` or `client_not_attached`. Written by `authorizePhotoUpload`. |
| `upload.slug` | string | The file route (`appointmentPhoto`) or `none`. Written by route `POST`. |

#### Reasons (`error.reason`)

The domain's reason for a refusal or a handled error, from `recordError(…, { reason })`. A **refusal** (`error.expected: true`, outcome `refused`) is a request the app was right to be told no. A **handled** error (`error.handled: true`) is a failure the code got over; the outcome did not change. An **unexpected** one (neither flag) failed the request.

| Reason | Kind | Meaning |
| --- | --- | --- |
| `account_lookup_failed_used_cache` | handled | The Stylist's name for a Share Image could not be read from Clerk; the cached one was used. |
| `account_name_unavailable` | handled | Clerk gave no name for the Stylist's Profile Share page; it is shown without one. |
| `app_version_write_failed` | handled | Storing the app version of the Stylist's device failed; the request went on. |
| `appointment_not_found` | refusal | The Appointment is not the Stylist's, or is not there (also for an Appointment Photo upload). |
| `appointment_total_above_maximum` | refusal | The Services would take the Appointment's total over the maximum amount. |
| `changed_repeat` | refusal | A request key already carried out was sent again with other values (`CONFLICT`; see `request.repeat_*`). |
| `clerk_token_undecodable` | handled | The proxy could not decode the sign-in; the request was passed on as if it had none. |
| `client_has_photos` | refusal | A Client with Appointment Photos in the Appointment cannot be removed from it. |
| `client_has_services` | refusal | A Client with Services in the Appointment cannot be removed from it. |
| `client_not_attached` | refusal | The Client is not on the Appointment (`removeParticipant`, and an upload for a Client that is not on it). |
| `client_not_found` | refusal | The Client is not the Stylist's, or is not there. |
| `client_referred_by_self` | refusal | A Client cannot be their own Referrer. |
| `color_formula_not_found` | refusal | The Color Formula is not there. |
| `copy_source_not_completed` | refusal | The Appointment to copy Services from is not Completed. |
| `copy_source_not_found` | refusal | The Appointment to copy Services from is not there. |
| `copy_target_not_found` | refusal | The Appointment to copy Services into is not there. |
| `distinct_from_self` | refusal | A Client cannot be marked as a different person from themselves. |
| `end_not_after_start` | refusal | The Appointment would end at or before its start. |
| `family_member_not_found` | refusal | The Client to add to the Family is not there. |
| `family_not_found` | refusal | The Client is in no Family (`setFamilyRole`). |
| `family_with_self` | refusal | A Client cannot be put in a Family with themselves. |
| `group_client_not_found` | refusal | A Client added to a new Appointment is not there. |
| `last_client` | refusal | The last Client of an Appointment cannot be removed. |
| `manifest_http_error` | handled | The published release manifest answered with an error status; the last known release is used. |
| `manifest_invalid` | handled | The manifest is not valid JSON of the expected shape. |
| `manifest_timeout` | handled | Reading the manifest timed out. |
| `manifest_unreadable` | handled | The manifest could not be read for another reason (network). |
| `menu_item_not_found` | refusal | A Service Menu item picked for a Service is not there. |
| `merge_into_self` | refusal | A Client cannot be merged into themselves. |
| `merged_note_too_long` | refusal | The two Client Notes together are over the limit. |
| `method_not_allowed` | refusal | The proxy refused a method other than reading on a page (405). |
| `no_completed_copy_source` | refusal | The Client has no Completed Appointment to copy Services from. |
| `not_signed_in` | refusal | An upload request carried no valid sign-in. |
| `nothing_to_copy` | refusal | The copy would add no Services. |
| `outdated_save` | refusal | The form was loaded before someone else saved the record (`CONFLICT`). |
| `photo_client_not_attached` | refusal | The Appointment Photo's Client is not on the Appointment. |
| `photo_file_taken` | refusal | The stored file is already another Appointment Photo. |
| `photo_not_found` | refusal | The Appointment Photo is not there. |
| `photo_not_in_storage` | refusal | A photo address does not point at the file's UploadThing storage. |
| `photo_storage_delete_failed` | handled | Deleting unused files from storage failed; they stay there. |
| `photo_storage_delete_timed_out` | handled | Deleting unused files from storage timed out. |
| `primary_client_not_attached` | refusal | The new primary Client is not on the Appointment. |
| `primary_client_not_found` | refusal | The primary Client is not there. |
| `profile_share_not_found` | refusal | The Client has no active Profile Share to change. |
| `referrer_not_found` | refusal | The Referrer sent is not a Client of the Stylist. |
| `removed_client_not_found` | refusal | The Client to merge away is not there and no such merge was done. |
| `service_client_not_attached` | refusal | The Service's Client is not on the Appointment. |
| `service_menu_item_not_found` | refusal | The Service Menu item is not there. |
| `service_name_missing` | refusal | A Service without a Service Menu item has no name. |
| `service_not_found` | refusal | The Service is not there. |
| `share_lookup_failed` | handled | Looking up a Profile Share failed; the link is answered as unavailable. |
| `share_photo_open_failed` | handled | Opening a Share Image photo from storage failed. |
| `share_photo_open_timed_out` | handled | Opening a Share Image photo from storage timed out. |
| `share_photo_read_failed` | handled | Reading a photo for the Share Image failed; the picture is drawn without it. |
| `share_photo_reader_threw` | handled | The photo reader of a Share Image threw; the picture is drawn without that photo. |
| `share_photo_unreadable` | unexpected | A Profile Share photo could not be read; the Client's phone gets a 502. |
| `stats_date_not_valid` | refusal | The day sent to the stats is not a date. |
| `stored_photo_without_file` | refusal | A stored Appointment Photo came without its file details. |
| `unhandled_in_<route_type>` | unexpected | Next.js caught an error nothing else did (`render`, `route`, `action`, `proxy`). |

Reasons that a rare path may add are named where they are thrown; a search can list the ones in use with `jq` on `error.reason`.

#### Child spans

Spans written with `traced`; their attributes stay on the span, and their time counts towards `slowest_span`.

| Span | Where | Attributes (on the span only) |
| --- | --- | --- |
| `appointment.add_menu_services` | `addMenuServices`: the inserts of the picked Services. | `appointment_service.count` |
| `appointment.copy_services` | `copyServices`: the inserts of the copied Services with their Color Formulas. | `appointment_service.count`, `color_formula.count` |
| `client.delete_appointments` | `delete`: the loop over the deleted Client's Appointments. | |
| `client_merge.move_appointments` | `merge`: the loop over the removed Client's Appointments. | |
| `photo_storage.delete_files` | The deletion of unused files after a commit. | |
| `profile_share.load_content` | The three loads of a Profile Share's content. | |
| `release.read_manifest` | Reading the published release manifest. | `release.manifest_status` |
| `share_image.build` | Reading photos and drawing a Share Image. | |
| `share_image.read_photo` | One photo for a Share Image. | `photo.storage_status`, `photo.source_bytes`, `photo.output_bytes` |
| `stats.aggregate_period` | `stats.summary`: adding up the period's service lines. | `stats.appointment_count`, `stats.service_line_count` |
| `stylist.lock_wait` | Waiting for the Stylist's advisory lock (ADR 0014). | |
