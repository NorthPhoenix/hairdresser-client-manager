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
  createdAt: Date;
  updatedAt: Date;
};
type Appointment = {
  id: string;
  stylistId: string;
  primaryClientId: string;
  status: "scheduled" | "completed" | "canceled" | "noShow";
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
  locationAddressIsCustom: boolean | null;
};
type Participant = { id: string; appointmentId: string; clientId: string; createdAt: Date };
type Service = { id: string; appointmentId: string; clientId: string; name: string };
type Photo = { id: string; appointmentId: string; clientId: string };
type Share = { id: string; clientId: string; token: string; language: "ru" | "en"; revokedAt: Date | null; createdAt: Date };
type Pair = { id: string; stylistId: string; firstClientId: string; secondClientId: string };
type Merge = { id: string; stylistId: string; keptClientId: string; removedClientId: string };

type State = {
  clients: Client[];
  appointments: Appointment[];
  participants: Participant[];
  services: Service[];
  photos: Photo[];
  shares: Share[];
  pairs: Pair[];
  merges: Merge[];
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
    createdAt: new Date("2026-01-01T00:00:00.000Z"),
    updatedAt: new Date("2026-01-01T00:00:00.000Z"),
    ...fields
  };
}

function appointment(id: string, primaryClientId: string, fields: Partial<Appointment> = {}): Appointment {
  return {
    id,
    stylistId: "stylist_1",
    primaryClientId,
    status: "completed",
    locationType: "inSalon",
    locationAddress: "500 Salon Ave",
    locationAddressIsCustom: false,
    ...fields
  };
}

function databaseError(code: string) {
  return Object.assign(new Error(code), { name: "PrismaClientKnownRequestError", code });
}

/**
 * The tables that refer to a Client, kept in memory with the calls a merge makes. Like the
 * database it stands in for, it refuses a second row of one Client in one Appointment and the
 * delete of a Client that an Appointment names as primary, cascades a deleted Client's rows, and
 * leaves nothing of a transaction that failed part-way.
 */
