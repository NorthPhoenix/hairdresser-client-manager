import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { withTransaction } from "./testSupport";

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
const createdAt = new Date("2026-06-13T12:00:00.000Z");
const startsAt = new Date("2026-06-13T15:00:00.000Z");
const requestKey = "3f0c8a52-7c1e-4a51-9f0e-0d9b8f6f2a11";
const otherRequestKey = "b7a1d1de-2f64-4f0e-8a9c-5f1f3f0f4c22";

type Row = Record<string, unknown> & { id: string; requestKey?: string | null };

// A small database kept in memory. Every call yields first, so two mutations started together
// would interleave if their check and their write were not inside the Stylist's transaction.
function createMemoryDb() {
  const rows = {
    clients: [] as Row[],
    menuItems: [] as Row[],
    appointments: [] as Row[],
    services: [] as Row[],
    formulas: [] as Row[],
    photos: [] as Row[],
    shares: [] as Row[]
  };
  const yieldFirst = <Args extends unknown[], Result>(run: (...args: Args) => Result) =>
    vi.fn(async (...args: Args) => {
      await new Promise((resolve) => setTimeout(resolve, 0));

      return run(...args);
    });
  const add = (list: Row[], prefix: string, data: Record<string, unknown>) => {
    const row = {
      ...data,
      id: `${prefix}_${list.length + 1}`,
      createdAt,
      updatedAt: createdAt
    };

    list.push(row);

    return row;
  };
  const byRequestKey = (list: Row[]) =>
    yieldFirst(({ where }: { where: { requestKey?: string } }) => list.find((row) => row.requestKey === where.requestKey) ?? null);
  // The Appointment as the procedures read it, whichever relations they ask for.
  const readAppointment = (id: unknown) => {
    const appointment = rows.appointments.find((row) => row.id === id);
    const services = rows.services.filter((service) => service.appointmentId === id);

    return (
      appointment && {
        ...appointment,
        primaryClient: {
          id: "client_1",
          name: "Anna",
          address: ""
        },
        participants: [
          {
            clientId: "client_1",
            client: {
              id: "client_1",
              name: "Anna",
              address: ""
            }
          }
        ],
        services: services.map((service) => ({
          ...service,
          colorFormulas: rows.formulas.filter((formula) => formula.appointmentServiceId === service.id)
        })),
        photos: rows.photos.filter((photo) => photo.appointmentId === id)
      }
    );
  };
  const db = withTransaction({
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUniqueOrThrow: vi.fn().mockResolvedValue(stylist)
    },
    client: {
      findFirst: yieldFirst(({ where }: { where: { id?: string; requestKey?: string } }) =>
        where.id
          ? {
              id: where.id,
              language: "en",
              address: "",
              profileShares: rows.shares.filter((share) => share.clientId === where.id && !share.revokedAt)
            }
          : (rows.clients.find((row) => row.requestKey === where.requestKey) ?? null)
      ),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.clients, "client", data))
    },
    serviceMenuItem: {
      findFirst: byRequestKey(rows.menuItems),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.menuItems, "menu", data))
    },
    appointment: {
      findFirst: yieldFirst(({ where }: { where: { id?: string; requestKey?: string } }) =>
        where.requestKey
          ? (rows.appointments.find((row) => row.requestKey === where.requestKey) ?? null)
          : (readAppointment(where.id) ?? null)
      ),
      // Scheduled Appointments that could overlap: the conflict search.
      findMany: yieldFirst(({ where }: { where: { id?: { not: string } } }) =>
        rows.appointments.filter((row) => row.id !== where.id?.not).map((row) => readAppointment(row.id))
      ),
      findUniqueOrThrow: yieldFirst(({ where }: { where: { id: string } }) => readAppointment(where.id)),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => {
        const { participants: _participants, ...appointment } = data;

        return readAppointment(add(rows.appointments, "appointment", { ...appointment, status: "scheduled" }).id);
      })
    },
    appointmentService: {
      findFirst: yieldFirst(({ where }: { where: { id: string } }) => {
        const service = rows.services.find((row) => row.id === where.id);

        return service
          ? {
              ...service,
              colorFormulas: rows.formulas.filter((formula) => formula.appointmentServiceId === service.id)
            }
          : null;
      }),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => {
        const { colorFormulas, ...service } = data as { colorFormulas?: { create: Record<string, unknown>[] } };
        const created = add(rows.services, "service", service);

        for (const formula of colorFormulas?.create ?? []) {
          add(rows.formulas, "formula", { ...formula, appointmentServiceId: created.id });
        }

        return created;
      })
    },
    colorFormula: {
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.formulas, "formula", data))
    },
    appointmentPhoto: {
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.photos, "photo", data))
    },
    profileShare: {
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.shares, "share", { ...data, revokedAt: null }))
    }
  });
  const caller = appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db,
    photoOrigins: []
  } as unknown as TRPCContext);

  return {
    caller,
    rows
  };
}

