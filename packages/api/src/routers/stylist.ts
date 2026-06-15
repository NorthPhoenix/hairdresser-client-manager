import { z } from "zod";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const stylistBootstrapInput = z.object({
  deviceLanguage: z.enum(["ru", "en"]).optional(),
  deviceTimezone: z.string().trim().min(1).optional()
}).optional();

const stylistSettingsInput = z.object({
  language: z.enum(["ru", "en"]),
  timezone: z.string().trim().min(1),
  salonAddress: z.string().trim().optional()
});

function toStylistOutput(stylist: {
  id: string;
  clerkId: string;
  language: "ru" | "en";
  timezone: string;
  salonAddress: string | null;
  onboardingCompletedAt: Date | null;
}) {
  return {
    id: stylist.id,
    clerkId: stylist.clerkId,
    language: stylist.language,
    timezone: stylist.timezone,
    salonAddress: stylist.salonAddress ?? "",
    onboardingCompletedAt: stylist.onboardingCompletedAt?.toISOString() ?? null,
    onboardingComplete: Boolean(stylist.onboardingCompletedAt)
  };
}

export const stylistRouter = createTRPCRouter({
  bootstrap: stylistProcedure.input(stylistBootstrapInput).query(({ ctx }) => {
    return toStylistOutput(ctx.stylist);
  }),
  saveSettings: stylistProcedure.input(stylistSettingsInput).mutation(async ({ ctx, input }) => {
    const stylist = await ctx.db.stylist.update({
      where: {
        id: ctx.stylist.id
      },
      data: {
        language: input.language,
        timezone: input.timezone,
        salonAddress: input.salonAddress ?? "",
        onboardingCompletedAt: ctx.stylist.onboardingCompletedAt ?? new Date()
      }
    });

    return toStylistOutput(stylist);
  })
});
