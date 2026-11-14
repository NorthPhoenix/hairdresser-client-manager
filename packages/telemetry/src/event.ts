// Builds the wide event of a request from its root span, what code annotated and its child spans'
// sums, and prints it as one line of JSON. The field names are the contract with whoever reads
// the logs: docs/observability.md lists every one, and a change to a name or a meaning raises
// `schemaVersion`.
import { SpanStatusCode, type Attributes, type HrTime } from "@opentelemetry/api";
import { readDeployment, type TelemetryConfig } from "./config";
import { cleanString, type FieldValue } from "./guard";
import { durationMs, type EndedSpan } from "./rollup";
import { safeSpanName } from "./spans";
import { decideSample, type SampleDecision } from "./sampler";
import { noteInternalError, registry, type ErrorInfo, type EventState } from "./state";

/** Raised whenever a field changes its name, type or meaning. */
export const schemaVersion = 1;

export const eventNames = {
  /** An HTTP request: the root span is a server span. */
  request: "hcm.request",
  /** Other work with a root span of its own, such as a span started outside any request. */
  task: "hcm.task",
  /** Something about the server process rather than a request: start, a failure to set up. */
  process: "hcm.process",
  /** An error recorded where no event was running to carry it, so it is printed on its own. */
  error: "hcm.error"
} as const;

export type Outcome = "success" | "refused" | "error";

/** A line longer than this is shortened (`telemetry.truncated`); Vercel cuts long log lines. */
export const maxLineLength = 16_000;

export type RootSpan = EndedSpan & {
  startTime: HrTime;
  parentSpanContext?: { spanId: string; isRemote?: boolean };
  spanContext: () => { spanId: string; traceId: string };
};

function hrTimeToIso(time: HrTime) {
  return new Date(time[0] * 1e3 + Math.floor(time[1] / 1e6)).toISOString();
}

function frameworkAttribute(attributes: Attributes, ...names: string[]) {
  for (const name of names) {
    const value = attributes[name];

    if (typeof value === "string" || typeof value === "number") {
      return value;
    }
  }

  return undefined;
}

/**
 * The error that the event reports: the first failure that code recorded, else the first failure
 * a span recorded (OpenTelemetry describes those more thinly, by code rather than by class), else
 * the first refusal.
 */
export function primaryError(errors: ErrorInfo[]) {
  const failures = errors.filter((error) => !error.expected && !error.handled);

  return failures.find((error) => !error.fromSpan) ?? failures[0] ?? errors.find((error) => !error.expected) ?? errors[0];
}

export function outcomeOf(statusCode: number | undefined, spanFailed: boolean, errors: ErrorInfo[]): Outcome {
  // A failure the code got over does not change the outcome.
  const error = primaryError(errors.filter((recorded) => !recorded.handled));

  if ((error && !error.expected) || (statusCode !== undefined && statusCode >= 500) || (spanFailed && !error)) {
    return "error";
  }

  if (error || (statusCode !== undefined && statusCode >= 400)) {
    return "refused";
  }

  return "success";
}

/** The fields of an error, as they appear in any event. */
export function errorFields(error: ErrorInfo, count: number): Record<string, FieldValue | undefined> {
  return {
    "error.type": error.type,
    "error.message": error.message,
    "error.code": error.code,
    "error.api_code": error.apiCode,
    "error.http_status": error.httpStatus,
    "error.reason": error.reason,
    "error.retriable": error.retriable,
    "error.expected": error.expected,
    "error.handled": error.handled || undefined,
    "error.cause_type": error.causeType,
    "error.cause_message": error.causeMessage,
    "error.stack": error.stack,
    "error.count": count
  };
}

/** The fields every event carries about where it was made. */
export function processFields(serviceName: string) {
  const { instance } = registry();

  return {
    "service.name": serviceName,
    ...readDeployment(),
    "runtime.node_version": process.version,
    "instance.id": instance.id
  };
}

export type BuiltEvent = {
  event: Record<string, unknown>;
  sample: SampleDecision;
};

