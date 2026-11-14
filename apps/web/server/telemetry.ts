// Sets up telemetry when a route handler's module loads. Imported for that effect alone, at the
// top of every route.ts and of proxy.ts.
//
// instrumentation.ts is not enough on Vercel. There, the framework calls a route's handler
// directly and starts `register` without waiting for it: the root span of the first request on a
// new instance starts before OpenTelemetry is set up, so that request gets no span and no wide
// event. `next start` waits for `register`, which is why this does not show locally. The module
// of a route (and of proxy.ts) loads before its handler runs, so setting up here is always in
// time. Pages load their modules during the request and keep that gap (docs/observability.md).
import { registerTelemetry } from "@hcm/telemetry/node";

// Not while `next build` loads the modules to collect page data: it serves no requests.
if (process.env.NEXT_RUNTIME === "nodejs" && process.env.NEXT_PHASE !== "phase-production-build") {
  // registerTelemetry never throws and does nothing when instrumentation.ts was first.
  registerTelemetry({ serviceName: "hcm-web" });
}
