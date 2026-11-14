// Sets up OpenTelemetry for a Node.js server of this app: the Next.js server on Vercel
// (apps/web/instrumentation.ts) and the dev backend. Imported only by those entry points; code
// that describes requests imports "@hcm/telemetry", which needs none of this.
import { PrismaInstrumentation } from "@prisma/instrumentation";
import { registerOTel } from "@vercel/otel";
import { readConfig } from "./config";
import { describeError } from "./errors";
import { errorFields, eventNames, processFields, schemaVersion, writeEvent } from "./event";
import { WideEventProcessor } from "./processor";
import { registry } from "./state";

export { WideEventProcessor, type WideEventProcessorOptions } from "./processor";

export type RegisterOptions = {
  /** `service.name` of every event and span: "hcm-web", "hcm-dev-backend". */
  serviceName: string;
};

const registeredKey = Symbol.for("hcm.telemetry.registered");

/** Prints an `hcm.process` event: something about the server process rather than a request. */
function writeProcessEvent(serviceName: string, phase: string, fields: Record<string, unknown> = {}) {
  writeEvent({
    event: eventNames.process,
    schema_version: schemaVersion,
    timestamp: new Date().toISOString(),
    "process.phase": phase,
    ...processFields(serviceName),
    "instance.started_at": new Date(registry().instance.startedAt).toISOString(),
    ...fields
  });
}

function describeFailure(error: unknown) {
  return errorFields(describeError(error), 1);
}

/**
 * Starts OpenTelemetry with the wide event processor, Prisma's and fetch's spans, and the export
 * that fits where it runs: Vercel's own trace collection on Vercel, an OTLP endpoint when
 * OTEL_EXPORTER_OTLP_ENDPOINT is set, else none. Prints an `hcm.process` event with
 * `process.phase` "start". Runs once per process; a second call does nothing. Never throws: if
 * setting up fails, the server runs without telemetry and an event with `process.phase`
 * "telemetry_failed" says why.
 */
export function registerTelemetry(options: RegisterOptions): void {
  const holder = globalThis as unknown as { [registeredKey]?: true };

  if (holder[registeredKey]) {
    return;
  }

  holder[registeredKey] = true;

  const startedAt = performance.now();

  try {
    const config = readConfig();

    if (!config.enabled) {
      return;
    }

    const env = process.env;
    // Off Vercel, @vercel/otel's automatic export sends to an OTLP collector at localhost:4318
    // even when none is configured. That is only wanted when an endpoint is named.
    const exportsSpans = config.exportEnabled && Boolean(env.VERCEL || env.OTEL_EXPORTER_OTLP_ENDPOINT || env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT);

    registerOTel({
      serviceName: options.serviceName,
      // Every request gets a recording span and so a wide event, also when an incoming
      // `traceparent` says its caller did not sample it. What is printed is decided at the end by
      // the tail sampler; what Vercel keeps, by its own sampling rules.
      traceSampler: "always_on",
      spanProcessors: [new WideEventProcessor({ serviceName: options.serviceName, config }), ...(exportsSpans ? (["auto"] as const) : [])],
      instrumentations: [
        // Outgoing fetch: UploadThing, Clerk, the release manifest. The trace is passed on only
        // to this app's own deployments, which is @vercel/otel's default.
        "fetch",
        new PrismaInstrumentation()
      ]
    });

    process.on("uncaughtExceptionMonitor", (error, origin) => {
      // A monitor only watches: the process still fails as it would without it.
      writeProcessEvent(options.serviceName, origin === "unhandledRejection" ? "unhandled_rejection" : "uncaught_exception", describeFailure(error));
    });

    writeProcessEvent(options.serviceName, "start", {
      "telemetry.init_ms": Math.round((performance.now() - startedAt) * 100) / 100,
      "telemetry.export": exportsSpans,
      "telemetry.sample_rate": config.sampleRate,
      "telemetry.slow_ms": config.slowMs
    });
  } catch (error) {
    try {
      writeProcessEvent(options.serviceName, "telemetry_failed", describeFailure(error));
    } catch {
      // Nothing is left to report it with; the server runs on without telemetry.
    }
  }
}

/** Prints an `hcm.process` event for a failure of the server outside any request, such as a module that cannot load. */
export function recordProcessFailure(serviceName: string, phase: string, error: unknown) {
  try {
    writeProcessEvent(serviceName, phase, describeFailure(error));
  } catch {
    // As above.
  }
}
