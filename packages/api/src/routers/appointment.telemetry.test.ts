import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TRPCContext } from "../context";
import { appRouter } from "../root";
import { withTransaction } from "../testSupport";

const stylist = {
  id: "stylist_1",
  clerkId: "clerk_1",
  language: "en",
  timezone: "America/Chicago",
  salonAddress: "500 Salon Ave",
  onboardingCompletedAt: new Date(),
  createdAt: new Date(),
  updatedAt: new Date()
};

const startsAt = new Date("2026-06-13T15:00:00.000Z");

// An Appointment row as the procedures read it, with the personal details an event must never show.
const appointmentRow = {
  id: "appointment_1",
  stylistId: "stylist_1",
  primaryClientId: "client_1",
  startsAt,
  endsAt: null,
  status: "scheduled",
  locationType: "inSalon",
  locationAddress: "500 Salon Ave",
  locationAddressIsCustom: false,
  note: "Allergic to ammonia",
  finalTotalCentsOverride: null,
  tipCents: null,
  primaryClient: {
    id: "client_1",
    name: "Anna Petrova",
    address: "10 Client St",
    phone: "+1 312 555 0100",
    language: "ru"
  },
  participants: [
    {
      id: "participant_1",
      createdAt: startsAt,
      client: {
        id: "client_1",
        name: "Anna Petrova",
        address: "10 Client St",
        phone: "+1 312 555 0100",
        language: "ru"
      }
    }
  ],
  services: [
    {
      id: "service_1",
      appointmentId: "appointment_1",
      clientId: "client_1",
      menuItemId: "menu_1",
      name: "Root touch-up",
      priceCents: 9000,
      note: "",
      createdAt: startsAt,
      colorFormulas: [{ id: "formula_1", appointmentServiceId: "service_1", formula: "7N + 20 vol", placement: "Roots", createdAt: startsAt }]
    }
  ],
  photos: []
};

let telemetry: ReturnType<typeof setUpTelemetry>;

beforeEach(() => {
  telemetry = setUpTelemetry();
});

afterEach(tearDownTelemetry);

function callerFor(models: Record<string, unknown>) {
  const db = withTransaction({
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUniqueOrThrow: vi.fn().mockResolvedValue(stylist)
    },
    serviceMenuItem: {
      findFirst: vi.fn().mockResolvedValue(null)
    },
    ...models
  });

  return appRouter.createCaller({
    clerkAuth: { userId: "clerk_1" },
    db,
    photoOrigins: [],
    unusedFiles: []
  } as unknown as TRPCContext);
}

/** Runs one procedure call as an HTTP request and returns its wide event, as printed. */
async function eventOf(call: () => Promise<unknown>) {
  await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
    try {
      await call();
      return { status: 200 };
    } catch {
      // A refusal is answered with a 4xx, as the route handler does.
      return { status: 400 };
    }
  });

  expect(telemetry.lines).toHaveLength(1);

  const line = telemetry.lines[0] ?? "";

  // Nothing personal of the Client gets into the line, whatever the procedure read.
  for (const personal of ["Anna", "Petrova", "Client St", "Salon Ave", "555", "ammonia", "7N", "Roots", "Root touch-up"]) {
    expect(line).not.toContain(personal);
  }

  const [event] = telemetry.events();

  expect(event).not.toHaveProperty("telemetry.blocked_keys");
  return event ?? {};
}