// An Appointment with one Client, as if created earlier.
function withAppointment(memory: ReturnType<typeof createMemoryDb>, id = "appointment_1", status = "scheduled") {
  memory.rows.appointments.push({
    id,
    stylistId: "stylist_1",
    primaryClientId: "client_1",
    startsAt,
    endsAt: null,
    status,
    locationType: "inSalon",
    locationAddress: "500 Salon Ave",
    locationAddressIsCustom: false,
    note: "",
    finalTotalCentsOverride: null
  });

  return memory;
}

describe("a creating request that is sent again", () => {
  it("creates one Client and answers with it both times", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      name: "Lost Carl",
      phone: "5550123",
      requestKey
    };
    const first = await caller.clientProfile.save(input);
    const second = await caller.clientProfile.save(input);

    expect(rows.clients).toHaveLength(1);
    expect(second).toEqual(first);
    expect(first).toMatchObject({
      id: "client_1",
      name: "Lost Carl",
      phone: "5550123"
    });
  });

  it("creates one Client when the second request arrives while the first is still running", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      name: "Lost Carl",
      requestKey
    };
    const [first, second] = await Promise.all([caller.clientProfile.save(input), caller.clientProfile.save(input)]);

    expect(rows.clients).toHaveLength(1);
    expect(second.id).toBe(first.id);
  });

  it("still creates a Client for every request with another key or without one", async () => {
    const { caller, rows } = createMemoryDb();

    await caller.clientProfile.save({ name: "Anna", requestKey });
    await caller.clientProfile.save({ name: "Anna", requestKey: otherRequestKey });
    await caller.clientProfile.save({ name: "Anna" });
    await caller.clientProfile.save({ name: "Anna" });

    expect(rows.clients).toHaveLength(4);
  });

  it("creates one Service Menu Item", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      name: "Haircut",
      defaultPriceCents: 8500,
      requestKey
    };
    const [first, second] = await Promise.all([caller.serviceMenu.save(input), caller.serviceMenu.save(input)]);
    const third = await caller.serviceMenu.save(input);

    expect(rows.menuItems).toHaveLength(1);
    expect(second.id).toBe(first.id);
    expect(third).toEqual(first);
  });

  it("creates one Appointment and one inline Client, and repeats the overlap warning", async () => {
    const memory = withAppointment(createMemoryDb(), "appointment_other");
    const { caller, rows } = memory;
    const input = {
      inlineClientName: "Walk-in",
      startsAt: startsAt.toISOString(),
      requestKey
    };
    const first = await caller.appointment.create(input);
    const second = await caller.appointment.create(input);

    expect(rows.appointments.map((appointment) => appointment.id)).toEqual(["appointment_other", "appointment_2"]);
    expect(rows.clients).toHaveLength(1);
    expect(second.appointment.id).toBe(first.appointment.id);
    expect(first.conflicts.map((conflict) => conflict.id)).toEqual(["appointment_other"]);
    expect(second.conflicts.map((conflict) => conflict.id)).toEqual(["appointment_other"]);
  });

  it("creates one Appointment when two identical requests arrive together", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      inlineClientName: "Walk-in",
      startsAt: startsAt.toISOString(),
      requestKey
    };
    const [first, second] = await Promise.all([caller.appointment.create(input), caller.appointment.create(input)]);

    expect(rows.appointments).toHaveLength(1);
    expect(rows.clients).toHaveLength(1);
    expect(second.appointment.id).toBe(first.appointment.id);
  });

  it("adds one Service", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());
    const input = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      name: "Lost Svc",
      priceCents: 1200,
      requestKey
    };
    const [first, second] = await Promise.all([caller.appointment.addService(input), caller.appointment.addService(input)]);
    const third = await caller.appointment.addService(input);

    expect(rows.services).toHaveLength(1);

    for (const reply of [first, second, third]) {
      expect(reply.services.map((service) => service.name)).toEqual(["Lost Svc"]);
      expect(reply.serviceTotalCents).toBe(1200);
    }

    // The same Service on purpose is another request with another key.
    await caller.appointment.addService({ ...input, requestKey: otherRequestKey });
    expect(rows.services).toHaveLength(2);
  });

  it("copies the Services of an Appointment once", async () => {
    const memory = withAppointment(withAppointment(createMemoryDb()), "source_1", "completed");
    const { caller, rows } = memory;

    rows.services.push(
      { id: "source_service_1", appointmentId: "source_1", clientId: "client_1", menuItemId: null, name: "Src Cut", priceCents: 3000, note: "", createdAt },
      { id: "source_service_2", appointmentId: "source_1", clientId: "client_1", menuItemId: null, name: "Src Color", priceCents: 9000, note: "", createdAt }
    );
    rows.formulas.push({ id: "source_formula_1", appointmentServiceId: "source_service_1", formula: "7N", placement: "", createdAt });

    const input = {
      targetAppointmentId: "appointment_1",
      sourceAppointmentId: "source_1",
      requestKey
    };
    const [first, second] = await Promise.all([caller.appointment.copyServices(input), caller.appointment.copyServices(input)]);
    const third = await caller.appointment.copyServices(input);

    // A second tap in the copy sheet, or the same copy sent again: what the first call copied.
    for (const reply of [first, second, third]) {
      expect(reply.services.map((service) => service.name)).toEqual(["Src Cut", "Src Color"]);
      expect(reply.serviceTotalCents).toBe(12000);
      expect(reply.copiedServiceCount).toBe(2);
    }

    expect(rows.services.filter((service) => service.appointmentId === "appointment_1")).toHaveLength(2);
    expect(rows.formulas).toHaveLength(2);
  });

  it("adds one Color Formula", async () => {
    const memory = withAppointment(createMemoryDb());
    const { caller, rows } = memory;

    rows.services.push({ id: "service_1", appointmentId: "appointment_1", clientId: "client_1", menuItemId: null, name: "Color", priceCents: 0, note: "", createdAt });

    const input = {
      appointmentServiceId: "service_1",
      formula: "7N lost",
      requestKey
    };
    const [first, second] = await Promise.all([caller.appointment.addColorFormula(input), caller.appointment.addColorFormula(input)]);
    const third = await caller.appointment.addColorFormula(input);

    expect(rows.formulas).toHaveLength(1);

    for (const reply of [first, second, third]) {
      expect(reply.services[0]?.colorFormulas.map((formula) => formula.formula)).toEqual(["7N lost"]);
    }
  });

  it("adds one Appointment Photo, recognised by the key or by the stored file", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());
    const photo = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      category: "after",
      fileKey: "ut_file_1",
      url: "https://app.ufs.sh/f/ut_file_1",
      thumbnailUrl: "https://app.ufs.sh/f/ut_file_1"
    } as const;
    const [first, second] = await Promise.all([
      caller.appointment.addPhoto({ ...photo, requestKey }),
      caller.appointment.addPhoto({ ...photo, requestKey })
    ]);
    // An app that sends no key: the same stored file is still one photo.
    const third = await caller.appointment.addPhoto(photo);

    expect(rows.photos).toHaveLength(1);

    for (const reply of [first, second, third]) {
      expect(reply.photos.map((stored) => stored.fileKey)).toEqual(["ut_file_1"]);
    }

    await caller.appointment.addPhoto({ ...photo, fileKey: "ut_file_2", url: "https://app.ufs.sh/f/ut_file_2" });
    expect(rows.photos).toHaveLength(2);

    // A photo without a stored file is recognised by its key alone.
    const failed = { appointmentId: "appointment_1", clientId: "client_1", category: "other", status: "failed" } as const;

    await caller.appointment.addPhoto({ ...failed, requestKey: otherRequestKey });
    await caller.appointment.addPhoto({ ...failed, requestKey: otherRequestKey });
    await caller.appointment.addPhoto(failed);
    expect(rows.photos).toHaveLength(4);
  });
});

describe("Profile Share creation", () => {
  it("creates one active Profile Share when two requests arrive together", async () => {
    const { caller, rows } = createMemoryDb();
    const [first, second] = await Promise.all([
      caller.clientProfile.createProfileShare({ clientId: "client_9" }),
      caller.clientProfile.createProfileShare({ clientId: "client_9" })
    ]);
    const third = await caller.clientProfile.createProfileShare({ clientId: "client_9" });

    expect(rows.shares).toHaveLength(1);
    expect(second.token).toBe(first.token);
    expect(third.token).toBe(first.token);
  });
});
