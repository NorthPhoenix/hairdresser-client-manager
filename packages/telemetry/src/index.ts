// What code anywhere on the server uses to describe the request it is part of. Nothing is passed
// around: the request's event is found through the active OpenTelemetry context. Without a
// registered SDK (a unit test, a script) every call here does nothing, and none of them ever
// throws into the code that calls it. See docs/observability.md.
import { createHash } from "node:crypto";
import { context, propagation, ROOT_CONTEXT, SpanKind, SpanStatusCode, trace, type Context, type Span, type TextMapGetter } from "@opentelemetry/api";
import { errorFields, eventNames, processFields, schemaVersion, writeEvent } from "./event";
import { cleanString, guardFields, normalizeKey, refusalOfKey, type Fields } from "./guard";
import { tracerName } from "./rollup";
import { describeError, type ErrorDetails } from "./errors";
import { noteInternalError, registry, type EventState } from "./state";

export { eventNames, schemaVersion } from "./event";
export type { ErrorDetails } from "./errors";
export type { FieldValue, Fields } from "./guard";

const maxErrorsPerEvent = 10;

function activeState(): { span: Span; state: EventState } | undefined {
  const span = trace.getActiveSpan();
  const state = span ? registry().states.get(span) : undefined;

  return span && state && !state.ended ? { span, state } : undefined;
}

/**
 * Adds fields to the wide event of the current request: IDs, counts, decisions, outcomes.
 * Nested objects become dotted keys (`{ client: { count: 2 } }` is `client.count`), camelCase
 * becomes snake_case, and the privacy guard drops what may not be recorded (it is named in
 * `telemetry.blocked_keys`). A later value for the same key replaces the earlier one.
 *
 * Pass a function that returns the fields when working them out reads data that may be missing:
 * it runs inside the same guard as everything else here, so a failure while computing leaves the
 * fields out of the event (and is counted as a telemetry error) instead of failing the request.
 * Without an active event the function is not even called.
 */
export function annotate(fields: Fields | (() => Fields)): void {
  try {
    const active = activeState();

    if (!active) {
      return;
    }

    const { fields: clean, blocked } = guardFields(typeof fields === "function" ? fields() : fields);

    for (const [key, value] of Object.entries(clean)) {
      active.state.fields.set(key, value);
    }

    blocked.forEach((key) => active.state.blocked.add(key));
    active.state.root.setAttributes(clean);
  } catch {
    noteInternalError();
  }
}

/** Adds `by` to a numeric field of the current request's event, such as `client.merge_count`. */
export function increment(key: string, by = 1): void {
  try {
    const active = activeState();

    if (!active || !Number.isFinite(by)) {
      return;
    }

    const normalized = normalizeKey(key);
    const refusal = refusalOfKey(normalized);

    if (refusal) {
      active.state.blocked.add(`${normalized}:${refusal}`);
      return;
    }

    active.state.counters.set(normalized, (active.state.counters.get(normalized) ?? 0) + by);
  } catch {
    noteInternalError();
  }
}

/** What a `traced` function can say about its own span (not the request's event). */
export type SpanHandle = {
  annotate(fields: Fields): void;
};

const noopHandle: SpanHandle = { annotate: () => undefined };

function isPromiseLike(value: unknown): value is PromiseLike<unknown> {
  return value !== null && (typeof value === "object" || typeof value === "function") && typeof (value as { then?: unknown }).then === "function";
}

function handleFor(span: Span): SpanHandle {
  return {
    annotate(fields) {
      try {
        const { fields: clean, blocked } = guardFields(fields);

        span.setAttributes(clean);
        blocked.forEach((key) => activeState()?.state.blocked.add(key));
      } catch {
        noteInternalError();
      }
    }
  };
}

function endSpan(span: Span, error?: unknown) {
  try {
    if (error !== undefined) {
      const info = describeError(error);

      span.recordException({ name: info.type, message: info.message, stack: info.stack });
      span.setStatus({ code: SpanStatusCode.ERROR, message: info.type });
    }

    span.end();
  } catch {
    noteInternalError();
  }
}

/**
 * Runs `fn` in a child span named `name` ("domain.unit_of_work", such as
 * "appointment.check_overlaps"), and returns what it returns, or throws what it throws. Its time
 * counts towards `slowest_span` of the request's event. A failure inside telemetry never stops
 * `fn` from running, exactly once.
 *
 * Wrap an async function, never a bare Prisma query that is passed on to `$transaction([...])`:
 * the query comes back as a plain Promise, which that form of `$transaction` does not take.
 */
export function traced<T>(name: string, fn: (span: SpanHandle) => T, fields?: Fields): T {
  let span: Span;
  let spanContext: Context;

  try {
    const { fields: clean } = guardFields(fields ?? {});

    span = trace.getTracer(tracerName).startSpan(cleanString(name, 100), { attributes: clean });
    spanContext = trace.setSpan(context.active(), span);
  } catch {
    noteInternalError();
    return fn(noopHandle);
  }

  let called = false;
  let result: T;

  try {
    result = context.with(spanContext, () => {
      called = true;
      return fn(handleFor(span));
    });
  } catch (error) {
    if (!called) {
      // The context manager failed before `fn` ran: run it untraced.
      noteInternalError();
      endSpan(span);
      return fn(noopHandle);
    }

    endSpan(span, error);
    throw error;
  }

  if (isPromiseLike(result)) {
    return Promise.resolve(result).then(
      (value) => {
        endSpan(span);
        return value;
      },
      (error: unknown) => {
        endSpan(span, error);
        throw error;
      }
    ) as T;
  }

  endSpan(span);
  return result;
}

