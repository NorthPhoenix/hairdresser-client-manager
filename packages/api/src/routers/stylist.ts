import { z } from "zod";
import { expectedUpdatedAt, refuseOutdatedSave, text, textLimits, timezoneText } from "../input";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

// The device defaults only create a Stylist: stylistProcedure checks them when it uses them.
const stylistBootstrapInput = z.object({
  deviceLanguage: z.enum(["ru", "en"]).optional(),
  deviceTimezone: text(textLimits.timezone).min(1).optional()
}).optional();

const stylistSettingsInput = z.object({
  language: z.enum(["ru", "en"]),
  timezone: timezoneText,
  salonAddress: text(textLimits.address).optional(),
  // The updatedAt of the Stylist the settings were loaded from.
  expectedUpdatedAt: expectedUpdatedAt.optional()
});

function toStylistOutput(
  stylist: {
    id: string;
    clerkId: string;
    language: "ru" | "en";
    timezone: string;
    salonAddress: string | null;
    onboardingCompletedAt: Date | null;
    updatedAt: Date;
  },
  photoOrigins: string[]
) {
  return {
    id: stylist.id,
    clerkId: stylist.clerkId,
    language: stylist.language,
    timezone: stylist.timezone,
    salonAddress: stylist.salonAddress ?? "",
    onboardingCompletedAt: stylist.onboardingCompletedAt?.toISOString() ?? null,
    onboardingComplete: Boolean(stylist.onboardingCompletedAt),
    updatedAt: stylist.updatedAt.toISOString(),
    // Not about the Stylist but about this server: without photo storage no Appointment Photo
    // can be added, and the app says so instead of offering an upload that cannot work.
    photoStorageConfigured: photoOrigins.length > 0
  };
}

export const stylistRouter = createTRPCRouter({
  bootstrap: stylistProcedure.input(stylistBootstrapInput).query(({ ctx }) => {
    return toStylistOutput(ctx.stylist, ctx.photoOrigins);
  }),
  saveSettings: stylistTransactionProcedure.input(stylistSettingsInput).mutation(async ({ ctx, input }) => {
    const salonAddress = input.salonAddress ?? "";

    if (input.expectedUpdatedAt) {
      // ctx.stylist was read before the lock.
      const current = await ctx.db.stylist.findUniqueOrThrow({
        where: {
          id: ctx.stylist.id
        }
      });

      refuseOutdatedSave(
        current,
        input.expectedUpdatedAt,
        current.language !== input.language ||
          current.timezone !== input.timezone ||
          (current.salonAddress ?? "") !== salonAddress
      );
    }

    const stylist = await ctx.db.stylist.update({
      where: {
        id: ctx.stylist.id
      },
      data: {
        language: input.language,
        timezone: input.timezone,
        salonAddress,
        onboardingCompletedAt: ctx.stylist.onboardingCompletedAt ?? new Date()
      }
    });

    // In-salon Appointments that are still Scheduled show the salon's current address, also those
    // created before there was one. Appointments with an outcome keep the address they had.
    await ctx.db.appointment.updateMany({
      where: {
        stylistId: stylist.id,
        status: "scheduled",
        locationType: "inSalon"
      },
      data: {
        locationAddress: stylist.salonAddress ?? ""
      }
    });

    return toStylistOutput(stylist, ctx.photoOrigins);
  })
});
