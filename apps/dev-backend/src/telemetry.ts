// Sets up OpenTelemetry and the wide events before anything else of the server loads, so this
// server prints the same `hcm.request` events as the web app does on Vercel (docs/observability.md).
// server.ts imports this first; it does nothing asynchronous, so it is done before the next import.
import { registerTelemetry } from "@hcm/telemetry/node";

registerTelemetry({ serviceName: "hcm-dev-backend" });
