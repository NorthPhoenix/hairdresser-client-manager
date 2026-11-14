import { annotate, recordError, traced } from "@hcm/telemetry";
import { initTRPC, TRPCError } from "@trpc/server";
import { z } from "zod";
import { recordAppVersion } from "./appReleases";
import type { TRPCContext } from "./context";
import { timezoneText } from "./input";
import { deleteUnusedFiles } from "./photoStorage";

const t = initTRPC.context<TRPCContext>().create({
  // No reply carries a stack trace, also not in development: failures are logged by the server
  // that hosts the router.
  isDev: false,
  errorFormatter({ shape, error }) {
    // An unexpected failure carries the text of whatever was thrown, such as a database statement
    // with server paths. A refusal thrown on purpose has its own code and keeps its message.
    return error.code === "INTERNAL_SERVER_ERROR" ? { ...shape, message: "Internal server error." } : shape;
  }
});

// Database failures a caller can act on, by Prisma error code. Prisma's own message names tables,
// constraints and server paths, so it is replaced.
const databaseFailures: Record<string, { code: "CONFLICT" | "NOT_FOUND"; message: string }> = {
  // Unique constraint.
  P2002: {
    code: "CONFLICT",
    message: "This record already exists."
  },
  // Foreign key: a record this change refers to was deleted in the meantime.
  P2003: {
    code: "NOT_FOUND",
    message: "A record this change refers to no longer exists."
  },
  // Update or delete of a record that was deleted in the meantime.
  P2025: {
    code: "NOT_FOUND",
    message: "This record no longer exists."
  }
};

function getDatabaseFailure(error: TRPCError) {
  // Recognised by shape, so this package needs no runtime import of the Prisma client.
  const cause = error.cause as { name?: unknown; code?: unknown } | undefined;

  return cause?.name === "PrismaClientKnownRequestError" && typeof cause.code === "string"
    ? databaseFailures[cause.code]
    : undefined;
}

/**
 * What the request's wide event says about a procedure's input: how big it is and which fields it
 * has, never what they hold (docs/observability.md).
 */
export function inputShape(rawInput: unknown) {
  if (rawInput === undefined) {
    return { "trpc.input_bytes": 0 };
  }

  const bytes = JSON.stringify(rawInput)?.length ?? 0;

  return rawInput && typeof rawInput === "object" && !Array.isArray(rawInput)
    ? { "trpc.input_bytes": bytes, "trpc.input_fields": Object.keys(rawInput).slice(0, 20) }
    : { "trpc.input_bytes": bytes };
}

// The outermost step of every procedure: it names the procedure in the request's wide event, and it
// sees the result after every other step, refusals included. The error itself is recorded where
// the route handler's `onError` sees it (`recordProcedureError`), which also covers a request that
// names no procedure.
const observedProcedure = t.procedure.use(async ({ path, type, ctx, getRawInput, next }) => {
  annotate({
    "trpc.procedure": path,
    "trpc.type": type,
    "auth.signed_in": ctx.clerkAuth.userId !== null,
    "auth.user_id": ctx.clerkAuth.userId ?? undefined,
    "app.version_code": ctx.appVersion?.code,
    "app.version": ctx.appVersion?.name ?? undefined
  });

  try {
    annotate(inputShape(await getRawInput()));
  } catch {
    // Input that cannot be read is refused by the procedure itself, as before.
  }

  const startedAt = performance.now();
  const result = await next();

  annotate({
    "trpc.ok": result.ok,
    "trpc.error_code": result.ok ? undefined : result.error.code,
    "trpc.duration_ms": Math.round(performance.now() - startedAt)
  });

  return result;
});

/**
 * Records a failed procedure call in the request's wide event, for the `onError` of the server that
 * hosts the router. The reply to an unexpected failure says nothing about it, so the event keeps
 * what was thrown, with its stack; a refusal on purpose is recorded as expected, with its code.
 */
export function recordProcedureError({ error, path }: { error: TRPCError; path?: string }) {
  const unexpected = error.code === "INTERNAL_SERVER_ERROR";

  annotate({
    "trpc.procedure": path,
    "trpc.ok": false,
    "trpc.error_code": error.code
  });
  // A failure is described by what was thrown (a Prisma error and its code); a refusal by itself.
  recordError(unexpected ? (error.cause ?? error) : error, { expected: !unexpected, code: unexpected ? undefined : error.code });
}

export const createTRPCRouter = t.router;
export const publicProcedure = observedProcedure.use(async ({ next }) => {
  const result = await next();

  if (!result.ok) {
    const failure = getDatabaseFailure(result.error);

    if (failure) {
      throw new TRPCError({
        ...failure,
        cause: result.error.cause
      });
    }
  }

  return result;
});

export const protectedProcedure = publicProcedure.use(({ ctx, next }) => {
  const clerkUserId = ctx.clerkAuth.userId;

  if (!clerkUserId) {
    throw new TRPCError({
      code: "UNAUTHORIZED",
      message: "Authentication required."
    });
  }

  return next({
    ctx: {
      ...ctx,
      clerkAuth: {
        ...ctx.clerkAuth,
        userId: clerkUserId
      }
    }
  });
});

