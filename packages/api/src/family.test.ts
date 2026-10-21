import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

type Client = {
  id: string;
  stylistId: string;
  name: string;
  language: "ru" | "en";
  phone: string | null;
  email: string | null;
  address: string | null;
  note: string | null;
  requestKey: string | null;
  referredById: string | null;
  familyId: string | null;
  familyRole: string | null;
  createdAt: Date;
  updatedAt: Date;
};
type Family = { id: string; stylistId: string };
type Appointment = { id: string; stylistId: string; status: "scheduled" | "completed" | "canceled" | "noShow" };
type Participant = { appointmentId: string; clientId: string };

type State = {
  clients: Client[];
  families: Family[];
  appointments: Appointment[];
  participants: Participant[];
};

function client(id: string, fields: Partial<Client> = {}): Client {
  return {
    id,
    stylistId: "stylist_1",
    name: id,
    language: "ru",
    phone: "",
    email: "",
    address: "",
    note: "",
    requestKey: null,
    referredById: null,
    familyId: null,
    familyRole: null,
    createdAt: new Date(`2026-01-0${(Number(id.replace(/\D/g, "")) % 9) + 1}T00:00:00.000Z`),
    updatedAt: new Date("2026-01-01T00:00:00.000Z"),
    ...fields
  };
}

type ClientWhere = { id?: string | { in: string[] }; stylistId?: string; familyId?: string | null };

/**
 * Clients and Families in memory with the calls the Family procedures and a Client delete make.
 * Like the database, a deleted Family leaves its Clients without one (onDelete: SetNull), a
 * Client cannot be put in a Family that does not exist, and a failed transaction leaves nothing.
 */
function createStore(initial: Partial<State>) {
  let state: State = {
    clients: [],
    families: [],
    appointments: [],
    participants: [],
    ...structuredClone(initial)
  };
  let nextId = 1;
  let last: Promise<unknown> = Promise.resolve();
  const matches = (row: Client, where: ClientWhere) =>
    (where.id === undefined || (typeof where.id === "string" ? row.id === where.id : where.id.in.includes(row.id))) &&
    (where.stylistId === undefined || row.stylistId === where.stylistId) &&
    (where.familyId === undefined || row.familyId === where.familyId);
  const refuseMissingFamily = (familyId: string | null | undefined) => {
    if (familyId && !state.families.some((family) => family.id === familyId)) {
      throw Object.assign(new Error("P2003"), { name: "PrismaClientKnownRequestError", code: "P2003" });
    }
  };
  const listed = (row: Client) => ({
    ...row,
    referredBy: null,
    profileShares: [],
    distinctPairsAsFirst: [],
    distinctPairsAsSecond: []
  });

  const tx = {
    $executeRaw: vi.fn().mockResolvedValue(1),
    stylist: {
      upsert: vi.fn(async ({ where }: { where: { clerkId: string } }) => ({
        id: where.clerkId === "clerk_2" ? "stylist_2" : "stylist_1",
        clerkId: where.clerkId,
        language: "ru",
        timezone: "America/Chicago",
        salonAddress: null
      }))
    },
    client: {
      findFirst: vi.fn(async ({ where }: { where: ClientWhere }) => structuredClone(state.clients.find((row) => matches(row, where)) ?? null)),
      findMany: vi.fn(async ({ where, orderBy }: { where: ClientWhere; orderBy?: unknown }) => {
        const rows = state.clients.filter((row) => matches(row, where));

        if (orderBy) {
          rows.sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime() || a.id.localeCompare(b.id));
        }

        return structuredClone(rows.map(listed));
      }),
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: Partial<Client> }) => {
        const row = state.clients.find((candidate) => candidate.id === where.id);

        if (!row) {
          throw Object.assign(new Error("P2025"), { name: "PrismaClientKnownRequestError", code: "P2025" });
        }

        refuseMissingFamily(data.familyId);
        Object.assign(row, data, { updatedAt: new Date("2026-10-21T12:00:00.000Z") });
        return structuredClone(row);
      }),
      updateMany: vi.fn(async ({ where, data }: { where: ClientWhere; data: Partial<Client> }) => {
        refuseMissingFamily(data.familyId);
        const rows = state.clients.filter((row) => matches(row, where));

        rows.forEach((row) => Object.assign(row, data, { updatedAt: new Date("2026-10-21T12:00:00.000Z") }));
        return { count: rows.length };
      }),
      deleteMany: vi.fn(async ({ where }: { where: ClientWhere }) => {
        const before = state.clients.length;

        state.clients = state.clients.filter((row) => !matches(row, where));
        return { count: before - state.clients.length };
      })
    },
    family: {
      create: vi.fn(async ({ data }: { data: { stylistId: string } }) => {
        const row = { id: `family_new_${nextId++}`, stylistId: data.stylistId };

        state.families.push(row);
        return structuredClone(row);
      }),
      deleteMany: vi.fn(async ({ where }: { where: { id: string; stylistId: string } }) => {
        const before = state.families.length;

        state.families = state.families.filter((row) => !(row.id === where.id && row.stylistId === where.stylistId));
        state.clients.forEach((row) => {
          if (row.familyId === where.id) {
            row.familyId = null;
          }
        });
        return { count: before - state.families.length };
      })
    },
    appointmentParticipant: {
      groupBy: vi.fn(
        async ({ where }: { where: { clientId: { in: string[] }; appointment: { stylistId: string; status: Appointment["status"] } } }) => {
          const counts = new Map<string, number>();

          state.participants
            .filter(
              (row) =>
                where.clientId.in.includes(row.clientId) &&
                state.appointments.some(
                  (appointment) =>
                    appointment.id === row.appointmentId &&
                    appointment.stylistId === where.appointment.stylistId &&
                    appointment.status === where.appointment.status
                )
            )
            .forEach((row) => counts.set(row.clientId, (counts.get(row.clientId) ?? 0) + 1));

          return [...counts].map(([clientId, count]) => ({ clientId, _count: { _all: count } }));
        }
      ),
      deleteMany: vi.fn()
    },
    appointment: {
      findMany: vi.fn().mockResolvedValue([])
    },
    appointmentPhoto: {
      findMany: vi.fn().mockResolvedValue([])
    }
  };

  const db = {
    ...tx,
    $transaction: vi.fn((run: (transaction: unknown) => Promise<unknown>) => {
      const result = last.then(async () => {
        const before = structuredClone(state);

        try {
          return await run(tx);
        } catch (error) {
          state = before;
          throw error;
        }
      });

      last = result.catch(() => undefined);
      return result;
    })
  };

  function caller(clerkId = "clerk_1") {
    return appRouter.createCaller({
      clerkAuth: {
        userId: clerkId
      },
      db,
      deleteStoredFiles: vi.fn()
    } as unknown as TRPCContext);
  }

  return {
    caller,
    tx,
    get state() {
      return state;
    }
  };
}

