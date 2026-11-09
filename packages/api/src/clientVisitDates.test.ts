import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

type Status = "scheduled" | "completed" | "canceled" | "noShow";
type Appointment = { id: string; stylistId: string; status: Status; startsAt: Date };
type Participant = { appointmentId: string; clientId: string };
type AppointmentWhere = {
  stylistId: string;
  OR: ({ status: Status; startsAt?: { gt: Date } })[];
};

const NOW = new Date("2026-11-08T18:00:00.000Z");

function createStore(appointments: Appointment[], participants: Participant[]) {
  const matches = (appointment: Appointment, where: AppointmentWhere) =>
    appointment.stylistId === where.stylistId &&
    where.OR.some((one) => appointment.status === one.status && (!one.startsAt || appointment.startsAt > one.startsAt.gt));
  const findMany = vi.fn(async ({ where }: { where: { appointment: AppointmentWhere } }) =>
    participants.flatMap((participant) => {
      const appointment = appointments.find((row) => row.id === participant.appointmentId);

      return appointment && matches(appointment, where.appointment)
        ? [{ clientId: participant.clientId, appointment: { status: appointment.status, startsAt: appointment.startsAt } }]
        : [];
    })
  );
  const db = {
    stylist: {
      upsert: vi.fn(async ({ where }: { where: { clerkId: string } }) => ({
        id: where.clerkId === "clerk_2" ? "stylist_2" : "stylist_1",
        clerkId: where.clerkId,
        language: "ru",
        timezone: "America/Chicago",
        salonAddress: null
      }))
    },
    appointmentParticipant: { findMany }
  };

  return {
    findMany,
    caller: (clerkId = "clerk_1") =>
      appRouter.createCaller({ clerkAuth: { userId: clerkId }, db, deleteStoredFiles: vi.fn() } as unknown as TRPCContext)
  };
}

const appointment = (id: string, status: Status, startsAt: string, stylistId = "stylist_1"): Appointment => ({
  id,
  stylistId,
  status,
  startsAt: new Date(startsAt)
});

describe("clientProfile.visitDates", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("gives each Client the start of their last visit and of their next Appointment that has not started", async () => {
    const store = createStore(
      [
        appointment("a_old", "completed", "2026-10-01T16:00:00.000Z"),
        appointment("a_last", "completed", "2026-11-03T20:00:00.000Z"),
        appointment("a_far", "scheduled", "2026-12-15T17:00:00.000Z"),
        appointment("a_next", "scheduled", "2026-11-21T00:00:00.000Z")
      ],
      [
        { appointmentId: "a_old", clientId: "client_1" },
        { appointmentId: "a_last", clientId: "client_1" },
        { appointmentId: "a_far", clientId: "client_1" },
        { appointmentId: "a_next", clientId: "client_1" }
      ]
    );

    await expect(store.caller().clientProfile.visitDates()).resolves.toEqual([
      { clientId: "client_1", lastVisitAt: "2026-11-03T20:00:00.000Z", nextAppointmentAt: "2026-11-21T00:00:00.000Z" }
    ]);
  });

  it("asks only for the Stylist's Completed Appointments and the Scheduled ones that start after now", async () => {
    const store = createStore([], []);

    await store.caller().clientProfile.visitDates();

    expect(store.findMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: {
          appointment: {
            stylistId: "stylist_1",
            OR: [{ status: "completed" }, { status: "scheduled", startsAt: { gt: NOW } }]
          }
        }
      })
    );
  });

  it("counts a group Appointment for every Client in it, and leaves out what is no visit and nothing ahead", async () => {
    const store = createStore(
      [
        appointment("group", "completed", "2026-11-06T15:30:00.000Z"),
        appointment("canceled", "canceled", "2026-11-02T17:30:00.000Z"),
        appointment("no_show", "noShow", "2026-11-04T16:00:00.000Z"),
        // Started an hour ago and not marked yet: no visit, and not ahead either.
        appointment("unresolved", "scheduled", "2026-11-08T17:00:00.000Z"),
        appointment("other_stylist", "completed", "2026-11-05T16:00:00.000Z", "stylist_2")
      ],
      [
        { appointmentId: "group", clientId: "client_primary" },
        { appointmentId: "group", clientId: "client_daughter" },
        { appointmentId: "canceled", clientId: "client_canceled" },
        { appointmentId: "no_show", clientId: "client_no_show" },
        { appointmentId: "unresolved", clientId: "client_unresolved" },
        { appointmentId: "other_stylist", clientId: "client_elsewhere" }
      ]
    );

    await expect(store.caller().clientProfile.visitDates()).resolves.toEqual([
      { clientId: "client_primary", lastVisitAt: "2026-11-06T15:30:00.000Z", nextAppointmentAt: null },
      { clientId: "client_daughter", lastVisitAt: "2026-11-06T15:30:00.000Z", nextAppointmentAt: null }
    ]);
  });

  it("gives a Client with only an Appointment ahead no last visit", async () => {
    const store = createStore(
      [appointment("first", "scheduled", "2026-11-09T17:00:00.000Z")],
      [{ appointmentId: "first", clientId: "client_new" }]
    );

    await expect(store.caller().clientProfile.visitDates()).resolves.toEqual([
      { clientId: "client_new", lastVisitAt: null, nextAppointmentAt: "2026-11-09T17:00:00.000Z" }
    ]);
  });
});
