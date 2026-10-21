import { afterEach, describe, expect, it, vi } from "vitest";
import { periodBounds, parseLocalDate, startOfLocalDay, topServices } from "./earnings";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

const chicago = "America/Chicago";

function bounds(period: "week" | "month" | "year", date: string, timezone = chicago) {
  const parsed = parseLocalDate(date);

  if (!parsed) {
    throw new Error(`Bad date ${date}`);
  }

  const result = periodBounds(period, parsed, timezone);

  return { startsAt: result.startsAt.toISOString(), endsAt: result.endsAt.toISOString() };
}

describe("periods of Итоги", () => {
  it("starts a week on Monday at midnight in the Stylist's timezone", () => {
    // Wednesday 21 Oct 2026; Chicago is on daylight time (UTC-5).
    expect(bounds("week", "2026-10-21")).toEqual({
      startsAt: "2026-10-19T05:00:00.000Z",
      endsAt: "2026-10-26T05:00:00.000Z"
    });
    // Monday itself and Sunday belong to the same week.
    expect(bounds("week", "2026-10-19")).toEqual(bounds("week", "2026-10-25"));
    expect(bounds("week", "2026-10-26").startsAt).toBe("2026-10-26T05:00:00.000Z");
  });

  it("takes a calendar month and a calendar year", () => {
    expect(bounds("month", "2026-10-31")).toEqual({
      startsAt: "2026-10-01T05:00:00.000Z",
      // 1 Nov is still on daylight time; the clocks go back on 1 Nov at 2:00.
      endsAt: "2026-11-01T05:00:00.000Z"
    });
    expect(bounds("month", "2026-12-15")).toEqual({
      startsAt: "2026-12-01T06:00:00.000Z",
      endsAt: "2027-01-01T06:00:00.000Z"
    });
    expect(bounds("year", "2026-06-30")).toEqual({
      startsAt: "2026-01-01T06:00:00.000Z",
      endsAt: "2027-01-01T06:00:00.000Z"
    });
  });

  it("keeps a day that is 23 or 25 hours long whole", () => {
    // The week of the clocks going back (Sun 1 Nov 2026) is 7 days and one hour long.
    const week = bounds("week", "2026-11-01");

    expect(week).toEqual({ startsAt: "2026-10-26T05:00:00.000Z", endsAt: "2026-11-02T06:00:00.000Z" });
    // And the clocks going forward (Sun 8 Mar 2026).
    expect(bounds("week", "2026-03-08")).toEqual({
      startsAt: "2026-03-02T06:00:00.000Z",
      endsAt: "2026-03-09T05:00:00.000Z"
    });
  });

  it("crosses a year in the week and a leap February", () => {
    expect(bounds("week", "2026-12-31").endsAt).toBe("2027-01-04T06:00:00.000Z");
    expect(bounds("month", "2028-02-10").endsAt).toBe("2028-03-01T06:00:00.000Z");
  });

  it("works in a timezone ahead of UTC", () => {
    expect(startOfLocalDay("Asia/Tokyo", { year: 2026, month: 10, day: 21 }).toISOString()).toBe(
      "2026-10-20T15:00:00.000Z"
    );
  });

  it("refuses dates that do not exist", () => {
    expect(parseLocalDate("2026-02-30")).toBeNull();
    expect(parseLocalDate("21.10.2026")).toBeNull();
    expect(parseLocalDate("2026-10-21")).toEqual({ year: 2026, month: 10, day: 21 });
  });
});

describe("top services", () => {
  it("groups by the group of the menu item, else by the name, and keeps five", () => {
    const groups = new Map([
      ["cut_f", "Стрижки"],
      ["cut_m", "Стрижки"]
    ]);
    const top = topServices(
      [
        { name: "Стрижка женская", priceCents: 5000, menuItemId: "cut_f" },
        { name: "Стрижка мужская", priceCents: 3000, menuItemId: "cut_m" },
        { name: "  стрижки ", priceCents: 1000, menuItemId: null },
        { name: "Укладка", priceCents: 2000, menuItemId: null },
        { name: "Укладка", priceCents: 2500, menuItemId: null },
        { name: "Ёлка", priceCents: 100, menuItemId: null },
        { name: "елка", priceCents: 100, menuItemId: null },
        { name: "A", priceCents: 10, menuItemId: null },
        { name: "B", priceCents: 20, menuItemId: null },
        { name: "C", priceCents: 30, menuItemId: null }
      ],
      groups
    );

    expect(top).toEqual([
      // The ungrouped "стрижка" shares the group's name.
      { name: "Стрижки", revenueCents: 9000, count: 3 },
      { name: "Укладка", revenueCents: 4500, count: 2 },
      { name: "Ёлка", revenueCents: 200, count: 2 },
      { name: "C", revenueCents: 30, count: 1 },
      { name: "B", revenueCents: 20, count: 1 }
    ]);
  });
});

const stylist = {
  id: "stylist_1",
  clerkId: "clerk_1",
  language: "ru",
  timezone: chicago,
  salonAddress: "",
  onboardingCompletedAt: new Date(),
  createdAt: new Date(),
  updatedAt: new Date()
};