const familyOf = (state: State, id: string) => state.clients.find((row) => row.id === id)?.familyId ?? null;
const roleOf = (state: State, id: string) => state.clients.find((row) => row.id === id)?.familyRole ?? null;

describe("Family", () => {
  it("puts two Clients without a Family in a new one, with the member's role", async () => {
    const store = createStore({ clients: [client("client_1", { name: "Оксана" }), client("client_2", { name: "Антони" })] });

    const result = await store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "  сын " });

    expect(store.state.families).toHaveLength(1);
    const familyId = store.state.families[0]!.id;
    expect(familyOf(store.state, "client_1")).toBe(familyId);
    expect(familyOf(store.state, "client_2")).toBe(familyId);
    expect(roleOf(store.state, "client_1")).toBeNull();
    expect(roleOf(store.state, "client_2")).toBe("сын");
    expect(result.familyId).toBe(familyId);
    expect(result.clients.map((row) => [row.id, row.familyId, row.familyRole]).sort()).toEqual([
      ["client_1", familyId, ""],
      ["client_2", familyId, "сын"]
    ]);
  });

  it("adds a third Client to the Family the first is in, and the list states who is in it", async () => {
    const store = createStore({
      clients: [
        client("client_1", { familyId: "family_1" }),
        client("client_2", { familyId: "family_1", familyRole: "сын" }),
        client("client_3")
      ],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    await store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_3", role: "дочь" });

    expect(familyOf(store.state, "client_3")).toBe("family_1");
    expect(roleOf(store.state, "client_3")).toBe("дочь");
    expect(store.state.families).toHaveLength(1);

    const list = await store.caller().clientProfile.list();

    expect(list.map((row) => [row.id, row.familyId, row.familyRole])).toEqual([
      ["client_1", "family_1", ""],
      ["client_2", "family_1", "сын"],
      ["client_3", "family_1", "дочь"]
    ]);
  });

  it("puts a Client without a Family in the Family of the member", async () => {
    const store = createStore({
      clients: [
        client("client_1"),
        client("client_2", { familyId: "family_1", familyRole: "сын" }),
        client("client_3", { familyId: "family_1" })
      ],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    await store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2" });

    expect(familyOf(store.state, "client_1")).toBe("family_1");
    expect(roleOf(store.state, "client_2")).toBe("сын");
    expect(store.state.families).toHaveLength(1);
  });

  it("moves a member out of another Family, and deletes that Family when one Client would be left in it", async () => {
    const store = createStore({
      clients: [
        client("client_1", { familyId: "family_1" }),
        client("client_2", { familyId: "family_1" }),
        client("client_3", { familyId: "family_2", familyRole: "сын" }),
        client("client_4", { familyId: "family_2", familyRole: "папа" })
      ],
      families: [
        { id: "family_1", stylistId: "stylist_1" },
        { id: "family_2", stylistId: "stylist_1" }
      ]
    });

    const result = await store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_3" });

    expect(familyOf(store.state, "client_3")).toBe("family_1");
    // A role belongs to the Family it was typed for.
    expect(roleOf(store.state, "client_3")).toBeNull();
    expect(familyOf(store.state, "client_4")).toBeNull();
    expect(roleOf(store.state, "client_4")).toBeNull();
    expect(store.state.families.map((row) => row.id)).toEqual(["family_1"]);
    // The Client who was left alone is in the answer, so the app shows them without their Family.
    expect(result.clients.map((row) => row.id).sort()).toEqual(["client_1", "client_3", "client_4"]);
  });

  it("changes nothing when the same member is added again, and only sets the role when one is stated", async () => {
    const store = createStore({ clients: [client("client_1"), client("client_2")] });
    const caller = store.caller();

    await caller.clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "сын" });
    const families = structuredClone(store.state.families);
    await caller.clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "сын" });
    await caller.clientProfile.addFamilyMember({ clientId: "client_2", memberId: "client_1" });

    expect(store.state.families).toEqual(families);
    expect(roleOf(store.state, "client_2")).toBe("сын");

    await caller.clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "внук" });

    expect(roleOf(store.state, "client_2")).toBe("внук");
  });

  it("refuses a Client in a Family with themselves, and a Client of another Stylist", async () => {
    const store = createStore({ clients: [client("client_1"), client("client_2", { stylistId: "stylist_2" })] });

    await expect(store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_1" })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
    await expect(store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2" })).rejects.toMatchObject({
      code: "NOT_FOUND"
    });
    await expect(store.caller("clerk_2").clientProfile.addFamilyMember({ clientId: "client_2", memberId: "client_1" })).rejects.toMatchObject({
      code: "NOT_FOUND"
    });
    expect(store.state.families).toEqual([]);
  });

  it("refuses a role longer than 40 characters", async () => {
    const store = createStore({ clients: [client("client_1"), client("client_2")] });

    await expect(
      store.caller().clientProfile.addFamilyMember({ clientId: "client_1", memberId: "client_2", role: "a".repeat(41) })
    ).rejects.toMatchObject({ code: "BAD_REQUEST" });
    expect(store.state.families).toEqual([]);
  });

  it("sets and clears a role, and refuses one for a Client in no Family", async () => {
    const store = createStore({
      clients: [client("client_1", { familyId: "family_1" }), client("client_2", { familyId: "family_1" }), client("client_3")],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    const result = await store.caller().clientProfile.setFamilyRole({ clientId: "client_2", role: "мама\nКати" });

    expect(roleOf(store.state, "client_2")).toBe("мама Кати");
    expect(result.clients).toMatchObject([{ id: "client_2", familyId: "family_1", familyRole: "мама Кати" }]);

    await store.caller().clientProfile.setFamilyRole({ clientId: "client_2", role: "  " });

    expect(roleOf(store.state, "client_2")).toBeNull();
    await expect(store.caller().clientProfile.setFamilyRole({ clientId: "client_3", role: "сын" })).rejects.toMatchObject({ code: "NOT_FOUND" });
  });

  it("takes a Client out of a Family of three, which goes on", async () => {
    const store = createStore({
      clients: [
        client("client_1", { familyId: "family_1" }),
        client("client_2", { familyId: "family_1", familyRole: "сын" }),
        client("client_3", { familyId: "family_1" })
      ],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    const result = await store.caller().clientProfile.leaveFamily({ clientId: "client_2" });

    expect(familyOf(store.state, "client_2")).toBeNull();
    expect(roleOf(store.state, "client_2")).toBeNull();
    expect(familyOf(store.state, "client_1")).toBe("family_1");
    expect(familyOf(store.state, "client_3")).toBe("family_1");
    expect(result).toMatchObject({ familyId: null, clients: [{ id: "client_2", familyId: null, familyRole: "" }] });
  });

  it("deletes a Family of two when one leaves it, and a second leave changes nothing", async () => {
    const store = createStore({
      clients: [client("client_1", { familyId: "family_1", familyRole: "мама" }), client("client_2", { familyId: "family_1" })],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    const result = await store.caller().clientProfile.leaveFamily({ clientId: "client_2" });

    expect(store.state.families).toEqual([]);
    expect(store.state.clients.map((row) => [row.familyId, row.familyRole])).toEqual([
      [null, null],
      [null, null]
    ]);
    expect(result.clients.map((row) => row.id).sort()).toEqual(["client_1", "client_2"]);

    const again = await store.caller().clientProfile.leaveFamily({ clientId: "client_2" });

    expect(again).toMatchObject({ familyId: null, clients: [{ id: "client_2" }] });
  });

  it("deletes the Family of a deleted Client when one Client would be left in it", async () => {
    const store = createStore({
      clients: [client("client_1", { familyId: "family_1", familyRole: "мама" }), client("client_2", { familyId: "family_1" })],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    await store.caller().clientProfile.delete({ id: "client_2" });

    expect(store.state.families).toEqual([]);
    expect(store.state.clients).toMatchObject([{ id: "client_1", familyId: null, familyRole: null }]);
  });

  it("keeps the Family of a deleted Client when two are left in it", async () => {
    const store = createStore({
      clients: [
        client("client_1", { familyId: "family_1" }),
        client("client_2", { familyId: "family_1" }),
        client("client_3", { familyId: "family_1", familyRole: "сын" })
      ],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });

    await store.caller().clientProfile.delete({ id: "client_2" });

    expect(store.state.families).toHaveLength(1);
    expect(store.state.clients.map((row) => [row.id, row.familyId, row.familyRole])).toEqual([
      ["client_1", "family_1", null],
      ["client_3", "family_1", "сын"]
    ]);
  });

  it("reads a Family with each Client's role and number of visits, oldest Client first", async () => {
    const store = createStore({
      clients: [
        client("client_2", { name: "Антони", familyId: "family_1", familyRole: "сын" }),
        client("client_1", { name: "Оксана", familyId: "family_1" }),
        client("client_3", { name: "Маша", familyId: "family_1", familyRole: "дочь" }),
        client("client_4")
      ],
      families: [{ id: "family_1", stylistId: "stylist_1" }],
      appointments: [
        { id: "appointment_1", stylistId: "stylist_1", status: "completed" },
        { id: "appointment_2", stylistId: "stylist_1", status: "completed" },
        { id: "appointment_3", stylistId: "stylist_1", status: "canceled" },
        { id: "appointment_4", stylistId: "stylist_1", status: "scheduled" }
      ],
      participants: [
        { appointmentId: "appointment_1", clientId: "client_1" },
        { appointmentId: "appointment_1", clientId: "client_2" },
        { appointmentId: "appointment_2", clientId: "client_2" },
        { appointmentId: "appointment_3", clientId: "client_3" },
        { appointmentId: "appointment_4", clientId: "client_3" }
      ]
    });

    await expect(store.caller().clientProfile.family({ clientId: "client_3" })).resolves.toEqual({
      familyId: "family_1",
      members: [
        { id: "client_1", name: "Оксана", role: "", visits: 1 },
        { id: "client_2", name: "Антони", role: "сын", visits: 2 },
        { id: "client_3", name: "Маша", role: "дочь", visits: 0 }
      ]
    });
    await expect(store.caller().clientProfile.family({ clientId: "client_4" })).resolves.toEqual({ familyId: null, members: [] });
    await expect(store.caller("clerk_2").clientProfile.family({ clientId: "client_1" })).rejects.toMatchObject({ code: "NOT_FOUND" });
  });

  it("leaves the Family alone in a save from an app that does not know of Families", async () => {
    const store = createStore({
      clients: [client("client_1", { familyId: "family_1", familyRole: "мама" }), client("client_2", { familyId: "family_1" })],
      families: [{ id: "family_1", stylistId: "stylist_1" }]
    });
    const update = store.tx.client.update;

    update.mockClear();
    // The input the app 1.0.1 sends: no Family fields.
    await store.caller().clientProfile.save({
      id: "client_1",
      name: "Оксана",
      language: "ru",
      phone: "",
      email: "",
      address: "",
      note: "Любит мягкие слои."
    });

    expect(update).toHaveBeenCalledTimes(1);
    expect(Object.keys(update.mock.calls[0]![0].data)).not.toContain("familyId");
    expect(Object.keys(update.mock.calls[0]![0].data)).not.toContain("familyRole");
    expect(store.state.clients[0]).toMatchObject({ familyId: "family_1", familyRole: "мама", note: "Любит мягкие слои." });
  });

  it("is stored in a new table and two nullable columns, so the schema change is additive", () => {
    const schema = readFileSync(new URL("../../db/prisma/schema.prisma", import.meta.url), "utf8");

    expect(schema).toMatch(/familyId\s+String\?\n/);
    expect(schema).toMatch(/familyRole\s+String\?\n/);
    expect(schema).toMatch(/family\s+Family\?\s+@relation\(fields: \[familyId\], references: \[id\], onDelete: SetNull\)/);
    expect(schema).toMatch(/model Family \{/);
  });
});
