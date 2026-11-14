// The span processor that turns traces into wide events. It sees every span of this process start
// and end. A span with no parent here (a request that arrived, or work started outside any
// request) opens an event; every span under it belongs to that event. When the root span ends,
// the event is built, sampled and printed.
import { SpanKind, trace, type Context, type Span } from "@opentelemetry/api";
import { readConfig, type TelemetryConfig } from "./config";
import { buildEvent, writeEvent, writeToStdout, type LineWriter, type RootSpan } from "./event";
import { addChild, noteStartedChild, type EndedSpan } from "./rollup";
import { newEventState, noteInternalError, registry } from "./state";

export type WideEventProcessorOptions = {
  /** `service.name` of every event: "hcm-web", "hcm-dev-backend". */
  serviceName: string;
  config?: TelemetryConfig;
  /** Where the line goes. Default: stdout. Tests pass their own. */
  write?: LineWriter;
  random?: () => number;
};

type StartedSpan = Span & { name?: string; kind?: SpanKind; attributes?: Record<string, unknown> };

/**
 * A `SpanProcessor` of the OpenTelemetry SDK (structurally: the SDK is bundled inside
 * @vercel/otel, so its interface is not imported). Every method swallows its own failures.
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
      const { states, instance } = registry();
      const parent = trace.getSpan(parentContext);
      const parentState = parent ? states.get(parent) : undefined;

      if (parentState) {
        // Work that goes on after its request ended (a promise left running) adds nothing to
        // an event that was already printed, and opens none of its own.
        if (!parentState.ended) {
          states.set(span, parentState);
          noteStartedChild(parentState, span.name ?? "", span.spanContext().spanId, (span.attributes ?? {}) as never);
        }

        return;
      }

      // No parent here: a parent from another process (an incoming `traceparent`), or none.
      const kind = span.kind === SpanKind.SERVER ? "request" : "task";
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

  // Called while the root span can still take attributes: the sums go onto the exported span too,
  // so a trace viewer can search for them like for the annotated fields.
  onEnding(span: Span): void {
    try {
      const state = registry().states.get(span);

      if (!state || state.root !== span) {
        return;
      }

      const rollup = state.rollup;

      span.setAttributes({
        "db.query_count": rollup.dbQueryCount,
        "db.query_ms": Math.round(rollup.dbQueryMs),
        "external.call_count": rollup.externalCallCount,
        "external.call_ms": Math.round(rollup.externalCallMs),
        ...Object.fromEntries(state.counters)
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