type Stored = {
  status: "scheduled" | "completed" | "canceled" | "noShow";
  startsAt: string;
  locationType?: "inSalon" | "atHome";
  finalTotalCentsOverride?: number | null;
  tipCents?: number | null;
  services?: { name: string; priceCents: number; menuItemId?: string | null }[];
};

/** Appointments in memory with the few queries `stats.summary` makes, filtered as Prisma would. */
function createStatsDb(stored: Stored[], menuItems: { id: string; group: string | null }[] = []) {
  const matching = (where: {
    stylistId: string;
    status: string | { in: string[] };
    startsAt: { gte: Date; lt: Date };
  }) =>
    stored.filter((appointment) => {
      const startsAt = new Date(appointment.startsAt);
      const statuses = typeof where.status === "string" ? [where.status] : where.status.in;

      return statuses.includes(appointment.status) && startsAt >= where.startsAt.gte && startsAt < where.startsAt.lt;
    });
  const db = {
    stylist: { upsert: vi.fn().mockResolvedValue(stylist) },
    appointment: {
      findMany: vi.fn(async ({ where }: { where: Parameters<typeof matching>[0] }) =>
        matching(where).map((appointment) => ({
          locationType: appointment.locationType ?? "inSalon",
          finalTotalCentsOverride: appointment.finalTotalCentsOverride ?? null,
          tipCents: appointment.tipCents ?? null,
          services: (appointment.services ?? []).map((service) => ({ menuItemId: null, ...service }))
        }))
      ),
      groupBy: vi.fn(async ({ where }: { where: Parameters<typeof matching>[0] }) => {
        const counts = new Map<string, number>();

        for (const appointment of matching(where)) {
          counts.set(appointment.status, (counts.get(appointment.status) ?? 0) + 1);
        }

        return [...counts].map(([status, count]) => ({ status, _count: { _all: count } }));
      })
    },
    serviceMenuItem: {
      findMany: vi.fn(async ({ where }: { where: { id: { in: string[] } } }) =>
        menuItems.filter((item) => where.id.in.includes(item.id) && item.group !== null)
      )
    }
  };
  const caller = appRouter.createCaller({
    clerkAuth: { userId: "clerk_1" },
    db,
    photoOrigins: [],
    stylist
  } as unknown as TRPCContext);

  return { caller, db };
}