function createStore(initial: Partial<State>) {
  let state: State = {
    clients: [],
    appointments: [],
    participants: [],
    services: [],
    photos: [],
    shares: [],
    pairs: [],
    merges: [],
    ...structuredClone(initial)
  };
  let nextId = 1;
  let last: Promise<unknown> = Promise.resolve();
  const failOn = new Set<string>();
  const id = (prefix: string) => `${prefix}_new_${nextId++}`;
  const fail = (step: string) => {
    if (failOn.has(step)) {
      throw new Error(`Failed at ${step}.`);
    }
  };
  const inAppointmentOf = (stylistId: string, appointmentId: string) =>
    state.appointments.some((row) => row.id === appointmentId && row.stylistId === stylistId);
  const refuseSecondParticipant = (appointmentId: string, clientId: string, except?: string) => {
    if (state.participants.some((row) => row.id !== except && row.appointmentId === appointmentId && row.clientId === clientId)) {
      throw databaseError("P2002");
    }
  };
  const withClientRelations = (row: Client) => ({
    ...row,
    profileShares: state.shares
      .filter((share) => share.clientId === row.id && !share.revokedAt)
      .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
      .slice(0, 1),
    distinctPairsAsFirst: state.pairs.filter((pair) => pair.firstClientId === row.id).map((pair) => ({ secondClientId: pair.secondClientId })),
    distinctPairsAsSecond: state.pairs.filter((pair) => pair.secondClientId === row.id).map((pair) => ({ firstClientId: pair.firstClientId }))
  });

  const tx = {
    $executeRaw: vi.fn().mockResolvedValue(1),
    stylist: {
      upsert: vi.fn(async ({ where }: { where: { clerkId: string } }) => ({
        id: where.clerkId === "clerk_2" ? "stylist_2" : "stylist_1",
        clerkId: where.clerkId,
        language: "ru",
        timezone: "America/Chicago",
        salonAddress: "500 Salon Ave"
      }))
    },
    client: {
      findFirst: vi.fn(async ({ where }: { where: { id: string; stylistId: string } }) =>
        structuredClone(state.clients.find((row) => row.id === where.id && row.stylistId === where.stylistId) ?? null)
      ),
      findMany: vi.fn(async ({ where }: { where: { id?: { in: string[] }; stylistId: string } }) =>
        state.clients
          .filter((row) => row.stylistId === where.stylistId && (!where.id || where.id.in.includes(row.id)))
          .map((row) => structuredClone(withClientRelations(row)))
      ),
      findUniqueOrThrow: vi.fn(async ({ where }: { where: { id: string } }) => {
        const row = state.clients.find((candidate) => candidate.id === where.id);

        if (!row) {
          throw databaseError("P2025");
        }

        return structuredClone(withClientRelations(row));
      }),
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: Partial<Client> }) => {
        fail("client.update");
        const row = state.clients.find((candidate) => candidate.id === where.id);

        if (!row) {
          throw databaseError("P2025");
        }

        Object.assign(row, data, { updatedAt: new Date("2026-10-21T12:00:00.000Z") });
        return structuredClone(row);
      }),
      delete: vi.fn(async ({ where }: { where: { id: string } }) => {
        fail("client.delete");
        const row = state.clients.find((candidate) => candidate.id === where.id);

        if (!row) {
          throw databaseError("P2025");
        }

        // Appointment.primaryClient is onDelete: Restrict.
        if (state.appointments.some((candidate) => candidate.primaryClientId === row.id)) {
          throw databaseError("P2003");
        }

        state.clients = state.clients.filter((candidate) => candidate.id !== row.id);
        state.participants = state.participants.filter((candidate) => candidate.clientId !== row.id);
        state.services = state.services.filter((candidate) => candidate.clientId !== row.id);
        state.photos = state.photos.filter((candidate) => candidate.clientId !== row.id);
        state.shares = state.shares.filter((candidate) => candidate.clientId !== row.id);
        state.pairs = state.pairs.filter((candidate) => candidate.firstClientId !== row.id && candidate.secondClientId !== row.id);
        state.merges = state.merges.filter((candidate) => candidate.keptClientId !== row.id);
        return structuredClone(row);
      })
    },
    appointment: {
      findMany: vi.fn(
        async ({ where }: { where: { stylistId: string; OR: [{ primaryClientId: string }, { participants: { some: { clientId: string } } }] } }) => {
          const clientId = where.OR[0].primaryClientId;

          return state.appointments
            .filter(
              (row) =>
                row.stylistId === where.stylistId &&
                (row.primaryClientId === clientId ||
                  state.participants.some((participant) => participant.appointmentId === row.id && participant.clientId === clientId))
            )
            .map((row) =>
              structuredClone({
                ...row,
                primaryClient: { address: state.clients.find((candidate) => candidate.id === row.primaryClientId)?.address ?? null },
                participants: state.participants
                  .filter((participant) => participant.appointmentId === row.id)
                  .map((participant) => ({ id: participant.id, clientId: participant.clientId }))
              })
            );
        }
      ),
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: Partial<Appointment> }) => {
        fail("appointment.update");
        const row = state.appointments.find((candidate) => candidate.id === where.id)!;

        if (data.primaryClientId && !state.clients.some((candidate) => candidate.id === data.primaryClientId)) {
          throw databaseError("P2003");
        }

        Object.assign(row, data);
        return structuredClone(row);
      })
    },
    appointmentParticipant: {
      create: vi.fn(async ({ data }: { data: { appointmentId: string; clientId: string } }) => {
        refuseSecondParticipant(data.appointmentId, data.clientId);
        const row = { id: id("participant"), createdAt: new Date("2026-10-21T12:00:00.000Z"), ...data };

        state.participants.push(row);
        return structuredClone(row);
      }),
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: { clientId: string } }) => {
        fail("appointmentParticipant.update");
        const row = state.participants.find((candidate) => candidate.id === where.id)!;

        refuseSecondParticipant(row.appointmentId, data.clientId, row.id);
        row.clientId = data.clientId;
        return structuredClone(row);
      }),
      delete: vi.fn(async ({ where }: { where: { id: string } }) => {
        state.participants = state.participants.filter((candidate) => candidate.id !== where.id);
      })
    },
    appointmentService: {
      updateMany: vi.fn(async ({ where, data }: { where: { clientId: string; appointment: { stylistId: string } }; data: { clientId: string } }) => {
        const rows = state.services.filter((row) => row.clientId === where.clientId && inAppointmentOf(where.appointment.stylistId, row.appointmentId));

        rows.forEach((row) => (row.clientId = data.clientId));
        return { count: rows.length };
      })
    },
    appointmentPhoto: {
      updateMany: vi.fn(async ({ where, data }: { where: { clientId: string; appointment: { stylistId: string } }; data: { clientId: string } }) => {
        const rows = state.photos.filter((row) => row.clientId === where.clientId && inAppointmentOf(where.appointment.stylistId, row.appointmentId));

        rows.forEach((row) => (row.clientId = data.clientId));
        return { count: rows.length };
      })
    },
    profileShare: {
      findFirst: vi.fn(async ({ where }: { where: { clientId: string; revokedAt: null } }) =>
        structuredClone(state.shares.find((row) => row.clientId === where.clientId && !row.revokedAt) ?? null)
      ),
      updateMany: vi.fn(async ({ where, data }: { where: { clientId: string; revokedAt?: null }; data: Partial<Share> }) => {
        const rows = state.shares.filter((row) => row.clientId === where.clientId && (where.revokedAt !== null || !row.revokedAt));

        rows.forEach((row) => Object.assign(row, data));
        return { count: rows.length };
      })
    },
    clientDistinctPair: {
      findMany: vi.fn(async ({ where }: { where: { OR: [{ firstClientId: string }, { secondClientId: string }] } }) =>
        structuredClone(state.pairs.filter((row) => row.firstClientId === where.OR[0].firstClientId || row.secondClientId === where.OR[1].secondClientId))
      ),
      findFirst: vi.fn(async ({ where }: { where: { firstClientId: string; secondClientId: string } }) =>
        structuredClone(state.pairs.find((row) => row.firstClientId === where.firstClientId && row.secondClientId === where.secondClientId) ?? null)
      ),
      deleteMany: vi.fn(async ({ where }: { where: { id: { in: string[] } } }) => {
        state.pairs = state.pairs.filter((row) => !where.id.in.includes(row.id));
      }),
      create: vi.fn(async ({ data }: { data: Omit<Pair, "id"> }) => {
        if (state.pairs.some((row) => row.firstClientId === data.firstClientId && row.secondClientId === data.secondClientId)) {
          throw databaseError("P2002");
        }

        if (data.firstClientId >= data.secondClientId) {
          throw new Error("A pair is stored with the smaller id first.");
        }

        const row = { id: id("pair"), ...data };

        state.pairs.push(row);
        return structuredClone(row);
      })
    },
    clientMerge: {
      findFirst: vi.fn(async ({ where }: { where: Omit<Merge, "id"> }) =>
        structuredClone(
          state.merges.find(
            (row) => row.stylistId === where.stylistId && row.keptClientId === where.keptClientId && row.removedClientId === where.removedClientId
          ) ?? null
        )
      ),
      updateMany: vi.fn(async ({ where, data }: { where: { keptClientId: string }; data: { keptClientId: string } }) => {
        state.merges.filter((row) => row.keptClientId === where.keptClientId).forEach((row) => (row.keptClientId = data.keptClientId));
      }),
      create: vi.fn(async ({ data }: { data: Omit<Merge, "id"> }) => {
        fail("clientMerge.create");
        const row = { id: id("merge"), ...data };

        state.merges.push(row);
        return structuredClone(row);
      })
    }
  };

  const db = {
    ...tx,
    // All or nothing, as the database's transaction is, and one at a time, as under the Stylist lock.
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
    failOn,
    get state() {
      return state;
    }
  };
}