export function buildEvent(state: EventState, span: RootSpan, serviceName: string, config: TelemetryConfig, random?: () => number): BuiltEvent {
  const attributes = span.attributes;
  const rollup = state.rollup;
  // A status that code annotated is the one it answered with; the framework's may not be (for
  // proxy.ts, Next.js reports 200 whatever it answers).
  const annotatedStatus = state.fields.get("http.status_code");
  const statusCode = typeof annotatedStatus === "number" ? annotatedStatus : frameworkAttribute(attributes, "http.status_code", "http.response.status_code");
  const status = typeof statusCode === "string" ? Number(statusCode) || undefined : statusCode;
  const duration = durationMs(span.duration);
  const outcome = outcomeOf(status, span.status.code === SpanStatusCode.ERROR, state.errors);
  const stylistId = state.fields.get("stylist.id");
  const sample = decideSample(
    { outcome, durationMs: duration, hasErrors: state.errors.length > 0, stylistId: typeof stylistId === "string" ? stylistId : undefined },
    config,
    random
  );
  const error = primaryError(state.errors);
  // Next.js runs proxy.ts under a root span of its own, apart from the route's (on Vercel, in a
  // function of its own), and that span names no route.
  const proxyRoot = attributes["next.span_type"] === "Middleware.execute";
  const proxy = rollup.proxy || proxyRoot;
  const method = frameworkAttribute(attributes, "http.method", "http.request.method");
  const internalErrors = registry().instance.internalErrors;
  const event: Record<string, unknown> = {
    event: state.kind === "request" ? eventNames.request : eventNames.task,
    schema_version: schemaVersion,
    timestamp: hrTimeToIso(span.startTime),
    // Next.js names the span of proxy.ts `middleware GET` where it is a root of its own (on
    // Vercel); the event names it as `next start` does, by its method. An outgoing call never
    // gives its address.
    name: proxyRoot && typeof method === "string" ? method : cleanString(safeSpanName(span.name, span.kind, attributes), 120),
    outcome,
    duration_ms: duration,
    "http.method": method,
    "http.route": frameworkAttribute(attributes, "http.route", "next.route") ?? (proxy ? "proxy" : undefined),
    "http.status_code": status,
    "next.span_type": frameworkAttribute(attributes, "next.span_type"),
    "next.proxy": proxy || undefined,
    trace_id: span.spanContext().traceId,
    span_id: span.spanContext().spanId,
    parent_span_id: span.parentSpanContext?.spanId,
    "trace.continued": span.parentSpanContext?.isRemote === true,
    ...processFields(serviceName)
  };

  for (const [key, value] of state.fields) {
    event[key] = value;
  }

  for (const [key, value] of state.counters) {
    event[key] = value;
  }

  Object.assign(event, {
    span_count: rollup.spanCount,
    "db.query_count": rollup.dbQueryCount,
    "db.query_ms": Math.round(rollup.dbQueryMs * 100) / 100,
    "db.slowest_query_ms": rollup.dbSlowestQueryMs,
    "db.operation_count": rollup.dbOperationCount,
    "db.operations": rollup.dbOperations.size > 0 ? Object.fromEntries(rollup.dbOperations) : undefined,
    "db.transaction_count": rollup.dbTransactionCount,
    "external.call_count": rollup.externalCallCount,
    "external.call_ms": Math.round(rollup.externalCallMs * 100) / 100,
    "external.slowest_ms": rollup.externalSlowestMs,
    "external.error_count": rollup.externalErrorCount,
    "external.hosts": rollup.externalHosts.size > 0 ? [...rollup.externalHosts] : undefined,
    "slowest_span.name": rollup.slowest?.name,
    "slowest_span.ms": rollup.slowest?.ms,
    ...(error ? errorFields(error, state.errors.length) : {}),
    "sample.reason": sample.reason,
    "sample.rate": sample.rate,
    "telemetry.blocked_keys": state.blocked.size > 0 ? [...state.blocked].slice(0, 20) : undefined,
    // Failures since the previous event was printed; the processor sets the count back.
    "telemetry.internal_errors": internalErrors > 0 ? internalErrors : undefined
  });

  return { event, sample };
}

// Fields given up first when a line is too long, most expendable first.
const expendable = ["error.stack", "db.operations", "external.hosts", "telemetry.blocked_keys", "error.cause_message"];

/** One line of JSON, never longer than `maxLineLength`, with absent fields left out. */
export function serializeEvent(event: Record<string, unknown>): string {
  const replacer = (_key: string, value: unknown) => (typeof value === "bigint" ? value.toString() : value);
  let line = JSON.stringify(event, replacer);

  if (line.length <= maxLineLength) {
    return line;
  }

  const shortened: Record<string, unknown> = { ...event, "telemetry.truncated": true };

  if (typeof shortened["error.stack"] === "string") {
    shortened["error.stack"] = (shortened["error.stack"] as string).slice(0, 1500);
    line = JSON.stringify(shortened, replacer);
  }

  for (const key of expendable) {
    if (line.length <= maxLineLength) {
      break;
    }

    delete shortened[key];
    line = JSON.stringify(shortened, replacer);
  }

  if (line.length > maxLineLength) {
    // Something annotated far too much: keep what identifies the request and say so.
    const core = ["event", "schema_version", "timestamp", "name", "outcome", "duration_ms", "http.method", "http.route", "http.status_code", "trace_id", "span_id", "service.name", "deployment.env", "deployment.id", "stylist.id", "trpc.procedure", "error.type", "error.code", "error.message", "sample.reason", "sample.rate"];

    line = JSON.stringify({ ...Object.fromEntries(core.map((key) => [key, shortened[key]])), "telemetry.truncated": true }, replacer);
  }

  return line;
}

export type LineWriter = (line: string) => void;

// Vercel ties what a function prints with console.log to the request that printed it, so the
// event is printed that way rather than written to the stream directly.
export const writeToStdout: LineWriter = (line) => {
  console.log(line);
};

/** Prints an event. Never throws. */
export function writeEvent(event: Record<string, unknown>, write: LineWriter = writeToStdout) {
  try {
    write(serializeEvent(event));
  } catch {
    noteInternalError();
  }
}