describe("stats.summary", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  // 21 Oct 2026, 12:00 in Chicago.
  const now = new Date("2026-10-21T17:00:00.000Z");
  const october: Stored[] = [
    {
      status: "completed",
      startsAt: "2026-10-05T20:00:00.000Z",
      locationType: "atHome",
      tipCents: 1500,
      services: [
        { name: "Стрижка женская", priceCents: 5000, menuItemId: "cut" },
        { name: "Выезд на дом", priceCents: 2000 }
      ]
    },
    {
      // A custom total replaces the sum of the Services; the tip is added on top.
      status: "completed",
      startsAt: "2026-10-12T20:00:00.000Z",
      locationType: "atHome",
      finalTotalCentsOverride: 9000,
      tipCents: 1000,
      services: [{ name: "Покраска", priceCents: 8000, menuItemId: "color" }]
    },
    { status: "completed", startsAt: "2026-10-14T20:00:00.000Z", tipCents: null, services: [] },
    { status: "canceled", startsAt: "2026-10-08T20:00:00.000Z", services: [{ name: "Стрижка", priceCents: 1 }] },
    { status: "noShow", startsAt: "2026-10-09T20:00:00.000Z" },
    { status: "noShow", startsAt: "2026-10-10T20:00:00.000Z" },
    // Not completed, not future: neither earned nor booked.
    { status: "scheduled", startsAt: "2026-10-20T20:00:00.000Z", services: [{ name: "X", priceCents: 999 }] },
    { status: "scheduled", startsAt: "2026-10-23T20:00:00.000Z", tipCents: 500, services: [{ name: "Y", priceCents: 4000 }] },
    { status: "scheduled", startsAt: "2026-10-30T20:00:00.000Z", services: [{ name: "Z", priceCents: 6000 }] },
    // Other months.
    { status: "completed", startsAt: "2026-09-30T20:00:00.000Z", services: [{ name: "Old", priceCents: 7700 }] },
    { status: "scheduled", startsAt: "2026-11-03T20:00:00.000Z", services: [{ name: "Later", priceCents: 1234 }] }
  ];
  const items = [
    { id: "cut", group: "Стрижки" },
    { id: "color", group: null }
  ];

  it("adds up the completed Appointments of the month", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller } = createStatsDb(october, items);

    await expect(caller.stats.summary({ period: "month" })).resolves.toEqual({
      period: "month",
      firstDay: "2026-10-01",
      lastDay: "2026-10-31",
      startsAt: "2026-10-01T05:00:00.000Z",
      endsAt: "2026-11-01T05:00:00.000Z",
      today: "2026-10-21",
      isCurrent: true,
      // 7000 + 9000 (custom total) + 0, and 1500 + 1000 in tips.
      earned: { totalCents: 18500, servicesCents: 16000, tipsCents: 2500 },
      visits: 3,
      averageCents: 6167,
      atHomeVisits: 2,
      canceled: 1,
      noShow: 2,
      upcoming: { count: 2, totalCents: 10500 },
      topServices: [
        { name: "Покраска", revenueCents: 8000, count: 1 },
        { name: "Стрижки", revenueCents: 5000, count: 1 },
        { name: "Выезд на дом", revenueCents: 2000, count: 1 }
      ]
    });
  });

  it("has no booked part once the period is over, and a past month never counts the future", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller } = createStatsDb(october, items);
    const september = await caller.stats.summary({ period: "month", date: "2026-09-15" });

    expect(september).toMatchObject({
      firstDay: "2026-09-01",
      isCurrent: false,
      visits: 1,
      earned: { totalCents: 7700, servicesCents: 7700, tipsCents: 0 },
      upcoming: null
    });
  });

  it("counts a future period's scheduled Appointments from its start", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller } = createStatsDb(october, items);

    await expect(caller.stats.summary({ period: "month", date: "2026-11-20" })).resolves.toMatchObject({
      isCurrent: false,
      visits: 0,
      earned: { totalCents: 0 },
      averageCents: 0,
      upcoming: { count: 1, totalCents: 1234 },
      topServices: []
    });
  });

  it("starts the booked part at now, not at the start of the week", async () => {
    // Wednesday 21 Oct 12:00; the Appointment on Tuesday 20th is before now.
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller, db } = createStatsDb(october, items);
    const week = await caller.stats.summary({ period: "week" });

    expect(week).toMatchObject({ firstDay: "2026-10-19", lastDay: "2026-10-25", visits: 0, upcoming: { count: 1, totalCents: 4500 } });
    expect(db.appointment.findMany.mock.calls.at(-1)?.[0].where.startsAt.gte).toEqual(now);
  });

  it("puts an Appointment on the Stylist's local day, not the UTC day", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller } = createStatsDb(
      [
        // Sunday 25 Oct 23:30 in Chicago is already Monday 26 Oct in UTC.
        { status: "completed", startsAt: "2026-10-26T04:30:00.000Z", services: [{ name: "A", priceCents: 1000 }] },
        // Monday 26 Oct 00:30 in Chicago.
        { status: "completed", startsAt: "2026-10-26T05:30:00.000Z", services: [{ name: "B", priceCents: 2000 }] }
      ],
      []
    );

    await expect(caller.stats.summary({ period: "week", date: "2026-10-22" })).resolves.toMatchObject({
      visits: 1,
      earned: { totalCents: 1000 }
    });
    await expect(caller.stats.summary({ period: "week", date: "2026-10-27" })).resolves.toMatchObject({
      visits: 1,
      earned: { totalCents: 2000 }
    });
  });

  it("answers an empty period with zeros", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller, db } = createStatsDb([]);

    await expect(caller.stats.summary({ period: "year", date: "2024-05-05" })).resolves.toMatchObject({
      visits: 0,
      earned: { totalCents: 0, servicesCents: 0, tipsCents: 0 },
      canceled: 0,
      noShow: 0,
      upcoming: null,
      topServices: []
    });
    // No Service, so no menu lookup.
    expect(db.serviceMenuItem.findMany).not.toHaveBeenCalled();
  });

  it("reads only the Appointments of the period and of this Stylist", async () => {
    vi.useFakeTimers({ now, toFake: ["Date"] });

    const { caller, db } = createStatsDb(october, items);

    await caller.stats.summary({ period: "month" });

    for (const [{ where }] of [...db.appointment.findMany.mock.calls, ...db.appointment.groupBy.mock.calls] as unknown as [
      { where: { stylistId: string; startsAt: { gte: Date; lt: Date } } }
    ][]) {
      expect(where.stylistId).toBe("stylist_1");
      expect(where.startsAt.gte.getTime()).toBeGreaterThanOrEqual(new Date("2026-10-01T05:00:00.000Z").getTime());
      expect(where.startsAt.lt.getTime()).toBeLessThanOrEqual(new Date("2026-11-01T05:00:00.000Z").getTime());
    }
    expect(db.serviceMenuItem.findMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: expect.objectContaining({ stylistId: "stylist_1" }) })
    );
  });

  it("refuses a date that does not exist or a period it does not know", async () => {
    const { caller } = createStatsDb([]);

    await expect(caller.stats.summary({ period: "month", date: "2026-02-31" })).rejects.toMatchObject({ code: "BAD_REQUEST" });
    await expect(caller.stats.summary({ period: "month", date: "1850-01-01" })).rejects.toMatchObject({ code: "BAD_REQUEST" });
    await expect(
      caller.stats.summary({ period: "day" as unknown as "week" })
    ).rejects.toMatchObject({ code: "BAD_REQUEST" });
  });
});