/**
 * Records an error on the current request's event: type, message, code, stack, cause, whether it
 * was expected and whether a retry could pass. The first unexpected error is the event's
 * `error.*`; `error.count` counts them all. Where no event is running (outside a request, or
 * telemetry is not set up) an unexpected error is printed on its own line as an `hcm.error`
 * event, so it is never lost.
 */
export function recordError(error: unknown, details: ErrorDetails = {}): void {
  try {
    const info = describeError(error, details);
    const active = activeState();

    if (active) {
      const errors = active.state.errors;
      // The same error recorded again, as a router does that records a refusal with its reason
      // and throws it to `onError`, which records it as well: the two records become one.
      const same = errors.find((recorded) => recorded.source !== undefined && recorded.source === info.source);

      if (same) {
        same.reason ??= info.reason;
        same.retriable ??= info.retriable;
        same.code ??= info.code;
        return;
      }

      // The framework may have recorded the same failure on its span already: this record of it
      // is the fuller one.
      const fromSpan = errors.findIndex((recorded) => recorded.fromSpan && recorded.message === info.message);

      if (fromSpan >= 0) {
        errors.splice(fromSpan, 1);
      }

      if (errors.length < maxErrorsPerEvent) {
        errors.push(info);
      }

      if (!info.expected) {
        active.span.recordException({ name: info.type, message: info.message, stack: info.stack });

        if (!info.handled) {
          active.state.root.setStatus({ code: SpanStatusCode.ERROR, message: info.type });
        }
      }

      return;
    }

    if (!info.expected) {
      const spanContext = trace.getActiveSpan()?.spanContext();

      writeEvent({
        event: eventNames.error,
        schema_version: schemaVersion,
        timestamp: new Date().toISOString(),
        outcome: "error",
        trace_id: spanContext?.traceId,
        span_id: spanContext?.spanId,
        ...processFields(registry().serviceName),
        ...errorFields(info, 1)
      });
    }
  } catch {
    noteInternalError();
  }
}

const headersGetter: TextMapGetter<Headers> = {
  keys: (carrier) => [...carrier.keys()],
  get: (carrier, key) => carrier.get(key) ?? undefined
};

export type RequestInfo = {
  method: string;
  /** The route pattern, never the path itself: "/api/trpc/[trpc]", "/profile-shares/[token]". */
  route: string;
  /** The request's headers, for an incoming `traceparent`. */
  headers?: Headers;
};

/**
 * Runs `fn` as an HTTP request with its own wide event, for a server that has no OpenTelemetry
 * spans of its own (the dev backend; Next.js makes the root span itself). An incoming
 * `traceparent` continues its trace. If `fn` returns something with a numeric `status` (a
 * Response), that is the event's `http.status_code`; if it throws, the status is 500.
 */
export async function traceRequest<T>(request: RequestInfo, fn: () => Promise<T>): Promise<T> {
  let span: Span;
  let spanContext: Context;

  try {
    const parent = request.headers ? propagation.extract(ROOT_CONTEXT, request.headers, headersGetter) : ROOT_CONTEXT;

    span = trace.getTracer(tracerName).startSpan(
      `${request.method} ${request.route}`,
      {
        kind: SpanKind.SERVER,
        attributes: {
          "http.method": cleanString(request.method, 10),
          "http.route": cleanString(request.route, 100)
        }
      },
      parent
    );
    spanContext = trace.setSpan(parent, span);
  } catch {
    noteInternalError();
    return fn();
  }

  let called = false;

  try {
    const result = await context.with(spanContext, () => {
      called = true;
      return fn();
    });

    try {
      const status = (result as { status?: unknown } | null)?.status;

      if (typeof status === "number") {
        span.setAttribute("http.status_code", status);
      }
    } catch {
      noteInternalError();
    }

    return result;
  } catch (error) {
    if (!called) {
      noteInternalError();
      return fn();
    }

    try {
      context.with(spanContext, () => recordError(error));
      span.setAttribute("http.status_code", 500);
    } catch {
      noteInternalError();
    }

    throw error;
  } finally {
    try {
      span.end();
    } catch {
      noteInternalError();
    }
  }
}

/** The first 12 hex characters of SHA-256 of `value`: names a share token without telling it. */
export function hashPrefix(value: string): string {
  try {
    return createHash("sha256").update(value).digest("hex").slice(0, 12);
  } catch {
    return "";
  }
}

/** The trace id of the current request, for a reply header or a message to the maintainer. */
export function currentTraceId(): string | undefined {
  try {
    return trace.getActiveSpan()?.spanContext().traceId;
  } catch {
    return undefined;
  }
}

/** Fields about the request that its headers tell without telling anything personal. */
export function requestFields(headers: Pick<Headers, "get">): Fields {
  try {
    return {
      // Vercel's id of the request, as in its own logs: "fra1::iad1::abcde-1700000000000-0123".
      "request.id": headers.get("x-vercel-id") ?? undefined,
      "http.user_agent": headers.get("user-agent")?.slice(0, 120) ?? undefined,
      "http.request_bytes": Number(headers.get("content-length")) || undefined
    };
  } catch {
    return {};
  }
}
