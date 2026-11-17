// Starts the database connection while a route handler's module loads. Imported right after
// server/telemetry at the top of the tRPC route, whose requests wait for `databaseWarmedUp`.
//
// A new server instance spends its first query starting Prisma and opening a connection to the
// database (and waking the database when it was asleep), which every Stylist request on that
// instance waits for. On Vercel that is the first request: the framework calls the route's handler
// as soon as its module has loaded. Started here, as soon as the database client has loaded, that
// work is under way or done by the time the first request needs the database. That request then
// waits for what is left of it and takes the connection it opened, rather than opening one of its
// own beside it. The time it takes is the `db.warm_up` task in the server log (docs/observability.md).
import { db } from "@hcm/db";
import { recordError, traced } from "@hcm/telemetry";

/** How long a request waits for the warm-up at most, before it connects on its own as it would without one. */
const waitAtMostMs = 2_000;

function warmUp(): Promise<void> {
  return traced("db.warm_up", async () => {
    try {
      await db.$queryRaw`SELECT 1`;
    } catch (error) {
      // In the task's event with its cause. It stops nothing: the request's own query connects again.
      recordError(error, { reason: "database_warm_up_failed" });
    }
  });
}

/**
 * Settles when the warm-up has ended, whether or not it reached the database, or after
 * [waitAtMostMs]. It never fails. Settled at once while `next build` loads the modules to collect
 * page data: that serves no requests, so nothing is started.
 */
export const databaseWarmedUp: Promise<void> =
  process.env.NEXT_RUNTIME === "nodejs" && process.env.NEXT_PHASE !== "phase-production-build"
    ? Promise.race([warmUp().catch(() => undefined), new Promise<void>((resolve) => setTimeout(resolve, waitAtMostMs))])
    : Promise.resolve();
