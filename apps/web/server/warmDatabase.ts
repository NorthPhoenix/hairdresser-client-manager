// Starts the database as the tRPC route's module loads, as soon as the database client has loaded.
// Imported right after server/telemetry at the top of that route, whose signed-in requests call
// `untilDatabaseWarmedUp`.
//
// A new server instance spends its first query starting Prisma and opening a connection to the
// database (and waking the database when it was asleep), and the requests that come meanwhile wait
// for it. On Vercel the first request comes at once: the framework calls the route's handler as
// soon as its module has loaded. Started here, as soon as the database client has loaded, that work
// is under way or done by the time the first request needs the database. That request waits for
// what is left of it and then takes the connection it opened, rather than opening one of its own
// beside it; requests that come along meanwhile go ahead as they would without it. The time it takes
// is the `db.warm_up` task in the server log (docs/observability.md).
import { db } from "@hcm/db";
import { recordError, traced } from "@hcm/telemetry";

/** How long a request waits for the warm-up at most, before it connects on its own as it would without one. */
const waitAtMostMs = 2_000;

function warmUp(): Promise<void> {
  return traced("db.warm_up", async () => {
    try {
      await db.$queryRaw`SELECT 1`;
    } catch (error) {
      // Handled: it stops nothing, as each request's own query connects again.
      recordError(error, { handled: true, reason: "database_warm_up_failed" });
    }
  });
}

let settled = false;
let awaited = false;

// Settles when the warm-up has ended, whether or not it reached the database, or after
// [waitAtMostMs], and never fails. Settled at once while `next build` loads the modules to collect
// page data: that serves no requests, so nothing is started.
const warmedUp: Promise<void> = (
  process.env.NEXT_RUNTIME === "nodejs" && process.env.NEXT_PHASE !== "phase-production-build"
    ? Promise.race([warmUp().catch(() => undefined), new Promise<void>((resolve) => setTimeout(resolve, waitAtMostMs))])
    : Promise.resolve()
).then(() => {
  settled = true;
});

/**
 * For a request, before it uses the database: the first one to ask while the warm-up is under way
 * waits for it, in a span of its own (`db.warm_up_wait`), whose time counts towards the request's
 * `slowest_span`. Nothing for every other request, nor once the warm-up has ended or
 * [waitAtMostMs] have passed: a request waiting behind another would only open its own connection
 * later than it could have.
 */
export function untilDatabaseWarmedUp(): Promise<void> | undefined {
  if (settled || awaited) {
    return undefined;
  }

  awaited = true;

  return traced("db.warm_up_wait", () => warmedUp);
}
