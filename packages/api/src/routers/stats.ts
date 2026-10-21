import { TRPCError } from "@trpc/server";
import { z } from "zod";
import {
  appointmentAmount,
  earningsPeriods,
  formatLocalDate,
  localDateOf,
  parseLocalDate,
  periodBounds,
  topServices,
  type RevenueLine
} from "../earnings";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const summaryInput = z.object({
  period: z.enum(earningsPeriods),
  // Any day of the period, "2026-10-21", on the Stylist's calendar. Left out: today.
  date: z
    .string()
    .refine((value) => {
      const date = parseLocalDate(value);

      return date !== null && date.year >= 2000 && date.year <= 2100;
    }, "Date is not valid.")
    .optional()
});

export const statsRouter = createTRPCRouter({
  /**
   * Итоги: what a week, month or year of the Stylist's Appointments came to. An Appointment
   * belongs to the period its start time is in, on the Stylist's calendar. Computed here from the
   * Appointments of the period only, never from the whole history.
   */
  summary: stylistProcedure.input(summaryInput).query(async ({ ctx, input }) => {
    const timezone = ctx.stylist.timezone;
    const now = new Date();
    const today = localDateOf(timezone, now);
    const date = input.date ? parseLocalDate(input.date) : today;

    if (!date) {
      throw new TRPCError({ code: "BAD_REQUEST", message: "Date is not valid." });
    }

    const bounds = periodBounds(input.period, date, timezone);
    const inPeriod = { gte: bounds.startsAt, lt: bounds.endsAt };
    const stylistId = ctx.stylist.id;
    const current = bounds.startsAt <= now && now < bounds.endsAt;
    // Scheduled Appointments from now to the end of the period; none once the period is over.
    const bookedFrom = bounds.startsAt > now ? bounds.startsAt : now;
    const serviceColumns = { menuItemId: true, name: true, priceCents: true } as const;

    const [completed, otherOutcomes, booked] = await Promise.all([
      ctx.db.appointment.findMany({
        where: { stylistId, status: "completed", startsAt: inPeriod },
        select: {
          locationType: true,
          finalTotalCentsOverride: true,
          tipCents: true,
          services: { select: serviceColumns }
        }
      }),
      ctx.db.appointment.groupBy({
        by: ["status"],
        where: { stylistId, status: { in: ["canceled", "noShow"] }, startsAt: inPeriod },
        _count: { _all: true }
      }),
      bookedFrom < bounds.endsAt
        ? ctx.db.appointment.findMany({
            where: { stylistId, status: "scheduled", startsAt: { gte: bookedFrom, lt: bounds.endsAt } },
            select: {
              finalTotalCentsOverride: true,
              tipCents: true,
              services: { select: { priceCents: true } }
            }
          })
        : Promise.resolve([])
    ]);

    let servicesCents = 0;
    let tipsCents = 0;
    let atHome = 0;
    const lines: RevenueLine[] = [];

    for (const appointment of completed) {
      const amount = appointmentAmount(appointment);

      servicesCents += amount.servicesCents;
      tipsCents += amount.tipCents;

      if (appointment.locationType === "atHome") {
        atHome += 1;
      }

      lines.push(...appointment.services);
    }

    const itemIds = [...new Set(lines.flatMap((line) => (line.menuItemId ? [line.menuItemId] : [])))];
    const items = itemIds.length
      ? await ctx.db.serviceMenuItem.findMany({
          where: { stylistId, id: { in: itemIds }, group: { not: null } },
          select: { id: true, group: true }
        })
      : [];
    const groupOfItem = new Map(items.flatMap((item) => (item.group ? [[item.id, item.group] as const] : [])));
    const totalCents = servicesCents + tipsCents;
    const countOf = (status: "canceled" | "noShow") =>
      otherOutcomes.find((outcome) => outcome.status === status)?._count._all ?? 0;
    const upcoming = bounds.endsAt > now
      ? {
          count: booked.length,
          totalCents: booked.reduce((sum, appointment) => sum + appointmentAmount(appointment).totalCents, 0)
        }
      : null;

    return {
      period: input.period,
      // The days of the period on the Stylist's calendar, both included, and the instants they span.
      firstDay: formatLocalDate(bounds.firstDay),
      lastDay: formatLocalDate(bounds.lastDay),
      startsAt: bounds.startsAt.toISOString(),
      endsAt: bounds.endsAt.toISOString(),
      today: formatLocalDate(today),
      isCurrent: current,
      earned: {
        totalCents,
        servicesCents,
        tipsCents
      },
      visits: completed.length,
      averageCents: completed.length ? Math.round(totalCents / completed.length) : 0,
      atHomeVisits: atHome,
      canceled: countOf("canceled"),
      noShow: countOf("noShow"),
      // Null once the period is over.
      upcoming,
      topServices: topServices(lines, groupOfItem).map((service) => ({
        name: service.name,
        revenueCents: service.revenueCents,
        count: service.count
      }))
    };
  })
});
