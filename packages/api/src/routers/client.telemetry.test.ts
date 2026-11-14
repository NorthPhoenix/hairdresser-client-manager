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

const loadedAt = new Date("2026-06-01T12:00:00.000Z");

// A Client row with every personal detail an event must never show.
function clientRow(id: string, fields: Record<string, unknown> = {}) {
  return {
    id,
    stylistId: "stylist_1",
    name: "Anna Petrova",
    language: "ru",
    phone: "+1 312 555 0100",
    email: "anna@example.com",
    address: "10 Client St",
    note: "Allergic to ammonia",
    requestKey: null,
    referredById: null,
    familyId: null,
    familyRole: null,
    createdAt: loadedAt,
    updatedAt: loadedAt,
    ...fields
  };
}

// As the list and the reply to a merge read a Client.
function listedRow(id: string, fields: Record<string, unknown> = {}) {
  return {
    ...clientRow(id, fields),
    referredBy: null,
    profileShares: [],
    distinctPairsAsFirst: [],
    distinctPairsAsSecond: []
  };
}

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

  // Nothing personal of a Client gets into the line, whatever the procedure read or was sent.
  for (const personal of ["Anna", "Petrova", "Maria", "555", "example.com", "Client St", "ammonia", "сын", "Petr"]) {
    expect(line).not.toContain(personal);
  }

  const [event] = telemetry.events();

  expect(event).not.toHaveProperty("telemetry.blocked_keys");
  return event ?? {};
}

