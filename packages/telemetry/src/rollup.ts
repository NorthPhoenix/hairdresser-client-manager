// What the child spans of a request add up to. A child span is exported as it is, but the event
// only gets its sums: how many database queries and how long they took, which Prisma operations
// ran, how many calls left the server, and the slowest piece of work.
import { SpanKind, SpanStatusCode, type Attributes, type HrTime } from "@opentelemetry/api";
import { cleanString } from "./guard";
import type { ErrorInfo, EventState } from "./state";

/** What the processor needs of an ended span (the SDK's ReadableSpan). */
export type EndedSpan = {
  name: string;
  kind: SpanKind;
  spanContext: () => { spanId: string; traceId: string };
  parentSpanContext?: { spanId: string };
  attributes: Attributes;
  status: { code: SpanStatusCode; message?: string };
  duration: HrTime;
  events?: { name: string; attributes?: Attributes }[];
  instrumentationScope?: { name: string };
};

/** The tracer name of `traced` spans. */
export const tracerName = "hcm";

// The span names of @prisma/instrumentation with Prisma 7.
const prismaQuery = "prisma:client:db_query";
const prismaOperation = "prisma:client:operation";
const prismaTransaction = "prisma:client:transaction";

const maxOperationKinds = 40;
const maxHosts = 10;

export function durationMs(duration: HrTime) {
  return Math.round((duration[0] * 1e3 + duration[1] / 1e6) * 100) / 100;
}

function stringAttribute(attributes: Attributes, ...names: string[]) {
  for (const name of names) {
    const value = attributes[name];

    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }

  return undefined;
}

function numberAttribute(attributes: Attributes, ...names: string[]) {
  for (const name of names) {
    const value = attributes[name];

    if (typeof value === "number") {
      return value;
    }

    if (typeof value === "string" && /^\d+$/.test(value)) {
      return Number(value);
    }
  }

  return undefined;
}

/** The host of an outgoing HTTP call, never its path or query, which can carry file keys. */
function hostOf(attributes: Attributes) {
  const host = stringAttribute(attributes, "server.address", "net.peer.name", "http.host");

  if (host) {
    return host.split(":")[0];
  }

  const url = stringAttribute(attributes, "url.full", "http.url");

  try {
    return url ? new URL(url).hostname : undefined;
  } catch {
    return undefined;
  }
}

function isOutgoingHttp(span: EndedSpan) {
  return span.kind === SpanKind.CLIENT && stringAttribute(span.attributes, "http.request.method", "http.method") !== undefined;
}

/** The first exception a span recorded, as OpenTelemetry writes it into the span's events. */
export function exceptionOf(span: EndedSpan): ErrorInfo | undefined {
  const exception = span.events?.find((event) => event.name === "exception");

  if (!exception?.attributes) {
    return undefined;
  }

  const type = stringAttribute(exception.attributes, "exception.type") ?? "Error";
  const message = stringAttribute(exception.attributes, "exception.message") ?? "";
  const stack = stringAttribute(exception.attributes, "exception.stacktrace");

  return {
    type: cleanString(type, 100),
    message: cleanString(message.trim(), 1000),
    stack: stack ? cleanString(stack, 4000) : undefined,
    expected: false,
    fromSpan: true
  };
}

/** Remembers the name of a Prisma operation when it starts, for the queries it runs. */
export function noteStartedChild(state: EventState, name: string, spanId: string, attributes: Attributes) {
  if (name === prismaOperation) {
    const operation = stringAttribute(attributes, "name");

    if (operation) {
      state.rollup.operationNames.set(spanId, operation);
    }
  }
}

/** Adds an ended child span to its event's sums. */
export function addChild(state: EventState, span: EndedSpan) {
  const rollup = state.rollup;
  const ms = durationMs(span.duration);
  const failed = span.status.code === SpanStatusCode.ERROR;
  let slowestName: string | undefined;

  rollup.spanCount += 1;

  if (span.attributes["next.span_type"] === "Middleware.execute") {
    rollup.proxy = true;
  }

  if (span.name === prismaQuery) {
    rollup.dbQueryCount += 1;
    rollup.dbQueryMs += ms;
    rollup.dbSlowestQueryMs = Math.max(rollup.dbSlowestQueryMs, ms);
    rollup.dbErrorCount += failed ? 1 : 0;
    const parentId = span.parentSpanContext?.spanId;
    slowestName = `db:${(parentId && rollup.operationNames.get(parentId)) || "query"}`;
  } else if (span.name === prismaOperation) {
    const operation = stringAttribute(span.attributes, "name") ?? "unknown";

    rollup.dbOperationCount += 1;

    if (rollup.dbOperations.has(operation) || rollup.dbOperations.size < maxOperationKinds) {
      rollup.dbOperations.set(operation, (rollup.dbOperations.get(operation) ?? 0) + 1);
    }

    rollup.operationNames.delete(span.spanContext().spanId);
  } else if (span.name === prismaTransaction) {
    rollup.dbTransactionCount += 1;
  } else if (isOutgoingHttp(span)) {
    const status = numberAttribute(span.attributes, "http.response.status_code", "http.status_code");
    const host = hostOf(span.attributes);

    rollup.externalCallCount += 1;
    rollup.externalCallMs += ms;
    rollup.externalSlowestMs = Math.max(rollup.externalSlowestMs, ms);
    rollup.externalErrorCount += failed || (status !== undefined && status >= 400) ? 1 : 0;

    if (host && rollup.externalHosts.size < maxHosts) {
      rollup.externalHosts.add(host);
    }

    slowestName = `http:${host ?? "unknown"}`;
  } else if (span.instrumentationScope?.name === tracerName) {
    // A unit of work this app named with `traced`.
    slowestName = span.name;
  }

  // Framework spans that wrap the whole request (Next.js's route handler span, Prisma's
  // operation and transaction spans) would always be the slowest and say nothing.
  if (slowestName && (!rollup.slowest || ms > rollup.slowest.ms)) {
    rollup.slowest = { name: cleanString(slowestName, 100), ms };
  }

  // An exception that a child span recorded, such as the framework's record of a failed route,
  // is the request's error when nothing else was recorded.
  if (failed && state.errors.length === 0 && span.name !== prismaQuery && !isOutgoingHttp(span)) {
    const exception = exceptionOf(span);

    if (exception) {
      state.errors.push(exception);
    }
  }
}
