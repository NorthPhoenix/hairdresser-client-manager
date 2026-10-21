import { describe, expect, it, vi } from "vitest";
import { maxAmountCents } from "./input";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { withTransaction } from "./testSupport";

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

type MenuItem = { id: string; stylistId: string; name: string; defaultPriceCents: number; travelFee: boolean; createdAt: Date };
type Service = {
  id: string;
  appointmentId: string;
  clientId: string;
  menuItemId: string | null;
  name: string;
  priceCents: number;
  note: string;
  createdAt: Date;
};

const travelFeeItem: MenuItem = {
  id: "menu_fee",
  stylistId: "stylist_1",
  name: "Выезд на дом к клиенту",
  defaultPriceCents: 2000,
  travelFee: true,
  createdAt: new Date("2026-01-01T00:00:00.000Z")
};

/**
 * One Stylist's Appointments, Clients, Services and Service Menu Items kept in memory, with the
 * calls Appointment Creation, Appointment changes and the Travel Fee make.
 */
function createStore(options: { menu?: MenuItem[]; appointment?: Record<string, unknown>; services?: Partial<Service>[] } = {}) {
  const clients = [
    { id: "client_1", name: "Anna", address: "10 Client St" },
    { id: "client_2", name: "Mila", address: "20 Other St" }
  ];
  const state = {
    menu: [...(options.menu ?? [travelFeeItem])],
    appointment: options.appointment
      ? {
          id: "appointment_1",
          stylistId: "stylist_1",
          primaryClientId: "client_1",
          startsAt: new Date("2026-10-23T15:00:00.000Z"),
          endsAt: null,
          status: "scheduled",
          locationType: "inSalon",
          locationAddress: "500 Salon Ave",
          locationAddressIsCustom: false,
          note: "",
          finalTotalCentsOverride: null,
          clientIds: ["client_1", "client_2"],
          ...options.appointment
        }
      : (null as Record<string, unknown> | null),
    services: (options.services ?? []).map(
      (service, index): Service => ({
        id: `service_${index + 1}`,
        appointmentId: "appointment_1",
        clientId: "client_1",
        menuItemId: null,
        name: "Стрижка",
        priceCents: 4000,
        note: "",
        createdAt: new Date(Date.UTC(2026, 9, 20, 0, 0, index)),
        ...service
      })
    )
  };
  let nextService = state.services.length + 1;
  const client = (id: string) => clients.find((row) => row.id === id) ?? null;
  const read = () => {
    const appointment = state.appointment as Record<string, unknown> & { clientIds: string[]; primaryClientId: string };

    return {
      ...appointment,
      primaryClient: client(appointment.primaryClientId),
      participants: appointment.clientIds.map((clientId, index) => ({
        id: `participant_${index}`,
        clientId,
        createdAt: new Date(index),
        client: client(clientId)
      })),
      services: state.services.map((service) => ({ ...service, colorFormulas: [] })),
      photos: []
    };
  };
  const matches = (service: Service, where: { appointmentId?: string; menuItemId?: string; id?: { in: string[] } }) =>
    (where.appointmentId === undefined || service.appointmentId === where.appointmentId) &&
    (where.menuItemId === undefined || service.menuItemId === where.menuItemId) &&
    (where.id === undefined || where.id.in.includes(service.id));
  const db = {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUniqueOrThrow: vi.fn().mockResolvedValue(stylist)
    },
    client: {
      findFirst: vi.fn(async ({ where }: { where: { id: string } }) => client(where.id)),
      findUniqueOrThrow: vi.fn(async ({ where }: { where: { id: string } }) => client(where.id)),
      findMany: vi.fn(async ({ where }: { where: { id: { in: string[] } } }) => clients.filter((row) => where.id.in.includes(row.id)))
    },
    appointment: {
      findFirst: vi.fn(async () => state.appointment && read()),
      findMany: vi.fn(async () => []),
      findUniqueOrThrow: vi.fn(async () => read()),
      create: vi.fn(async ({ data }: { data: Record<string, unknown> & { participants: { create: { clientId: string }[] } } }) => {
        const { participants, ...row } = data;

        state.appointment = {
          id: "appointment_1",
          status: "scheduled",
          ...row,
          clientIds: participants.create.map((participant) => participant.clientId)
        };

        return read();
      }),
      update: vi.fn(async ({ data }: { data: Record<string, unknown> }) => {
        state.appointment = { ...state.appointment, ...data };

        return read();
      })
    },
    appointmentParticipant: {
      findFirst: vi.fn(async ({ where }: { where: { clientId: string } }) =>
        (state.appointment?.clientIds as string[]).includes(where.clientId) ? { clientId: where.clientId } : null
      )
    },
    appointmentService: {
      findMany: vi.fn(async ({ where }: { where: { appointmentId: string; menuItemId?: string } }) =>
        state.services.filter((service) => matches(service, where))
      ),
      create: vi.fn(async ({ data }: { data: Omit<Service, "id" | "createdAt"> }) => {
        const service = { ...data, id: `service_${nextService++}`, createdAt: new Date() };

        state.services.push(service);

        return service;
      }),
      deleteMany: vi.fn(async ({ where }: { where: { id: { in: string[] } } }) => {
        state.services = state.services.filter((service) => !matches(service, where));
      })
    },
    serviceMenuItem: {
      findFirst: vi.fn(async ({ where }: { where: { stylistId: string; travelFee: boolean } }) =>
        state.menu.find((item) => item.stylistId === where.stylistId && item.travelFee === where.travelFee) ?? null
      )
    }
  };
  const caller = appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
    stylist
  } as unknown as TRPCContext);

  return { state, db, caller };
}

