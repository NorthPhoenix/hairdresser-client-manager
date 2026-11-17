// Starts the database connection while a route handler's module loads. Imported for that effect
// alone, right after server/telemetry at the top of the tRPC route.
//
// A new server instance spends its first query starting Prisma and opening a connection to the
// database (and waking the database when it was asleep), which every Stylist request on that
// instance waits for. On Vercel that is the first request: the framework calls the route's handler
// as soon as its module has loaded. Started here, that work begins before the rest of the route's
// modules load, and the first request finds it done or under way. The time it takes is the
// `db.warm_up` task in the server log (docs/observability.md).
import { db } from "@hcm/db";
import { traced } from "@hcm/telemetry";

// Not while `next build` loads the modules to collect page data: it serves no requests.
if (process.env.NEXT_RUNTIME === "nodejs" && process.env.NEXT_PHASE !== "phase-production-build") {
  // A failure is in the task's event. It stops nothing: the request's own query connects again.
  traced("db.warm_up", async () => {
    await db.$queryRaw`SELECT 1`;
  }).catch(() => undefined);
}
