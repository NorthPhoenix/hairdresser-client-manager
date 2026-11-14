// The span processor that turns traces into wide events. It sees every span of this process start
// and end. A span with no parent here that is a request (or work this app started outside any
// request) opens an event; every span under it belongs to that event. When the root span ends,
// the event is built, sampled and printed. Every span, of an event or not, is cleaned for export
// as it ends (spans.ts).
import { SpanKind, trace, type Context, type Span } from "@opentelemetry/api";
import { readConfig, type TelemetryConfig } from "./config";
import { buildEvent, writeEvent, writeToStdout, type LineWriter, type RootSpan } from "./event";
import { addChild, noteStartedChild, type EndedSpan } from "./rollup";
import { sanitizeSpan, type EndingSpan } from "./spans";
import { newEventState, noteInternalError, registry, type EventState } from "./state";

export type WideEventProcessorOptions = {
  /** `service.name` of every event: "hcm-web", "hcm-dev-backend". */
  serviceName: string;
  config?: TelemetryConfig;
  /** Where the line goes. Default: stdout. Tests pass their own. */
  write?: LineWriter;
  random?: () => number;
};

type StartedSpan = Span & { name?: string; kind?: SpanKind; attributes?: Record<string, unknown>; instrumentationScope?: { name?: string } };

/** The tracer name of Next.js's own spans. */
const nextTracer = "next.js";
/** `next.span_type` of the span in which Next.js runs proxy.ts. */
export const proxySpanType = "Middleware.execute";

/**
 * What a span with no parent in this process stands for:
 * - "request": an HTTP request. A span of kind SERVER (Next.js's `BaseServer.handleRequest`, the
 *   dev backend's `traceRequest`), or the span in which Next.js runs proxy.ts, which on Vercel is
 *   the root of a function of its own and of kind INTERNAL.
 * - null: the framework's own work outside any request, such as Next.js's `start response` that
 *   some responses begin after the request's span ended. It says nothing about a request, so it
 *   opens no event, and neither do the spans under it.
 * - "task": anything else, such as work this app started with `traced` outside any request.
 */
export function rootKind(span: StartedSpan): EventState["kind"] | null {
  const attributes = span.attributes ?? {};

  if (span.kind === SpanKind.SERVER || attributes["next.span_type"] === proxySpanType) {
    return "request";
  }

  if (span.instrumentationScope?.name === nextTracer || attributes["next.span_type"] !== undefined) {
    return null;
  }

  return "task";
}

/**
 * A `SpanProcessor` of the OpenTelemetry SDK (structurally: the SDK is bundled inside
 * @vercel/otel, so its interface is not imported). Every method swallows its own failures.
 *
 * It has to be the first of the processors (registerTelemetry puts it there), and it changes
 * spans only in `onEnding`, which the SDK calls for every processor before any `onEnd`: the
 * exporting processors only take spans in `onEnd`, so they always see them cleaned.
 */
export class WideEventProcessor {
  private readonly serviceName: string;
  private readonly config: TelemetryConfig;
  private readonly write: LineWriter;
  private readonly random: () => number;

  constructor(options: WideEventProcessorOptions) {
    this.serviceName = options.serviceName;
    this.config = options.config ?? readConfig();
    this.write = options.write ?? writeToStdout;
    this.random = options.random ?? Math.random;

    const state = registry();

    state.processorInstalled = true;
    state.serviceName = this.serviceName;
    state.config = this.config;
  }

  onStart(span: StartedSpan, parentContext: Context): void {
    try {
      const { states, skipped, instance } = registry();
      const parent = trace.getSpan(parentContext);

      if (parent && skipped.has(parent)) {
        skipped.add(span);
        return;
      }

      const parentState = parent ? states.get(parent) : undefined;

      if (parentState) {
        // Work that goes on after its request ended (a promise left running) adds nothing to
        // an event that was already printed, and opens none of its own; nor does anything it
        // starts in turn.
        if (parentState.ended) {
          skipped.add(span);
          return;
        }

        states.set(span, parentState);
        noteStartedChild(parentState, span.name ?? "", span.spanContext().spanId, (span.attributes ?? {}) as never);
        return;
      }

      // No parent here: a parent from another process (an incoming `traceparent`), or none.
      const kind = rootKind(span);

      if (kind === null) {
        skipped.add(span);
        return;
      }

      const state = newEventState(span, kind);

      if (kind === "request") {
        instance.requestCount += 1;
        state.fields.set("instance.request_seq", instance.requestCount);
        state.fields.set("instance.cold_start", instance.requestCount === 1);
        state.fields.set("instance.uptime_ms", Date.now() - instance.startedAt);
      }

      states.set(span, state);
    } catch {
      noteInternalError();
    }
  }

  // Called while a span can still be changed. Every span is cleaned for export; the root span
  // also gets the event's sums, so a trace viewer can search for them like for the annotated
  // fields.
  onEnding(span: Span): void {
    try {
      sanitizeSpan(span as unknown as EndingSpan);
    } catch {
      noteInternalError();
    }

    try {
      const state = registry().states.get(span);

      if (!state || state.root !== span) {
        return;
      }

      const rollup = state.rollup;
      // A status that code annotated (proxy.ts, which knows what it answers) replaces the
      // framework's, which Next.js sets just before the span ends.
      const annotatedStatus = state.fields.get("http.status_code");

      span.setAttributes({
        "db.query_count": rollup.dbQueryCount,
        "db.query_ms": Math.round(rollup.dbQueryMs),
        "external.call_count": rollup.externalCallCount,
        "external.call_ms": Math.round(rollup.externalCallMs),
        ...Object.fromEntries(state.counters),
        ...(typeof annotatedStatus === "number" ? { "http.status_code": annotatedStatus } : {})
      });
    } catch {
      noteInternalError();
    }
  }

  onEnd(span: EndedSpan): void {
    try {
      const state = registry().states.get(span);

      if (!state || state.ended) {
        return;
      }

      if (state.root !== (span as unknown)) {
        addChild(state, span);
        return;
      }

      state.ended = true;

      const { event, sample } = buildEvent(state, span as RootSpan, this.serviceName, this.config, this.random);

      if (sample.keep) {
        writeEvent(event, this.write);

        // The failures this event reported are not reported again.
        const reported = event["telemetry.internal_errors"];

        if (typeof reported === "number") {
          const { instance } = registry();

          instance.internalErrors = Math.max(0, instance.internalErrors - reported);
        }
      }
    } catch {
      noteInternalError();
    }
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
