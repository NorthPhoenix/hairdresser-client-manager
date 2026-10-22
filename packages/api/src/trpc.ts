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

export const createTRPCRouter = t.router;
export const publicProcedure = t.procedure.use(async ({ next }) => {
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

export const stylistProcedure = protectedProcedure.use(async ({ ctx, getRawInput, next }) => {
  const where = {
    clerkId: ctx.clerkAuth.userId
  };
  const rawInput = await getRawInput();
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
    throw new TRPCError({
      code: "BAD_REQUEST",
      cause: deviceDefaults.error
    });
  }

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
  const committed = await ctx.db.$transaction(
    async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${ctx.stylist.id}, 0))`;

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

  await deleteUnusedFiles(ctx.deleteStoredFiles, unusedFiles);

  return committed;
});