describe("the wide event of the client router", () => {
  it("describes a save by the names of the details it changes", async () => {
    const existing = clientRow("client_1");
    const caller = callerFor({
      client: {
        findFirst: vi.fn().mockResolvedValue(existing),
        update: vi.fn(async ({ data }: { data: Record<string, unknown> }) => ({ ...existing, ...data }))
      }
    });

    const event = await eventOf(() =>
      caller.clientProfile.save({
        id: "client_1",
        name: "Anna Petrova",
        language: "ru",
        phone: "+1 312 555 0199",
        email: "anna@example.com",
        address: "10 Client St",
        note: "Allergic to ammonia, use 6%",
        expectedUpdatedAt: loadedAt.toISOString()
      })
    );

    expect(event).toMatchObject({
      outcome: "success",
      "trpc.procedure": "clientProfile.save",
      "client.id": "client_1",
      "client.save_kind": "update",
      "client.save_result": "updated",
      "client.changed_fields": ["phone", "note"],
      "client.changed_field_count": 2,
      "client.referrer_change": "not_stated",
      "client.language": "ru",
      "client.language_source": "sent",
      "client.has_phone": true,
      "client.has_email": true,
      "client.note_length": "Allergic to ammonia, use 6%".length,
      "client.has_expected_updated_at": true
    });
  });

  it("records the reason a Client recommended by themselves is refused", async () => {
    const caller = callerFor({
      client: {
        findFirst: vi.fn().mockResolvedValue(clientRow("client_1")),
        update: vi.fn()
      }
    });

    const event = await eventOf(() => caller.clientProfile.save({ id: "client_1", name: "Anna Petrova", referredById: "client_1" }));

    expect(event).toMatchObject({
      outcome: "refused",
      "client.id": "client_1",
      "client.referrer_change": "set",
      "error.code": "BAD_REQUEST",
      "error.reason": "client_referred_by_self",
      "error.expected": true
    });
  });

  it("tells a list's size and whether it was searched, never the search", async () => {
    const caller = callerFor({
      client: {
        findMany: vi.fn().mockResolvedValue([listedRow("client_1", { familyId: "family_1" }), listedRow("client_2")])
      }
    });

    const event = await eventOf(() => caller.clientProfile.list({ search: "Petr" }));

    expect(event).toMatchObject({
      "client.has_search": true,
      "client.search_length": 4,
      "client.count": 2,
      "client.with_family_count": 1,
      "client.with_profile_share_count": 0
    });
  });

  it("describes a merge: what moved, what the kept Client took, and how each Appointment changed", async () => {
    const kept = clientRow("client_keep", { phone: "", note: "Allergic to ammonia" });
    const removed = clientRow("client_remove", { name: "Maria", note: "Prefers mornings", referredById: "client_9" });
    const findFirst = vi.fn(async ({ where }: { where: { id: string } }) => (where.id === kept.id ? kept : removed));
    const caller = callerFor({
      client: {
        findFirst,
        update: vi.fn().mockResolvedValue(kept),
        updateMany: vi.fn().mockResolvedValue({ count: 1 }),
        delete: vi.fn().mockResolvedValue(removed),
        findUniqueOrThrow: vi.fn().mockResolvedValue(listedRow("client_keep"))
      },
      appointment: {
        findMany: vi.fn().mockResolvedValue([
          {
            id: "appointment_1",
            primaryClientId: "client_remove",
            status: "completed",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            locationAddressIsCustom: false,
            primaryClient: { address: "10 Client St" },
            participants: [{ id: "participant_1", clientId: "client_remove" }]
          },
          {
            id: "appointment_2",
            primaryClientId: "client_keep",
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            locationAddressIsCustom: false,
            primaryClient: { address: "10 Client St" },
            participants: [
              { id: "participant_2", clientId: "client_keep" },
              { id: "participant_3", clientId: "client_remove" }
            ]
          }
        ]),
        update: vi.fn().mockResolvedValue({})
      },
      appointmentParticipant: {
        update: vi.fn().mockResolvedValue({}),
        delete: vi.fn().mockResolvedValue({})
      },
      appointmentService: { updateMany: vi.fn().mockResolvedValue({ count: 3 }) },
      appointmentPhoto: { updateMany: vi.fn().mockResolvedValue({ count: 2 }) },
      profileShare: {
        findFirst: vi.fn().mockResolvedValue({ id: "share_keep" }),
        updateMany: vi.fn().mockResolvedValue({ count: 1 })
      },
      clientDistinctPair: {
        findMany: vi.fn().mockResolvedValue([]),
        deleteMany: vi.fn().mockResolvedValue({ count: 0 })
      },
      clientMerge: {
        updateMany: vi.fn().mockResolvedValue({ count: 0 }),
        create: vi.fn().mockResolvedValue({})
      }
    });

    const event = await eventOf(() => caller.clientProfile.merge({ keepId: "client_keep", removeId: "client_remove" }));

    expect(event).toMatchObject({
      outcome: "success",
      "trpc.procedure": "clientProfile.merge",
      "client.id": "client_keep",
      "client_merge.removed_client_id": "client_remove",
      "client_merge.result": "merged",
      "client_merge.taken_fields": ["phone"],
      "client_merge.is_note_combined": true,
      "client_merge.is_note_taken": false,
      "client_merge.referrer_source": "removed",
      "client_merge.family_merge": "none",
      "client_merge.appointment_count": 2,
      "client_merge.appointment_ids": ["appointment_1", "appointment_2"],
      "client_merge.shared_appointment_count": 1,
      "client_merge.participant_moved_count": 1,
      "client_merge.primary_changed_count": 1,
      "client_merge.primary_past_kept_place_count": 1,
      "client_merge.service_moved_count": 3,
      "client_merge.photo_moved_count": 2,
      "client_merge.kept_has_profile_share": true,
      "client_merge.profile_share_revoked_count": 1,
      "client_merge.referral_moved_count": 1
    });
  });

  it("answers a merge sent again as done, and says so", async () => {
    const kept = clientRow("client_keep");
    const caller = callerFor({
      client: {
        findFirst: vi.fn(async ({ where }: { where: { id: string } }) => (where.id === kept.id ? kept : null)),
        findUniqueOrThrow: vi.fn().mockResolvedValue(listedRow("client_keep"))
      },
      clientMerge: { findFirst: vi.fn().mockResolvedValue({ id: "merge_1" }) }
    });

    const event = await eventOf(() => caller.clientProfile.merge({ keepId: "client_keep", removeId: "client_remove" }));

    expect(event).toMatchObject({ outcome: "success", "client_merge.result": "repeat" });
  });

  it("tells a delete sent again from a delete", async () => {
    const caller = callerFor({
      client: {
        findFirst: vi.fn().mockResolvedValue(null),
        deleteMany: vi.fn().mockResolvedValue({ count: 0 })
      },
      appointmentPhoto: { findMany: vi.fn().mockResolvedValue([]) },
      appointment: { findMany: vi.fn().mockResolvedValue([]) }
    });

    const event = await eventOf(() => caller.clientProfile.delete({ id: "client_1" }));

    expect(event).toMatchObject({
      outcome: "success",
      "client.id": "client_1",
      "client.delete_result": "not_found",
      "client.deleted_count": 0,
      "client.appointment_count": 0,
      "client.photo_count": 0
    });
  });

  it("names the path a Family member was added by, never the role", async () => {
    const client = { id: "client_1", familyId: null, familyRole: null };
    const member = { id: "client_2", familyId: null, familyRole: null };
    const caller = callerFor({
      client: {
        findFirst: vi.fn(async ({ where }: { where: { id: string } }) => (where.id === client.id ? client : member)),
        update: vi.fn().mockResolvedValue({}),
        findMany: vi.fn().mockResolvedValue([
          listedRow("client_1", { familyId: "family_new" }),
          listedRow("client_2", { familyId: "family_new", familyRole: "сын" })
        ])
      },
      family: { create: vi.fn().mockResolvedValue({ id: "family_new" }) }
    });

    const event = await eventOf(() => caller.clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "сын" }));

    expect(event).toMatchObject({
      outcome: "success",
      "client.id": "client_1",
      "family.id": "family_new",
      "family.added_client_id": "client_2",
      "family.add_path": "new_family",
      "family.is_role_stated": true,
      "family.is_role_changed": true,
      "family.changed_client_count": 2,
      "family.reply_client_count": 2
    });
  });
});
