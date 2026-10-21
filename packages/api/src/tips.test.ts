import { describe, expect, it, vi } from "vitest";
import { maxAmountCents } from "./input";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { firstIssueOf, withTransaction } from "./testSupport";

const stylist = {
  id: "stylist_1",
  clerkId: "clerk_1",
  language: "ru",
  timezone: "America/Chicago",
  salonAddress: "500 Salon Ave",
  onboardingCompletedAt: new Date(),
  createdAt: new Date(),
  updatedAt: new Date()
};

const startsAt = new Date("2026-10-16T23:00:00.000Z");

/**
 * One completed Appointment kept in memory, with two Services ($50 and $35, $85 together), and
 * just the calls `appointment.update`, `appointment.get` and `appointment.list` make. `update`
 * writes what it is given, as Prisma does, and leaves every other column as it is.
 */
function createTipDb(stored: { finalTotalCentsOverride?: number | null; tipCents?: number | null } = {}) {
  const row: Record<string, unknown> = {
    id: "appointment_1",
    stylistId: "stylist_1",
    primaryClientId: "client_1",
    startsAt,
    endsAt: null,
    status: "completed",
    locationType: "inSalon",
    locationAddress: "500 Salon Ave",
    locationAddressIsCustom: false,
    note: "",
    finalTotalCentsOverride: stored.finalTotalCentsOverride ?? null,
    tipCents: stored.tipCents ?? null,
    requestKey: null,
    createdAt: startsAt,
    updatedAt: startsAt
  };
  const read = () => ({
    ...row,
    primaryClient: {
      id: "client_1",
      name: "Света",
      address: "820 Elm Ave",
      phone: "",
      language: "ru"
    },
    participants: [
      {
        id: "participant_1",
        createdAt: startsAt,
        client: {
          id: "client_1",
          name: "Света",
          address: "820 Elm Ave",
          phone: "",
          language: "ru"
        }
      }
    ],
    services: [
      {
        id: "service_1",
        appointmentId: "appointment_1",
        clientId: "client_1",
        menuItemId: null,
        name: "Стрижка концов",
        priceCents: 5000,
        note: "",
        createdAt: startsAt,
        colorFormulas: []
      },
      {
        id: "service_2",
        appointmentId: "appointment_1",
        clientId: "client_1",
        menuItemId: null,
        name: "Стрижка детская",
        priceCents: 3500,
        note: "",
        createdAt: new Date(startsAt.getTime() + 1000),
        colorFormulas: []
      }
    ],
    photos: []
  });
  const db = {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUniqueOrThrow: vi.fn().mockResolvedValue(stylist)
    },
    appointment: {
      findFirst: vi.fn(async () => read()),
      findUniqueOrThrow: vi.fn(async () => read()),
      findMany: vi.fn(async () => [read()]),
      update: vi.fn(async ({ data }: { data: Record<string, unknown> }) => {
        Object.assign(row, data);

        return read();
      })
    },
    appointmentPhoto: {
      findMany: vi.fn().mockResolvedValue([])
    },
    serviceMenuItem: {
      findFirst: vi.fn().mockResolvedValue(null)
    }
  };
  const caller = appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
    photoOrigins: [],
    stylist
  } as unknown as TRPCContext);

  return { caller, db, row };
}

