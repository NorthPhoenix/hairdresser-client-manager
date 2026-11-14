// A tracer provider for tests, with the wide event processor writing into an array instead of
// stdout. Each call replaces the previous global provider.
import { context, propagation, trace } from "@opentelemetry/api";
import { AsyncLocalStorageContextManager } from "@opentelemetry/context-async-hooks";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import { BasicTracerProvider, SimpleSpanProcessor, type SpanExporter } from "@opentelemetry/sdk-trace-base";
import { readConfig, type TelemetryConfig } from "./config";
import { WideEventProcessor } from "./processor";
import { registry } from "./state";

export type SetUpOptions = {
  config?: Partial<TelemetryConfig>;
  write?: (line: string) => void;
  random?: () => number;
  /** Also export every span here, as Vercel's or an OTLP exporter would. */
  exporter?: SpanExporter;
  /** Put the exporting processor before the wide event processor instead of after it. */
  exporterFirst?: boolean;
};

export function setUpTelemetry(options: SetUpOptions = {}) {
  const lines: string[] = [];

  trace.disable();
  context.disable();
  propagation.disable();
  registry().instance.internalErrors = 0;
  registry().instance.requestCount = 0;
  registry().skipped = new WeakSet();

  const processor = new WideEventProcessor({
    serviceName: "hcm-test",
    config: { ...readConfig({}), ...options.config },
    write: options.write ?? ((line) => lines.push(line)),
    random: options.random
  });
  const exporting = options.exporter ? [new SimpleSpanProcessor(options.exporter)] : [];
  const provider = new BasicTracerProvider({ spanProcessors: options.exporterFirst ? [...exporting, processor as never] : [processor as never, ...exporting] });

  context.setGlobalContextManager(new AsyncLocalStorageContextManager().enable());
  propagation.setGlobalPropagator(new W3CTraceContextPropagator());
  trace.setGlobalTracerProvider(provider);

  return {
    lines,
    events: () => lines.map((line) => JSON.parse(line) as Record<string, unknown>),
    tracer: trace.getTracer("test")
  };
}

export function tearDownTelemetry() {
  trace.disable();
  context.disable();
  propagation.disable();
}
