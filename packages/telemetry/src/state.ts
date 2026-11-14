// The state of the wide events of this process. It is kept on `globalThis` and not in this
// module: a framework such as Next.js bundles instrumentation.ts and each route separately, so the
// module can be loaded more than once, and every copy has to see the same events.
import { randomBytes } from "node:crypto";
import type { Span } from "@opentelemetry/api";
import { readConfig, type TelemetryConfig } from "./config";
import type { FieldValue } from "./guard";

export type ErrorInfo = {
  type: string;
  message: string;
  stack?: string;
  code?: string;
  reason?: string;
  retriable?: boolean;
  /** A refusal on purpose (a tRPC BAD_REQUEST, a NOT_FOUND) rather than a failure. */
  expected: boolean;
  /** A failure the code got over; it leaves the request's outcome as it was. */
  handled?: boolean;
  /** What was thrown, to recognise the same error recorded twice. Never printed. */
  source?: unknown;
  causeType?: string;
  causeMessage?: string;
  /** Taken from an exception a span recorded (the framework's), not from `recordError`. */
  fromSpan?: boolean;
};

export type Rollup = {
  spanCount: number;
  dbQueryCount: number;
  dbQueryMs: number;
  dbSlowestQueryMs: number;
  dbOperationCount: number;
  dbOperations: Map<string, number>;
  dbTransactionCount: number;
  dbErrorCount: number;
  externalCallCount: number;
  externalCallMs: number;
  externalSlowestMs: number;
  externalErrorCount: number;
  externalHosts: Set<string>;
  slowest?: { name: string; ms: number };
  /** Whether the request ran Next.js's proxy (proxy.ts), which Next.js traces on its own. */
  proxy: boolean;
  /** Prisma operation names by span id, so a database query is named after its operation. */
  operationNames: Map<string, string>;
};

/** One wide event while its request runs: the root span and everything said about it. */
export type EventState = {
  root: Span;
  /** "request" for a span of kind SERVER (an HTTP request), "task" for other work. */
  kind: "request" | "task";
  fields: Map<string, FieldValue>;
  counters: Map<string, number>;
  blocked: Set<string>;
  errors: ErrorInfo[];
  rollup: Rollup;
  ended: boolean;
};

type Registry = {
  /** Every recording span of a running event, root or child, to the event it belongs to. */
  states: WeakMap<object, EventState>;
  /** Whether a processor is installed, so that `recordError` knows whether an event will be printed. */
  processorInstalled: boolean;
  /** `service.name` of events printed outside a request. Set when the processor is created. */
  serviceName: string;
  config: TelemetryConfig;
  instance: {
    id: string;
    startedAt: number;
    requestCount: number;
    /** Failures inside telemetry, swallowed so they never fail a request, and reported in the next event. */
    internalErrors: number;
  };
};

const registryKey = Symbol.for("hcm.telemetry.registry");

export function registry(): Registry {
  const holder = globalThis as unknown as { [registryKey]?: Registry };

  holder[registryKey] ??= {
    states: new WeakMap(),
    processorInstalled: false,
    serviceName: "hcm",
    config: readConfig(),
    instance: {
      id: randomBytes(4).toString("hex"),
      startedAt: Date.now(),
      requestCount: 0,
      internalErrors: 0
    }
  };

  return holder[registryKey];
}

export function newRollup(): Rollup {
  return {
    spanCount: 0,
    dbQueryCount: 0,
    dbQueryMs: 0,
    dbSlowestQueryMs: 0,
    dbOperationCount: 0,
    dbOperations: new Map(),
    dbTransactionCount: 0,
    dbErrorCount: 0,
    externalCallCount: 0,
    externalCallMs: 0,
    externalSlowestMs: 0,
    externalErrorCount: 0,
    externalHosts: new Set(),
    proxy: false,
    operationNames: new Map()
  };
}

export function newEventState(root: Span, kind: EventState["kind"]): EventState {
  return {
    root,
    kind,
    fields: new Map(),
    counters: new Map(),
    blocked: new Set(),
    errors: [],
    rollup: newRollup(),
    ended: false
  };
}

/** Counts a failure inside telemetry. Never throws. */
export function noteInternalError() {
  try {
    registry().instance.internalErrors += 1;
  } catch {
    // Nothing is left to report it with.
  }
}