describe("tips", () => {
  it("records a tip apart from the price of the Services", async () => {
    const { caller, db } = createTipDb();

    await expect(caller.appointment.update({ id: "appointment_1", tipCents: 1500 })).resolves.toMatchObject({
      serviceTotalCents: 8500,
      // The totals stay the price of the Services, as an app that does not know tips reads them.
      finalTotalCents: 8500,
      finalTotalCentsOverride: null,
      tipCents: 1500
    });
    expect(db.appointment.update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        tipCents: 1500
      },
      include: expect.any(Object)
    });
  });

  it("clears a tip with null", async () => {
    const { caller, row } = createTipDb({ tipCents: 1500 });

    await expect(caller.appointment.update({ id: "appointment_1", tipCents: null })).resolves.toMatchObject({
      finalTotalCents: 8500,
      tipCents: null
    });
    expect(row.tipCents).toBeNull();
  });

  it("answers the same tip sent again with the same Appointment", async () => {
    const { caller } = createTipDb();

    const first = await caller.appointment.update({ id: "appointment_1", tipCents: 1000 });
    const again = await caller.appointment.update({ id: "appointment_1", tipCents: 1000 });

    expect(again).toEqual(first);
    expect(again.tipCents).toBe(1000);
  });

  it("keeps a tip that a save without it does not mention, such as the custom total of an older app", async () => {
    const { caller, db, row } = createTipDb({ tipCents: 1500 });

    // What the app 1.0.1 sends for "Своя итоговая сумма", and for "Вернуть сумму услуг".
    await expect(caller.appointment.update({ id: "appointment_1", finalTotalCentsOverride: 9000 })).resolves.toMatchObject({
      serviceTotalCents: 8500,
      finalTotalCents: 9000,
      finalTotalCentsOverride: 9000,
      tipCents: 1500
    });
    await expect(caller.appointment.update({ id: "appointment_1", finalTotalCentsOverride: null })).resolves.toMatchObject({
      finalTotalCents: 8500,
      finalTotalCentsOverride: null,
      tipCents: 1500
    });
    // And the other fields an older app saves.
    await caller.appointment.update({ id: "appointment_1", status: "scheduled" });
    await caller.appointment.update({ id: "appointment_1", note: "Пришла с дочкой" });

    for (const [{ data }] of db.appointment.update.mock.calls as unknown as [{ data: Record<string, unknown> }][]) {
      expect(data).not.toHaveProperty("tipCents");
    }
    expect(row.tipCents).toBe(1500);
  });

  it("leaves the custom total as it is when only the tip is saved", async () => {
    const { caller } = createTipDb({ finalTotalCentsOverride: 7000 });

    await expect(caller.appointment.update({ id: "appointment_1", tipCents: 500 })).resolves.toMatchObject({
      finalTotalCents: 7000,
      finalTotalCentsOverride: 7000,
      tipCents: 500
    });
  });

  it("moves the difference of a custom total above the Services into the tip in one save", async () => {
    // $100 for $85 of Services: how the first tips were recorded, before there were tips.
    const { caller, db } = createTipDb({ finalTotalCentsOverride: 10000 });

    await expect(
      caller.appointment.update({ id: "appointment_1", finalTotalCentsOverride: null, tipCents: 1500 })
    ).resolves.toMatchObject({
      serviceTotalCents: 8500,
      finalTotalCents: 8500,
      finalTotalCentsOverride: null,
      tipCents: 1500
    });
    expect(db.appointment.update).toHaveBeenCalledTimes(1);
    expect(db.appointment.update).toHaveBeenCalledWith(
      expect.objectContaining({
        data: {
          finalTotalCentsOverride: null,
          tipCents: 1500
        }
      })
    );
  });

  it("refuses a tip that is not an amount", async () => {
    const { caller, db } = createTipDb();

    await expect(firstIssueOf(caller.appointment.update({ id: "appointment_1", tipCents: maxAmountCents + 1 }))).resolves.toEqual({
      code: "BAD_REQUEST",
      message: "Amount is too large.",
      field: "tipCents"
    });
    await expect(firstIssueOf(caller.appointment.update({ id: "appointment_1", tipCents: -500 }))).resolves.toMatchObject({
      code: "BAD_REQUEST",
      field: "tipCents"
    });
    await expect(firstIssueOf(caller.appointment.update({ id: "appointment_1", tipCents: 10.5 }))).resolves.toMatchObject({
      code: "BAD_REQUEST",
      field: "tipCents"
    });
    expect(db.appointment.update).not.toHaveBeenCalled();
  });

  it("takes the largest amount as a tip on top of the largest custom total", async () => {
    const { caller } = createTipDb({ finalTotalCentsOverride: maxAmountCents });

    await expect(caller.appointment.update({ id: "appointment_1", tipCents: maxAmountCents })).resolves.toMatchObject({
      finalTotalCents: maxAmountCents,
      tipCents: maxAmountCents
    });
  });

  it("puts the tip into every read of the Appointment, and null when there is none", async () => {
    const tipped = createTipDb({ tipCents: 1000 });
    const none = createTipDb();
    const range = { start: "2026-10-12T05:00:00.000Z", end: "2026-10-19T05:00:00.000Z" };

    await expect(tipped.caller.appointment.get({ id: "appointment_1" })).resolves.toMatchObject({ tipCents: 1000, finalTotalCents: 8500 });
    await expect(tipped.caller.appointment.list(range)).resolves.toMatchObject([{ tipCents: 1000, finalTotalCents: 8500 }]);
    await expect(none.caller.appointment.get({ id: "appointment_1" })).resolves.toMatchObject({ tipCents: null });
    await expect(none.caller.appointment.list(range)).resolves.toMatchObject([{ tipCents: null }]);
  });

  it("puts the tip of the whole visit into the Client's Appointment history", async () => {
    const appointment = (tipCents: number | null) => ({
      id: `appointment_${tipCents}`,
      primaryClientId: "client_1",
      startsAt,
      status: "completed",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      locationAddressIsCustom: false,
      tipCents,
      primaryClient: { id: "client_1", name: "Света", address: "" },
      participants: [{ clientId: "client_1", client: { id: "client_1", name: "Света", address: "" } }],
      services: [],
      photos: []
    });
    const caller = appRouter.createCaller({
      clerkAuth: { userId: "clerk_1" },
      db: {
        stylist: { upsert: vi.fn().mockResolvedValue(stylist) },
        appointment: { findMany: vi.fn().mockResolvedValue([appointment(1500), appointment(null)]) }
      },
      photoOrigins: [],
      stylist
    } as unknown as TRPCContext);

    await expect(caller.clientProfile.appointmentHistory({ id: "client_1" })).resolves.toMatchObject([
      { id: "appointment_1500", tipCents: 1500 },
      { id: "appointment_null", tipCents: null }
    ]);
  });
});
