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
  const byIdOrRequestKey = (list: Row[]) =>
    yieldFirst(
      ({ where }: { where: { id?: string; requestKey?: string } }) =>
        list.find((row) => (where.id ? row.id === where.id : row.requestKey === where.requestKey)) ?? null
    );
  // A Client that was created here, or one that exists from before under any id that is asked for.
  const readClient = (id: unknown) => ({
    id,
    name: "Anna",
    address: "",
    ...rows.clients.find((row) => row.id === id)
  });
  // The Appointment as the procedures read it, whichever relations they ask for.
  const readAppointment = (id: unknown) => {
    const appointment = rows.appointments.find((row) => row.id === id);
    const services = rows.services.filter((service) => service.appointmentId === id);

    return (
      appointment && {
        ...appointment,
        primaryClient: readClient(appointment.primaryClientId),
        participants: ((appointment.clientIds as string[] | undefined) ?? [appointment.primaryClientId]).map((clientId) => ({
          clientId,
          client: readClient(clientId)
        })),
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
      findMany: yieldFirst(({ where }: { where: { id: { in: string[] } } }) => where.id.in.map(readClient)),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.clients, "client", data))
    },
    serviceMenuItem: {
      findFirst: byIdOrRequestKey(rows.menuItems),
      findMany: yieldFirst(({ where }: { where: { id: { in: string[] }; stylistId: string } }) =>
        rows.menuItems.filter((row) => where.id.in.includes(row.id) && row.stylistId === where.stylistId)
      ),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.menuItems, "menu", data))
    },
    appointment: {
      findFirst: yieldFirst(({ where }: { where: { id?: string; requestKey?: string } }) =>
        readAppointment(where.requestKey ? rows.appointments.find((row) => row.requestKey === where.requestKey)?.id : where.id) ?? null
      ),
      // Scheduled Appointments that could overlap: the conflict search.
      findMany: yieldFirst(({ where }: { where: { id?: { not: string } } }) =>
        rows.appointments.filter((row) => row.id !== where.id?.not).map((row) => readAppointment(row.id))
      ),
      findUniqueOrThrow: yieldFirst(({ where }: { where: { id: string } }) => readAppointment(where.id)),
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => {
        const { participants, ...appointment } = data as { participants: { create: { clientId: string }[] } };
        const clientIds = participants.create.map((participant) => participant.clientId);

        return readAppointment(add(rows.appointments, "appointment", { ...appointment, clientIds, status: "scheduled" }).id);
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
    appointmentParticipant: {
      // Adds the Client to the Appointment unless they are in it already.
      upsert: yieldFirst(({ create }: { create: { appointmentId: string; clientId: string } }) => {
        const appointment = rows.appointments.find((row) => row.id === create.appointmentId);

        if (appointment) {
          const clientIds = (appointment.clientIds as string[] | undefined) ?? [appointment.primaryClientId as string];

          appointment.clientIds = clientIds.includes(create.clientId) ? clientIds : [...clientIds, create.clientId];
        }

        return create;
      })
    },
    colorFormula: {
      create: yieldFirst(({ data }: { data: Record<string, unknown> }) => add(rows.formulas, "formula", data))
    },
    appointmentPhoto: {
      findFirst: yieldFirst(({ where }: { where: { fileKey: string } }) => rows.photos.find((row) => row.fileKey === where.fileKey) ?? null),
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
    photoOrigins: ["https://app.ufs.sh"]
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

  it("adds one new person to an Appointment when both steps of adding them are sent again", async () => {
    // The app adds a person by a name only: it creates the Client under a key, then adds them.
    const { caller, rows } = withAppointment(createMemoryDb());

    rows.clients.push({ id: "client_1", name: "Anna" });

    const input = {
      name: "Daniel",
      requestKey
    };
    const first = await caller.clientProfile.save(input);

    await caller.appointment.addParticipant({ appointmentId: "appointment_1", clientId: first.id });

    // The reply was lost: the app tries both again with the same key.
    const second = await caller.clientProfile.save(input);

    await caller.appointment.addParticipant({ appointmentId: "appointment_1", clientId: second.id });

    expect(second.id).toBe(first.id);
    expect(rows.clients.map((client) => client.name)).toEqual(["Anna", "Daniel"]);
    expect(rows.appointments[0]?.clientIds).toEqual(["client_1", first.id]);
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

  it("adds the Services picked from the Service Menu once, in the order they were picked", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());

    rows.menuItems.push(
      { id: "menu_1", stylistId: "stylist_1", name: "Coloring short", defaultPriceCents: 5000 },
      { id: "menu_2", stylistId: "stylist_1", name: "Haircut", defaultPriceCents: 3000 }
    );

    // One item twice is two Services, as two taps of "add" would be.
    const input = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      menuItemIds: ["menu_2", "menu_1", "menu_2"],
      requestKey
    };
    const [first, second] = await Promise.all([
      caller.appointment.addMenuServices(input),
      caller.appointment.addMenuServices(input)
    ]);
    // The menu price changed in between: the request is still the same one.
    rows.menuItems[0]!.defaultPriceCents = 9000;
    const third = await caller.appointment.addMenuServices(input);

    expect(rows.services).toHaveLength(3);
    expect(rows.services.map((service) => service.requestKey)).toEqual([requestKey, requestKey, requestKey]);

    for (const reply of [first, second, third]) {
      expect(reply.services.map((service) => [service.name, service.menuItemId, service.priceCents])).toEqual([
        ["Haircut", "menu_2", 3000],
        ["Coloring short", "menu_1", 5000],
        ["Haircut", "menu_2", 3000]
      ]);
      expect(reply.serviceTotalCents).toBe(11000);
    }

    await caller.appointment.addMenuServices({ ...input, requestKey: otherRequestKey });
    expect(rows.services).toHaveLength(6);
  });

  it("adds no Service from the Service Menu when one of them cannot be added", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());

    rows.menuItems.push(
      { id: "menu_1", stylistId: "stylist_1", name: "Haircut", defaultPriceCents: 3000 },
      { id: "menu_other", stylistId: "stylist_2", name: "Not hers", defaultPriceCents: 3000 }
    );

    await expect(
      caller.appointment.addMenuServices({ appointmentId: "appointment_1", clientId: "client_1", menuItemIds: ["menu_1", "menu_gone"] })
    ).rejects.toMatchObject({ code: "NOT_FOUND" });
    await expect(
      caller.appointment.addMenuServices({ appointmentId: "appointment_1", clientId: "client_1", menuItemIds: ["menu_1", "menu_other"] })
    ).rejects.toMatchObject({ code: "NOT_FOUND" });
    await expect(
      caller.appointment.addMenuServices({ appointmentId: "appointment_1", clientId: "client_2", menuItemIds: ["menu_1"] })
    ).rejects.toMatchObject({ code: "BAD_REQUEST" });
    await expect(
      caller.appointment.addMenuServices({ appointmentId: "appointment_1", clientId: "client_1", menuItemIds: [] })
    ).rejects.toMatchObject({ code: "BAD_REQUEST" });

    rows.menuItems[0]!.defaultPriceCents = 999_999_999;
    await expect(
      caller.appointment.addMenuServices({ appointmentId: "appointment_1", clientId: "client_1", menuItemIds: ["menu_1", "menu_1"] })
    ).rejects.toMatchObject({ code: "BAD_REQUEST", message: "Appointment total is too large." });

    expect(rows.services).toHaveLength(0);
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

    const otherFile = { ...photo, fileKey: "ut_file_2", url: "https://app.ufs.sh/f/ut_file_2", thumbnailUrl: "https://app.ufs.sh/f/ut_file_2" };

    await caller.appointment.addPhoto(otherFile);
    expect(rows.photos).toHaveLength(2);

    // A photo without a stored file is recognised by its key alone.
    const failed = { appointmentId: "appointment_1", clientId: "client_1", category: "other", status: "failed" } as const;

    await caller.appointment.addPhoto({ ...failed, requestKey: otherRequestKey });
    await caller.appointment.addPhoto({ ...failed, requestKey: otherRequestKey });
    await caller.appointment.addPhoto(failed);
    expect(rows.photos).toHaveLength(4);
  });
});

