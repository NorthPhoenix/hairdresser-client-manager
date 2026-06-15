import { initTRPC, TRPCError } from "@trpc/server";
import type { TRPCContext } from "./context";

const t = initTRPC.context<TRPCContext>().create();

export const createTRPCRouter = t.router;
export const publicProcedure = t.procedure;

export const protectedProcedure = t.procedure.use(({ ctx, next }) => {
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

export const stylistProcedure = protectedProcedure.use(async ({ ctx, getRawInput, next }) => {
  const rawInput = await getRawInput();
  const input =
    rawInput && typeof rawInput === "object" && !Array.isArray(rawInput)
      ? (rawInput as { deviceLanguage?: "ru" | "en"; deviceTimezone?: string })
      : {};
  const stylist = await ctx.db.stylist.upsert({
    where: {
      clerkId: ctx.clerkAuth.userId
    },
    update: {},
    create: {
      clerkId: ctx.clerkAuth.userId,
      language: input.deviceLanguage ?? "ru",
      timezone: input.deviceTimezone ?? "UTC"
    }
  });

  return next({
    ctx: {
      ...ctx,
      stylist
    }
  });
});