// What the device suggests for a Stylist who does not exist yet. The input of any procedure can
// carry them, because the Stylist is created by whichever call comes first.
const deviceDefaultsInput = z.object({
  deviceLanguage: z.enum(["ru", "en"]).optional(),
  deviceTimezone: timezoneText.optional()
});

// Whether the upsert created the Stylist: the first request of a new account (docs/adr/0011).
// Prisma Client sets `createdAt` (`@default(now())`) with this server's clock, so a Stylist created
// by this upsert has a `createdAt` no earlier than the moment before it.
function wasCreatedSince(stylist: { createdAt?: unknown }, since: number) {
  return stylist.createdAt instanceof Date && stylist.createdAt.getTime() >= since;
}

export const stylistProcedure = protectedProcedure.use(async ({ ctx, getRawInput, next }) => {
  const where = {
    clerkId: ctx.clerkAuth.userId
  };
  const rawInput = await getRawInput();
  // Whole milliseconds, as the database keeps them.
  const upsertStartedAt = Math.floor(Date.now());
  const deviceDefaults = deviceDefaultsInput.safeParse(
    rawInput && typeof rawInput === "object" && !Array.isArray(rawInput) ? rawInput : {}
  );
  // Defaults that are not valid create no Stylist. A Stylist who already exists does not need
  // them and is not held up by them, for instance on a device whose timezone this server does
  // not know.
  const stylist = deviceDefaults.success
    ? await ctx.db.stylist.upsert({
        where,
        update: {},
        create: {
          ...where,
          language: deviceDefaults.data.deviceLanguage ?? "ru",
          timezone: deviceDefaults.data.deviceTimezone ?? "UTC"
        }
      })
    : await ctx.db.stylist.findUnique({
        where
      });

  if (!stylist) {
    annotate({ "stylist.device_defaults_valid": false });
    throw new TRPCError({
      code: "BAD_REQUEST",
      cause: deviceDefaults.error
    });
  }

  annotate({
    "stylist.id": stylist.id,
    "stylist.just_created": wasCreatedSince(stylist, upsertStartedAt),
    "stylist.device_defaults_valid": deviceDefaults.success
  });

  // Which app the Stylist uses, for the maintainer; at most about once an hour per Stylist.
  await recordAppVersion(ctx.db, stylist, ctx.appVersion ?? null);

  return next({
    ctx: {
      ...ctx,
      stylist
    }
  });
});

/**
 * For mutations that check Appointment or Client state before they write.
 *
 * The whole procedure runs in one database transaction, with `ctx.db` as that transaction, so a
 * refusal or a failure part-way leaves nothing behind. The transaction starts by taking a lock
 * that one request per Stylist can hold at a time, so two such mutations of the same Stylist
 * never interleave: the second waits and then reads what the first committed. `ctx.stylist` was
 * read before the lock; a procedure that depends on a Stylist field reads it again.
 * See docs/adr/0014-one-checking-mutation-at-a-time-per-stylist.md.
 *
 * A procedure that deletes Appointment Photos names their stored files in `ctx.unusedFiles`
 * (`markFilesUnused`). They are deleted from photo storage after the commit, never before: a file
 * has to outlive every record that names it.
 */
export const stylistTransactionProcedure = stylistProcedure.use(async ({ ctx, next }) => {
  const unusedFiles: string[] = [];
  // For the wide event: how long the transaction took to open, to get the lock, and in all.
  const calledAt = performance.now();
  const timing: { beganAt?: number; lockWaitMs?: number } = {};
  const describeTransaction = (outcome: "committed" | "rolled_back") =>
    annotate({
      "tx.outcome": outcome,
      "tx.begin_ms": timing.beganAt === undefined ? undefined : Math.round(timing.beganAt - calledAt),
      "tx.lock_wait_ms": timing.lockWaitMs === undefined ? undefined : Math.round(timing.lockWaitMs),
      "tx.duration_ms": Math.round(performance.now() - calledAt),
      "tx.unused_file_count": unusedFiles.length
    });
  const transaction = ctx.db.$transaction(
    async (tx) => {
      timing.beganAt = performance.now();
      await traced("stylist.lock_wait", () => tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${ctx.stylist.id}, 0))`);
      timing.lockWaitMs = performance.now() - timing.beganAt;

      const result = await next({
        ctx: {
          db: tx,
          unusedFiles
        }
      });

      // A failed procedure reports through its result; throwing is what rolls the transaction back.
      if (!result.ok) {
        throw result.error;
      }

      return result;
    },
    {
      // Each statement must see what was committed before it, or waiting for the lock is no use.
      isolationLevel: "ReadCommitted",
      // Above Prisma's 2 s and 5 s defaults, so a mutation that waits for the lock is not cut off.
      maxWait: 10_000,
      timeout: 20_000
    }
  );
  const committed = await transaction.catch((error: unknown) => {
    describeTransaction("rolled_back");
    throw error;
  });

  describeTransaction("committed");
  await deleteUnusedFiles(ctx.deleteStoredFiles, unusedFiles);

  return committed;
});