const anna = client("client_a", { name: "Джессика", phone: "(512) 917-4528", note: "Любит мягкие слои." });
const jessica = client("client_b", {
  name: "Jessica Brown",
  phone: "+1 512-917-4528",
  email: "jessica@example.com",
  address: "10 Oak St",
  note: "Allergic to ammonia."
});

describe("clientProfile.merge", () => {
  it("moves every Appointment, Service and photo to the kept Client, fills their empty details, and deletes the other", async () => {
    const store = createStore({
      clients: [anna, jessica, client("client_c")],
      appointments: [appointment("appointment_1", "client_b"), appointment("appointment_2", "client_c"), appointment("appointment_3", "client_a")],
      participants: [
        { id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) },
        { id: "participant_2", appointmentId: "appointment_2", clientId: "client_c", createdAt: new Date(1) },
        { id: "participant_3", appointmentId: "appointment_2", clientId: "client_b", createdAt: new Date(2) },
        { id: "participant_4", appointmentId: "appointment_3", clientId: "client_a", createdAt: new Date(1) }
      ],
      services: [
        { id: "service_1", appointmentId: "appointment_1", clientId: "client_b", name: "Стрижка" },
        { id: "service_2", appointmentId: "appointment_2", clientId: "client_b", name: "Тонирование" },
        { id: "service_3", appointmentId: "appointment_2", clientId: "client_c", name: "Стрижка мужская" }
      ],
      photos: [{ id: "photo_1", appointmentId: "appointment_1", clientId: "client_b" }]
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(result).toMatchObject({
      removedId: "client_b",
      client: {
        id: "client_a",
        name: "Джессика",
        // The kept Client's own details stay; the empty ones are the other's.
        phone: "(512) 917-4528",
        email: "jessica@example.com",
        address: "10 Oak St",
        note: "Любит мягкие слои.\n\nAllergic to ammonia.",
        distinctFromIds: []
      }
    });
    expect(result.appointmentIds.sort()).toEqual(["appointment_1", "appointment_2"]);
    expect(store.state.clients.map((row) => row.id)).toEqual(["client_a", "client_c"]);
    expect(store.state.appointments.find((row) => row.id === "appointment_1")?.primaryClientId).toBe("client_a");
    // In the removed Client's place: the other Client stays primary and first.
    expect(store.state.appointments.find((row) => row.id === "appointment_2")?.primaryClientId).toBe("client_c");
    expect(store.state.participants.filter((row) => row.appointmentId === "appointment_2").map((row) => row.clientId)).toEqual(["client_c", "client_a"]);
    expect(store.state.services.map((row) => [row.id, row.clientId])).toEqual([
      ["service_1", "client_a"],
      ["service_2", "client_a"],
      ["service_3", "client_c"]
    ]);
    expect(store.state.photos[0]?.clientId).toBe("client_a");
    expect(store.state.participants.some((row) => row.clientId === "client_b")).toBe(false);
  });

  it("leaves one Client in an Appointment both were in, the kept one primary when the removed one was", async () => {
    const store = createStore({
      clients: [anna, jessica],
      appointments: [appointment("appointment_1", "client_b")],
      participants: [
        { id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) },
        { id: "participant_2", appointmentId: "appointment_1", clientId: "client_a", createdAt: new Date(2) }
      ],
      services: [
        { id: "service_1", appointmentId: "appointment_1", clientId: "client_b", name: "Стрижка" },
        { id: "service_2", appointmentId: "appointment_1", clientId: "client_a", name: "Укладка" }
      ],
      photos: [{ id: "photo_1", appointmentId: "appointment_1", clientId: "client_b" }]
    });

    await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.participants.map((row) => row.clientId)).toEqual(["client_a"]);
    expect(store.state.appointments[0]?.primaryClientId).toBe("client_a");
    expect(store.state.services.map((row) => row.clientId)).toEqual(["client_a", "client_a"]);
    expect(store.state.photos.map((row) => row.clientId)).toEqual(["client_a"]);
  });

  it("keeps the kept Client primary when the removed one was in their Appointment as another Client", async () => {
    const store = createStore({
      clients: [anna, jessica],
      appointments: [appointment("appointment_1", "client_a")],
      participants: [
        { id: "participant_1", appointmentId: "appointment_1", clientId: "client_a", createdAt: new Date(1) },
        { id: "participant_2", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(2) }
      ]
    });

    await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.participants.map((row) => row.clientId)).toEqual(["client_a"]);
    expect(store.state.appointments[0]?.primaryClientId).toBe("client_a");
  });

  it("makes the kept Client primary and one of the Clients of an Appointment that named the removed one as primary only", async () => {
    const store = createStore({
      clients: [anna, jessica],
      appointments: [appointment("appointment_1", "client_b")],
      participants: []
    });

    await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.appointments[0]?.primaryClientId).toBe("client_a");
    expect(store.state.participants.map((row) => [row.appointmentId, row.clientId])).toEqual([["appointment_1", "client_a"]]);
  });

  it("moves a Scheduled at-home Appointment to the kept Client's address and leaves one that happened where it was", async () => {
    const store = createStore({
      clients: [client("client_a", { address: "1 Elm St" }), client("client_b", { address: "10 Oak St" })],
      appointments: [
        appointment("appointment_1", "client_b", { status: "scheduled", locationType: "atHome", locationAddress: "10 Oak St" }),
        appointment("appointment_2", "client_b", { status: "completed", locationType: "atHome", locationAddress: "10 Oak St" }),
        appointment("appointment_3", "client_b", { status: "scheduled", locationType: "atHome", locationAddress: "77 Lake Rd", locationAddressIsCustom: true })
      ],
      participants: ["appointment_1", "appointment_2", "appointment_3"].map((appointmentId, index) => ({
        id: `participant_${index}`,
        appointmentId,
        clientId: "client_b",
        createdAt: new Date(1)
      }))
    });

    await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.appointments.map((row) => [row.id, row.primaryClientId, row.locationAddress, row.locationAddressIsCustom])).toEqual([
      ["appointment_1", "client_a", "1 Elm St", false],
      ["appointment_2", "client_a", "10 Oak St", true],
      ["appointment_3", "client_a", "77 Lake Rd", true]
    ]);
  });

  it("puts a Scheduled at-home Appointment at the address the kept Client takes from the removed one", async () => {
    const store = createStore({
      clients: [client("client_a"), client("client_b", { address: "10 Oak St" })],
      appointments: [appointment("appointment_1", "client_b", { status: "scheduled", locationType: "atHome", locationAddress: "10 Oak St" })],
      participants: [{ id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) }]
    });

    await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.appointments[0]).toMatchObject({ primaryClientId: "client_a", locationAddress: "10 Oak St", locationAddressIsCustom: false });
  });

  it("keeps the kept Client's Profile Share and revokes the removed one's, which moves with the rest", async () => {
    const store = createStore({
      clients: [anna, jessica],
      shares: [
        { id: "share_a", clientId: "client_a", token: "token_a", language: "ru", revokedAt: null, createdAt: new Date(1) },
        { id: "share_b", clientId: "client_b", token: "token_b", language: "en", revokedAt: null, createdAt: new Date(2) },
        { id: "share_old", clientId: "client_b", token: "token_old", language: "en", revokedAt: new Date(1), createdAt: new Date(0) }
      ]
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(result.client.activeProfileShare?.id).toBe("share_a");
    expect(store.state.shares.map((row) => [row.id, row.clientId, row.revokedAt !== null])).toEqual([
      ["share_a", "client_a", false],
      ["share_b", "client_a", true],
      ["share_old", "client_a", true]
    ]);
  });

  it("keeps the removed Client's Profile Share working for the kept Client when the kept one has none", async () => {
    const store = createStore({
      clients: [anna, jessica],
      shares: [{ id: "share_b", clientId: "client_b", token: "token_b", language: "en", revokedAt: null, createdAt: new Date(2) }]
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(result.client.activeProfileShare).toMatchObject({ id: "share_b", token: "token_b" });
    expect(store.state.shares[0]).toMatchObject({ clientId: "client_a", revokedAt: null });
  });

  it("carries over whom the removed Client is not, once, and forgets that the two were different", async () => {
    const store = createStore({
      clients: [anna, jessica, client("client_c"), client("client_d")],
      pairs: [
        { id: "pair_ab", stylistId: "stylist_1", firstClientId: "client_a", secondClientId: "client_b" },
        { id: "pair_bc", stylistId: "stylist_1", firstClientId: "client_b", secondClientId: "client_c" },
        { id: "pair_bd", stylistId: "stylist_1", firstClientId: "client_b", secondClientId: "client_d" },
        { id: "pair_ad", stylistId: "stylist_1", firstClientId: "client_a", secondClientId: "client_d" }
      ]
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.pairs.map((row) => [row.firstClientId, row.secondClientId]).sort()).toEqual([
      ["client_a", "client_c"],
      ["client_a", "client_d"]
    ]);
    expect(result.client.distinctFromIds?.sort()).toEqual(["client_c", "client_d"]);
  });

  it("keeps one Client Note when both say the same", async () => {
    const store = createStore({
      clients: [client("client_a", { note: "Allergic to ammonia." }), client("client_b", { note: " Allergic to ammonia. " })]
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(result.client.note).toBe("Allergic to ammonia.");
  });

  it("answers the same merge sent again with the kept Client as they are now, and changes nothing", async () => {
    const store = createStore({
      clients: [anna, jessica],
      appointments: [appointment("appointment_1", "client_b")],
      participants: [{ id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) }]
    });
    const caller = store.caller();

    const first = await caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" });
    const after = structuredClone(store.state);
    const again = await caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(again).toEqual({ ...first, appointmentIds: [] });
    expect(store.state).toEqual(after);
  });

  it("answers two of the same merge sent together as one", async () => {
    const store = createStore({ clients: [anna, jessica] });
    const caller = store.caller();

    const [first, second] = await Promise.all([
      caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" }),
      caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" })
    ]);

    expect(first.client).toEqual(second.client);
    expect(store.state.merges).toHaveLength(1);
  });

  it("does not take a removed Client for merged into someone they were not merged into", async () => {
    const store = createStore({ clients: [anna, jessica, client("client_c")] });
    const caller = store.caller();

    await caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    await expect(caller.clientProfile.merge({ keepId: "client_c", removeId: "client_b" })).rejects.toMatchObject({ code: "NOT_FOUND" });
  });

  it("merges three Clients with one number one after the other, and the first merge sent again still finds its Client", async () => {
    const store = createStore({ clients: [anna, jessica, client("client_c", { address: "5 Pine St" })] });
    const caller = store.caller();

    await caller.clientProfile.merge({ keepId: "client_b", removeId: "client_c" });
    await caller.clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(store.state.clients.map((row) => row.id)).toEqual(["client_a"]);
    // The first merge's Client lives on in client_a now.
    await expect(caller.clientProfile.merge({ keepId: "client_b", removeId: "client_c" })).rejects.toMatchObject({ code: "NOT_FOUND" });
    expect(store.state.merges.map((row) => [row.keptClientId, row.removedClientId])).toEqual([
      ["client_a", "client_c"],
      ["client_a", "client_b"]
    ]);
  });

  it("refuses to merge a Client into themselves and changes nothing", async () => {
    const store = createStore({ clients: [anna] });
    const before = structuredClone(store.state);

    await expect(store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_a" })).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "A Client cannot be merged into themselves."
    });
    expect(store.state).toEqual(before);
  });

  it("refuses a Client of another Stylist on either side and changes nothing", async () => {
    const store = createStore({
      clients: [anna, jessica],
      appointments: [appointment("appointment_1", "client_b")],
      participants: [{ id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) }]
    });
    const before = structuredClone(store.state);
    const otherStylist = store.caller("clerk_2");

    await expect(otherStylist.clientProfile.merge({ keepId: "client_a", removeId: "client_b" })).rejects.toMatchObject({ code: "NOT_FOUND" });

    const mixed = createStore({ clients: [anna, client("client_x", { stylistId: "stylist_2" })] });
    const mixedBefore = structuredClone(mixed.state);

    await expect(mixed.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_x" })).rejects.toMatchObject({ code: "NOT_FOUND" });
    await expect(mixed.caller().clientProfile.merge({ keepId: "client_x", removeId: "client_a" })).rejects.toMatchObject({ code: "NOT_FOUND" });
    expect(store.state).toEqual(before);
    expect(mixed.state).toEqual(mixedBefore);
  });

  it("refuses Client Notes that are too long together and changes nothing", async () => {
    const store = createStore({
      clients: [client("client_a", { note: "a".repeat(3000) }), client("client_b", { note: "b".repeat(3000) })]
    });
    const before = structuredClone(store.state);

    await expect(store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" })).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Text is too long."
    });
    expect(store.state).toEqual(before);
  });

  it.each(["appointmentParticipant.update", "appointment.update", "clientMerge.create", "client.delete"])(
    "leaves nothing changed when it fails at %s",
    async (step) => {
      const store = createStore({
        clients: [anna, jessica],
        appointments: [appointment("appointment_1", "client_b")],
        participants: [{ id: "participant_1", appointmentId: "appointment_1", clientId: "client_b", createdAt: new Date(1) }],
        services: [{ id: "service_1", appointmentId: "appointment_1", clientId: "client_b", name: "Стрижка" }]
      });
      const before = structuredClone(store.state);

      store.failOn.add(step);

      await expect(store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" })).rejects.toThrow();
      expect(store.state).toEqual(before);
    }
  );

  it("handles a Client in many Appointments", async () => {
    const appointments = Array.from({ length: 40 }, (_, index) => appointment(`appointment_${index}`, "client_b"));
    const store = createStore({
      clients: [anna, jessica],
      appointments,
      participants: appointments.map((row, index) => ({ id: `participant_${index}`, appointmentId: row.id, clientId: "client_b", createdAt: new Date(1) })),
      services: appointments.map((row, index) => ({ id: `service_${index}`, appointmentId: row.id, clientId: "client_b", name: "Стрижка" }))
    });

    const result = await store.caller().clientProfile.merge({ keepId: "client_a", removeId: "client_b" });

    expect(result.appointmentIds).toHaveLength(40);
    expect(store.state.appointments.every((row) => row.primaryClientId === "client_a")).toBe(true);
    expect(store.state.services.every((row) => row.clientId === "client_a")).toBe(true);
  });
});

describe("clientProfile.markDistinct", () => {
  it("records the pair once, with the smaller id first, and the list says it of both Clients", async () => {
    const store = createStore({ clients: [anna, jessica] });
    const caller = store.caller();

    await caller.clientProfile.markDistinct({ clientId: "client_b", otherClientId: "client_a" });
    await caller.clientProfile.markDistinct({ clientId: "client_a", otherClientId: "client_b" });

    expect(store.state.pairs.map((row) => [row.firstClientId, row.secondClientId])).toEqual([["client_a", "client_b"]]);

    const list = await caller.clientProfile.list();

    expect(list.map((row) => [row.id, row.distinctFromIds])).toEqual([
      ["client_a", ["client_b"]],
      ["client_b", ["client_a"]]
    ]);
  });

  it("refuses the same Client twice and a Client of another Stylist", async () => {
    const store = createStore({ clients: [anna, client("client_x", { stylistId: "stylist_2" })] });

    await expect(store.caller().clientProfile.markDistinct({ clientId: "client_a", otherClientId: "client_a" })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
    await expect(store.caller().clientProfile.markDistinct({ clientId: "client_a", otherClientId: "client_x" })).rejects.toMatchObject({
      code: "NOT_FOUND"
    });
    expect(store.state.pairs).toEqual([]);
  });
});