describe("the wide event of the appointment router", () => {
  it("describes a new Appointment and the overlap it has", async () => {
    const overlapping = { ...appointmentRow, id: "appointment_0", startsAt: new Date("2026-06-13T14:45:00.000Z"), services: [] };
    const caller = callerFor({
      client: { findFirst: vi.fn().mockResolvedValue(appointmentRow.primaryClient) },
      appointment: {
        findMany: vi.fn().mockResolvedValue([overlapping]),
        create: vi.fn().mockResolvedValue({ ...appointmentRow, note: "" })
      }
    });

    const event = await eventOf(() => caller.appointment.create({ primaryClientId: "client_1", startsAt: startsAt.toISOString() }));

    expect(event).toMatchObject({
      outcome: "success",
      "trpc.procedure": "appointment.create",
      "stylist.id": "stylist_1",
      "appointment.id": "appointment_1",
      "appointment.primary_client_id": "client_1",
      "appointment.primary_client_source": "existing",
      "appointment.status": "scheduled",
      "appointment.location_type": "in_salon",
      "appointment.location_source": "salon",
      "appointment.has_location_address": true,
      // 15:00 UTC is 10:00 in Chicago in June.
      "appointment.start_local_hour": 10,
      "appointment.overlap_check": "overlaps",
      "appointment.conflict_count": 1,
      "appointment.conflict_candidate_count": 1,
      "appointment.conflict_ids": ["appointment_0"],
      "appointment.travel_fee_change": "not_applicable",
      "appointment.client_count": 1,
      "appointment.service_count": 1,
      "appointment.color_formula_count": 1,
      "appointment.service_total_minor": 9000,
      "appointment.final_total_minor": 9000,
      "appointment.has_total_override": false
    });
  });

  it("records why an edit was refused", async () => {
    const caller = callerFor({ appointment: { findFirst: vi.fn().mockResolvedValue(appointmentRow) } });

    const event = await eventOf(() =>
      caller.appointment.update({ id: "appointment_1", endsAt: new Date("2026-06-13T14:00:00.000Z").toISOString() })
    );

    expect(event).toMatchObject({
      outcome: "refused",
      "appointment.id": "appointment_1",
      "appointment.previous_status": "scheduled",
      "error.type": "TRPCError",
      "error.code": "BAD_REQUEST",
      "error.reason": "end_not_after_start",
      "error.expected": true
    });
  });

  it("names the fields an edit changed, and tells a save that changes nothing", async () => {
    const changed = callerFor({
      appointment: {
        findFirst: vi.fn().mockResolvedValue(appointmentRow),
        update: vi.fn().mockResolvedValue({ ...appointmentRow, status: "completed", tipCents: 1500 })
      }
    });

    expect(await eventOf(() => changed.appointment.update({ id: "appointment_1", status: "completed", tipCents: 1500 }))).toMatchObject({
      "appointment.changed_fields": ["status", "tip"],
      "appointment.changed_field_count": 2,
      "appointment.is_noop_save": false,
      "appointment.previous_status": "scheduled",
      "appointment.status": "completed",
      "appointment.tip_minor": 1500,
      "appointment.overlap_check": "skipped",
      "appointment.travel_fee_change": "not_applicable",
      "appointment.location_source": "unchanged"
    });

    telemetry.lines.length = 0;

    const unchanged = callerFor({
      appointment: {
        findFirst: vi.fn().mockResolvedValue(appointmentRow),
        update: vi.fn().mockResolvedValue(appointmentRow)
      }
    });

    expect(await eventOf(() => unchanged.appointment.update({ id: "appointment_1", status: "scheduled", note: appointmentRow.note }))).toMatchObject({
      "appointment.changed_fields": [],
      "appointment.is_noop_save": true,
      // The note's length, never the note.
      "appointment.note_length": appointmentRow.note.length
    });
  });

  it("counts what Appointment Copying copied", async () => {
    const caller = callerFor({
      appointment: {
        findFirst: vi
          .fn()
          .mockResolvedValueOnce({ id: "appointment_1", participants: [{ clientId: "client_1" }], services: [] })
          .mockResolvedValueOnce({
            id: "source_1",
            status: "completed",
            startsAt: new Date(Date.now() - 42 * 24 * 60 * 60 * 1000),
            participants: [{ clientId: "client_1" }],
            services: appointmentRow.services
          }),
        findUniqueOrThrow: vi.fn().mockResolvedValue(appointmentRow)
      },
      appointmentService: { create: vi.fn() }
    });

    expect(await eventOf(() => caller.appointment.copyServices({ targetAppointmentId: "appointment_1" }))).toMatchObject({
      "appointment.id": "appointment_1",
      "appointment.copy_source_choice": "latest",
      "appointment.copy_source_id": "source_1",
      "appointment.copy_source_age_days": 42,
      "appointment_service.copied_count": 1,
      "appointment_service.copied_minor": 9000,
      "color_formula.copied_count": 1,
      "appointment.service_total_before_minor": 0,
      "appointment.service_total_change_minor": 9000
    });
  });

  it("tells a delete of an Appointment that is already gone", async () => {
    const caller = callerFor({
      appointment: { deleteMany: vi.fn().mockResolvedValue({ count: 0 }) },
      appointmentPhoto: { findMany: vi.fn().mockResolvedValue([]) }
    });

    expect(await eventOf(() => caller.appointment.delete({ id: "appointment_1" }))).toMatchObject({
      outcome: "success",
      "appointment.id": "appointment_1",
      "appointment.delete_result": "not_found",
      "appointment.photo_count": 0
    });
  });
});