describe("a request key that comes again with other values", () => {
  const carriedOutOtherwise = {
    code: "CONFLICT",
    message: "This request was already carried out with other values."
  };

  it("refuses the corrected Client and keeps the one that was created", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      name: "Lost Carl",
      phone: "5550123",
      requestKey
    };
    const first = await caller.clientProfile.save(input);

    // The reply was lost, the form stayed filled, and the Stylist corrected it before sending again.
    for (const corrected of [{ phone: "5550199" }, { name: "Lost Karl" }, { email: "carl@example.com" }, { note: "Calls back" }, { language: "ru" as const }]) {
      await expect(caller.clientProfile.save({ ...input, ...corrected })).rejects.toMatchObject(carriedOutOtherwise);
    }

    expect(rows.clients).toHaveLength(1);
    expect(rows.clients[0]).toMatchObject({ name: "Lost Carl", phone: "5550123", email: "", note: "" });
    // The same values, typed with other white space, are the same request.
    await expect(caller.clientProfile.save({ ...input, name: " Lost Carl\n", phone: "5550123 ", email: "" })).resolves.toEqual(first);
  });

  it("refuses the corrected Service Menu Item", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      name: "Haircut",
      defaultPriceCents: 8500,
      requestKey
    };

    await caller.serviceMenu.save(input);

    await expect(caller.serviceMenu.save({ ...input, defaultPriceCents: 9000 })).rejects.toMatchObject(carriedOutOtherwise);
    await expect(caller.serviceMenu.save({ ...input, name: "Haircut long" })).rejects.toMatchObject(carriedOutOtherwise);
    expect(rows.menuItems).toHaveLength(1);
    expect(rows.menuItems[0]).toMatchObject({ name: "Haircut", defaultPriceCents: 8500 });
  });

  const laterStart = "2026-06-13T16:00:00.000Z";
  const end = "2026-06-13T17:00:00.000Z";

  it.each([
    ["another start", { startsAt: laterStart }],
    ["an end time", { endsAt: end }],
    ["another place", { locationType: "atHome" as const }],
    ["another address", { locationType: "atHome" as const, customLocationAddress: "9 Other St" }],
    ["another primary Client", { primaryClientId: "client_8" }],
    ["a new Client instead of the chosen one", { primaryClientId: undefined, inlineClientName: "Walk-in" }],
    ["one more Client", { additionalClientIds: ["client_2", "client_3"] }],
    ["one Client fewer", { additionalClientIds: [] }]
  ])("refuses the Appointment sent again with %s", async (_label, corrected) => {
    const { caller, rows } = createMemoryDb();
    const input = {
      primaryClientId: "client_7",
      additionalClientIds: ["client_2"],
      startsAt: startsAt.toISOString(),
      requestKey
    };
    const first = await caller.appointment.create(input);

    await expect(caller.appointment.create({ ...input, ...corrected })).rejects.toMatchObject(carriedOutOtherwise);
    expect(rows.appointments).toHaveLength(1);
    expect(rows.clients).toHaveLength(0);
    expect(rows.appointments[0]).toMatchObject({ primaryClientId: "client_7", startsAt, endsAt: null, locationType: "inSalon" });

    // The same request, with the Clients named in another order and one of them twice.
    const again = await caller.appointment.create({ ...input, additionalClientIds: ["client_2", "client_7", "client_2"] });

    expect(again.appointment.id).toBe(first.appointment.id);
  });

  it("refuses the Appointment sent again with another name or address for what the first call stored", async () => {
    const { caller, rows } = createMemoryDb();
    const input = {
      inlineClientName: "Walk-in",
      startsAt: startsAt.toISOString(),
      endsAt: end,
      locationType: "atHome" as const,
      customLocationAddress: "7 Home St",
      requestKey
    };
    const first = await caller.appointment.create(input);

    for (const corrected of [{ inlineClientName: "Walk-in Wanda" }, { customLocationAddress: "9 Other St" }, { customLocationAddress: "" }, { endsAt: undefined }, { locationType: "inSalon" as const }]) {
      await expect(caller.appointment.create({ ...input, ...corrected })).rejects.toMatchObject(carriedOutOtherwise);
    }

    expect(rows.appointments).toHaveLength(1);
    expect(rows.clients.map((client) => client.name)).toEqual(["Walk-in"]);
    await expect(caller.appointment.create(input)).resolves.toMatchObject({ appointment: { id: first.appointment.id } });
  });

  it("refuses the corrected Service", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());
    const input = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      name: "Lost Svc",
      priceCents: 1200,
      requestKey
    };

    await caller.appointment.addService(input);

    for (const corrected of [{ priceCents: 1500 }, { name: "Found Svc" }, { note: "With toner" }, { menuItemId: "menu_9" }]) {
      await expect(caller.appointment.addService({ ...input, ...corrected })).rejects.toMatchObject(carriedOutOtherwise);
    }

    expect(rows.services).toHaveLength(1);
    expect(rows.services[0]).toMatchObject({ name: "Lost Svc", priceCents: 1200, note: "" });
  });

  it("takes a Service from the Service Menu again when its name and price were left to the menu", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());

    rows.menuItems.push({ id: "menu_1", stylistId: "stylist_1", name: "Haircut", defaultPriceCents: 8500 });

    const input = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      menuItemId: "menu_1",
      requestKey
    };

    await caller.appointment.addService(input);
    // The menu price changed in between: the request is still the same one.
    rows.menuItems[0]!.defaultPriceCents = 9000;
    await caller.appointment.addService(input);

    expect(rows.services).toHaveLength(1);
    await expect(caller.appointment.addService({ ...input, priceCents: 9000 })).rejects.toMatchObject(carriedOutOtherwise);
    await expect(caller.appointment.addService({ ...input, priceCents: 8500, name: "Haircut" })).resolves.toMatchObject({
      serviceTotalCents: 8500
    });
  });

  it("refuses other Service Menu Items or another Client for the key of Services that were added", async () => {
    const memory = withAppointment(createMemoryDb());
    const { caller, rows } = memory;

    rows.appointments[0]!.clientIds = ["client_1", "client_2"];
    rows.menuItems.push(
      { id: "menu_1", stylistId: "stylist_1", name: "Haircut", defaultPriceCents: 3000 },
      { id: "menu_2", stylistId: "stylist_1", name: "Coloring", defaultPriceCents: 5000 }
    );

    const input = { appointmentId: "appointment_1", clientId: "client_1", menuItemIds: ["menu_1", "menu_2"], requestKey };

    await caller.appointment.addMenuServices(input);

    for (const corrected of [
      { menuItemIds: ["menu_1"] },
      { menuItemIds: ["menu_2", "menu_1"] },
      { menuItemIds: ["menu_1", "menu_2", "menu_2"] },
      { clientId: "client_2" }
    ]) {
      await expect(caller.appointment.addMenuServices({ ...input, ...corrected })).rejects.toMatchObject(carriedOutOtherwise);
    }

    expect(rows.services).toHaveLength(2);
  });

  it("refuses the corrected Color Formula", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());

    rows.services.push({ id: "service_1", appointmentId: "appointment_1", clientId: "client_1", menuItemId: null, name: "Color", priceCents: 0, note: "", createdAt });

    const input = {
      appointmentServiceId: "service_1",
      formula: "7N lost",
      requestKey
    };

    await caller.appointment.addColorFormula(input);

    await expect(caller.appointment.addColorFormula({ ...input, formula: "7N + 6G" })).rejects.toMatchObject(carriedOutOtherwise);
    await expect(caller.appointment.addColorFormula({ ...input, placement: "Roots" })).rejects.toMatchObject(carriedOutOtherwise);
    expect(rows.formulas).toHaveLength(1);
    expect(rows.formulas[0]).toMatchObject({ formula: "7N lost", placement: "" });
  });

  it("refuses the same picture for another category, and takes its second upload as the same photo", async () => {
    const { caller, rows } = withAppointment(createMemoryDb());
    const photo = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      category: "before",
      fileKey: "ut_file_1",
      url: "https://app.ufs.sh/f/ut_file_1",
      requestKey
    } as const;
    const uploadedAgain = { ...photo, fileKey: "ut_file_2", url: "https://app.ufs.sh/f/ut_file_2" };

    await caller.appointment.addPhoto(photo);

    await expect(caller.appointment.addPhoto({ ...photo, category: "after" })).rejects.toMatchObject(carriedOutOtherwise);
    await expect(caller.appointment.addPhoto({ ...uploadedAgain, category: "after" })).rejects.toMatchObject(carriedOutOtherwise);
    await caller.appointment.addPhoto(uploadedAgain);

    expect(rows.photos).toHaveLength(1);
    expect(rows.photos[0]).toMatchObject({ category: "before", fileKey: "ut_file_1" });
  });

  it("answers a repeated copy with what was copied, whichever source it names", async () => {
    const memory = withAppointment(withAppointment(withAppointment(createMemoryDb()), "source_1", "completed"), "source_2", "completed");
    const { caller, rows } = memory;

    rows.services.push(
      { id: "source_service_1", appointmentId: "source_1", clientId: "client_1", menuItemId: null, name: "Src Cut", priceCents: 3000, note: "", createdAt },
      { id: "source_service_2", appointmentId: "source_2", clientId: "client_1", menuItemId: null, name: "Other Color", priceCents: 9000, note: "", createdAt }
    );

    const input = {
      targetAppointmentId: "appointment_1",
      sourceAppointmentId: "source_1",
      requestKey
    };

    await caller.appointment.copyServices(input);

    // The copies do not record their source, so the reply is what shows which copy was made.
    const again = await caller.appointment.copyServices({ ...input, sourceAppointmentId: "source_2" });

    expect(again.services.map((service) => service.name)).toEqual(["Src Cut"]);
    expect(again.copiedServiceCount).toBe(1);
    expect(rows.services.filter((service) => service.appointmentId === "appointment_1")).toHaveLength(1);
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