const startsAt = "2026-10-23T15:00:00.000Z";

describe("Travel Fee", () => {
  it("adds a Service from the Travel Fee item to an Appointment created at home, for the primary Client at its default price", async () => {
    const { state, caller } = createStore();

    const result = await caller.appointment.create({
      primaryClientId: "client_1",
      additionalClientIds: ["client_2"],
      startsAt,
      locationType: "atHome"
    });

    expect(state.services).toEqual([
      expect.objectContaining({ clientId: "client_1", menuItemId: "menu_fee", name: "Выезд на дом к клиенту", priceCents: 2000 })
    ]);
    expect(result.travelFee).toEqual({
      added: [expect.objectContaining({ clientId: "client_1", menuItemId: "menu_fee", name: "Выезд на дом к клиенту", priceCents: 2000 })],
      removed: []
    });
    // The Appointment in the reply has it, with the totals.
    expect(result.appointment.services).toHaveLength(1);
    expect(result.appointment.serviceTotalCents).toBe(2000);
  });

  it("adds nothing in the salon, without a Travel Fee item, or to an Appointment that is not Scheduled", async () => {
    const inSalon = createStore();

    await expect(
      inSalon.caller.appointment.create({ primaryClientId: "client_1", startsAt, locationType: "inSalon" })
    ).resolves.toMatchObject({ travelFee: { added: [], removed: [] } });
    expect(inSalon.state.services).toEqual([]);

    const noItem = createStore({ menu: [{ ...travelFeeItem, travelFee: false }] });

    await expect(
      noItem.caller.appointment.create({ primaryClientId: "client_1", startsAt, locationType: "atHome" })
    ).resolves.toMatchObject({ travelFee: { added: [], removed: [] } });
    expect(noItem.state.services).toEqual([]);

    for (const status of ["completed", "canceled", "noShow"]) {
      const done = createStore({ appointment: { status } });

      await expect(
        done.caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" })
      ).resolves.toMatchObject({ travelFee: { added: [], removed: [] } });
      expect(done.state.services).toEqual([]);
    }

    const doneAtHome = createStore({ appointment: { status: "completed", locationType: "atHome" }, services: [{ menuItemId: "menu_fee" }] });

    await doneAtHome.caller.appointment.update({ id: "appointment_1", locationType: "inSalon" });
    expect(doneAtHome.state.services).toHaveLength(1);
  });

  it("follows a Scheduled Appointment that changes place back and forth, and never adds a second one", async () => {
    const { state, caller } = createStore({ appointment: {}, services: [{ name: "Стрижка" }] });

    const toHome = await caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" });

    expect(toHome.travelFee.added).toEqual([expect.objectContaining({ menuItemId: "menu_fee", clientId: "client_1", priceCents: 2000 })]);
    expect(toHome.services.map((service) => service.menuItemId)).toEqual([null, "menu_fee"]);
    expect(toHome.serviceTotalCents).toBe(6000);

    const toSalon = await caller.appointment.update({ id: "appointment_1", locationType: "inSalon" });

    expect(toSalon.travelFee).toEqual({
      added: [],
      removed: [expect.objectContaining({ id: toHome.travelFee.added[0]?.id, menuItemId: "menu_fee", priceCents: 2000 })]
    });
    expect(state.services.map((service) => service.menuItemId)).toEqual([null]);

    await caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" });
    // Saved again at home, with another address: it is not moved, so nothing is added.
    const sameHome = await caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "77 Venue Rd" });

    expect(sameHome.travelFee).toEqual({ added: [], removed: [] });
    expect(state.services.filter((service) => service.menuItemId === "menu_fee")).toHaveLength(1);
  });

  it("leaves a fee the Stylist removed from an at-home Appointment removed until it is moved to at home again", async () => {
    const { state, caller } = createStore({ appointment: { locationType: "atHome", locationAddress: "10 Client St" } });

    // Another primary Client, then times and the address: the place stays at home, so no fee comes back.
    await caller.appointment.update({ id: "appointment_1", primaryClientId: "client_2" });
    await caller.appointment.update({
      id: "appointment_1",
      startsAt: "2026-10-23T16:00:00.000Z",
      endsAt: null,
      locationType: "atHome",
      customLocationAddress: ""
    });
    expect(state.services).toEqual([]);

    await caller.appointment.update({ id: "appointment_1", locationType: "inSalon" });
    await caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" });
    // It goes to the primary Client of the moment.
    expect(state.services).toEqual([expect.objectContaining({ menuItemId: "menu_fee", clientId: "client_2" })]);
  });

  it("does not add it to an Appointment that has a Service from the Travel Fee item already, for any of its Clients", async () => {
    const { state, caller } = createStore({ appointment: {}, services: [{ menuItemId: "menu_fee", clientId: "client_2", priceCents: 1500 }] });

    await expect(
      caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" })
    ).resolves.toMatchObject({ travelFee: { added: [], removed: [] } });
    expect(state.services).toHaveLength(1);
  });

  it("keeps Services of a Travel Fee item that is no longer the Travel Fee", async () => {
    const { state, caller } = createStore({
      menu: [{ ...travelFeeItem, id: "menu_new_fee" }],
      appointment: { locationType: "atHome" },
      services: [{ menuItemId: "menu_fee" }]
    });

    await expect(caller.appointment.update({ id: "appointment_1", locationType: "inSalon" })).resolves.toMatchObject({
      travelFee: { added: [], removed: [] }
    });
    expect(state.services).toHaveLength(1);
  });

  it("saves the place without the fee when the fee would take the total past the largest amount", async () => {
    const { state, caller } = createStore({ appointment: {}, services: [{ priceCents: maxAmountCents - 1000 }] });

    await expect(
      caller.appointment.update({ id: "appointment_1", locationType: "atHome", customLocationAddress: "" })
    ).resolves.toMatchObject({ locationType: "atHome", travelFee: { added: [], removed: [] } });
    expect(state.services).toHaveLength(1);
  });
});

