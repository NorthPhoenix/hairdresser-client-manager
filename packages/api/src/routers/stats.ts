import { recordError, traced } from "@hcm/telemetry";
import { TRPCError } from "@trpc/server";
import { z } from "zod";
import {
  appointmentAmount,
  earningsPeriods,
  formatLocalDate,
  localDateOf,
  parseLocalDate,
  periodBounds,
  serviceRevenue,
  topServices,
  type RevenueLine
} from "../earnings";
import { annotateSafely } from "../input";
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
      const refusal = new TRPCError({ code: "BAD_REQUEST", message: "Date is not valid." });

      recordError(refusal, { expected: true, reason: "stats_date_not_valid" });
      throw refusal;
    }

    const bounds = periodBounds(input.period, date, timezone);
    const inPeriod = { gte: bounds.startsAt, lt: bounds.endsAt };
    const stylistId = ctx.stylist.id;
    const current = bounds.startsAt <= now && now < bounds.endsAt;
    annotateSafely(() => ({
      "stats.period": input.period,
      // Left out: today's period.
      "stats.has_date": input.date !== undefined,
      "stats.range_days": Math.round((bounds.endsAt.getTime() - bounds.startsAt.getTime()) / 86_400_000),
      "stats.is_current_period": current,
      "stats.period_offset_days": Math.round((bounds.startsAt.getTime() - now.getTime()) / 86_400_000)
    }));

    const serviceColumns = { menuItemId: true, name: true, priceCents: true } as const;

    const [completed, otherOutcomes, scheduled] = await Promise.all([
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
      ctx.db.appointment.findMany({
        where: { stylistId, status: "scheduled", startsAt: inPeriod },
        select: {
          startsAt: true,
          finalTotalCentsOverride: true,
          tipCents: true,
          services: { select: { priceCents: true } }
        }
      })
    ]);
    // A scheduled Appointment from now on is booked. One whose start time has passed is waiting for
    // an outcome: it is neither earned nor booked, and is counted apart until the Stylist marks it.
    const booked = scheduled.filter((appointment) => appointment.startsAt >= now);
    const unresolved = scheduled.filter((appointment) => appointment.startsAt < now);
    const amountOf = (appointments: typeof scheduled) =>
      appointments.reduce((sum, appointment) => sum + appointmentAmount(appointment).totalCents, 0);

    // The loop over the period's completed Appointments: its own unit of work, as a year has hundreds.
    const { servicesCents, tipsCents, atHome, lines, customTotalCount, tipCount } = await traced(
      "stats.aggregate_period",
      async (span) => {
        let servicesCents = 0;
        let tipsCents = 0;
        let atHome = 0;
        let customTotalCount = 0;
        let tipCount = 0;
        const lines: RevenueLine[] = [];

        for (const appointment of completed) {
          const amount = appointmentAmount(appointment);

          servicesCents += amount.servicesCents;
          tipsCents += amount.tipCents;

          if (appointment.locationType === "atHome") {
            atHome += 1;
          }

          if (appointment.finalTotalCentsOverride !== null) {
            customTotalCount += 1;
          }

          if (amount.tipCents > 0) {
            tipCount += 1;
          }

          // Under a custom total each Service counts its share of it, so the lines add up to Earned's Services.
          lines.push(...serviceRevenue(appointment));
        }

        span.annotate({ "stats.appointment_count": completed.length, "stats.service_line_count": lines.length });
        return { servicesCents, tipsCents, atHome, lines, customTotalCount, tipCount };
      }
    );

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
    const ranked = topServices(lines, groupOfItem);

    annotateSafely(() => ({
      "stats.completed_count": completed.length,
      "stats.canceled_count": countOf("canceled"),
      "stats.no_show_count": countOf("noShow"),
      "stats.scheduled_count": scheduled.length,
      "stats.booked_count": booked.length,
      "stats.unresolved_count": unresolved.length,
      "stats.at_home_count": atHome,
      "stats.custom_total_count": customTotalCount,
      "stats.tip_count": tipCount,
      "stats.service_line_count": lines.length,
      "stats.menu_item_count": itemIds.length,
      "stats.grouped_item_count": items.length,
      "stats.top_service_count": ranked.length,
      "stats.earned_minor": totalCents,
      "stats.services_minor": servicesCents,
      "stats.tips_minor": tipsCents,
      "stats.booked_minor": amountOf(booked),
      "stats.unresolved_minor": amountOf(unresolved),
      // Nothing happened in the period: the app shows its empty state.
      "stats.is_empty": completed.length + scheduled.length + countOf("canceled") + countOf("noShow") === 0
    }));

    const upcoming = bounds.endsAt > now
      ? {
          count: booked.length,
          totalCents: amountOf(booked)
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
      // Scheduled Appointments of the period whose start time has passed, waiting for an outcome
      // (Home's "Needs an outcome"). Apps before 1.15.0 do not read it.
      unresolved: { count: unresolved.length, totalCents: amountOf(unresolved) },
      topServices: ranked.map((service) => ({
        name: service.name,
        revenueCents: service.revenueCents,
        count: service.count
      }))
    };
  })
});