describe("Travel Fee item", () => {
  function createMenuDb(item: Record<string, unknown>) {
    const update = vi.fn(({ data }: { data: Record<string, unknown> }) => ({
      ...item,
      ...Object.fromEntries(Object.entries(data).filter(([, value]) => value !== undefined))
    }));
    const create = vi.fn(({ data }: { data: Record<string, unknown> }) => ({
      id: "menu_2",
      createdAt: new Date(),
      updatedAt: new Date(),
      travelFee: false,
      ...Object.fromEntries(Object.entries(data).filter(([, value]) => value !== undefined))
    }));
    const updateMany = vi.fn().mockResolvedValue({ count: 1 });
    const caller = appRouter.createCaller({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        serviceMenuItem: {
          findFirst: vi.fn().mockResolvedValue(item),
          update,
          create,
          updateMany
        }
      }),
      stylist
    } as unknown as TRPCContext);

    return { caller, update, create, updateMany };
  }

  const item = {
    id: "menu_1",
    stylistId: "stylist_1",
    name: "Выезд на дом к клиенту",
    defaultPriceCents: 2000,
    group: null,
    travelFee: false,
    createdAt: new Date(),
    updatedAt: new Date()
  };

  it("is one item at most: marking one unmarks any other of the Stylist", async () => {
    const { caller, updateMany } = createMenuDb(item);

    await expect(
      caller.serviceMenu.save({ id: "menu_1", name: item.name, defaultPriceCents: 2000, travelFee: true })
    ).resolves.toMatchObject({ id: "menu_1", travelFee: true });
    expect(updateMany).toHaveBeenCalledWith({
      where: { stylistId: "stylist_1", travelFee: true, id: { not: "menu_1" } },
      data: { travelFee: false }
    });

    const created = createMenuDb(item);

    await expect(
      created.caller.serviceMenu.save({ name: "Выезд", defaultPriceCents: 2500, travelFee: true })
    ).resolves.toMatchObject({ id: "menu_2", travelFee: true });
    expect(created.updateMany).toHaveBeenCalledWith({
      where: { stylistId: "stylist_1", travelFee: true, id: { not: "menu_2" } },
      data: { travelFee: false }
    });
  });

  it("keeps the mark of an edited item that the save leaves out, so an older app does not clear it", async () => {
    const { caller, update, updateMany } = createMenuDb({ ...item, travelFee: true });

    await expect(caller.serviceMenu.save({ id: "menu_1", name: "Выезд", defaultPriceCents: 2500 })).resolves.toMatchObject({
      travelFee: true
    });
    expect(update.mock.calls[0]?.[0].data).not.toHaveProperty("travelFee", expect.anything());

    await expect(
      caller.serviceMenu.save({ id: "menu_1", name: "Выезд", defaultPriceCents: 2500, travelFee: false })
    ).resolves.toMatchObject({ travelFee: false });
    // Unmarking touches no other item.
    expect(updateMany).toHaveBeenCalledTimes(1);
  });

  it("is not the Travel Fee when it is created without saying so", async () => {
    const { caller, updateMany } = createMenuDb(item);

    await expect(caller.serviceMenu.save({ name: "Стрижка", defaultPriceCents: 4000 })).resolves.toMatchObject({ travelFee: false });
    expect(updateMany).not.toHaveBeenCalled();
  });
});
