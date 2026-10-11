import { describe, expect, it, vi } from "vitest";
import { maxAmountCents } from "./input";
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

function createContext(db: TRPCContext["db"], photoOrigins: string[] = []): TRPCContext {
  return {
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
    photoOrigins,
    stylist
  } as unknown as TRPCContext;
}

function createDb(appointment: Record<string, unknown>, client: Record<string, unknown> = {}) {
  return {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUniqueOrThrow: vi.fn().mockResolvedValue(stylist)
    },
    appointment,
    client
  } as unknown as TRPCContext["db"];
}

// An Appointment row as the mutations read it before they change it.
const scheduledAppointment = {
  id: "appointment_1",
  stylistId: "stylist_1",
  primaryClientId: "client_1",
  startsAt: new Date("2026-06-13T15:00:00.000Z"),
  endsAt: null,
  status: "scheduled",
  locationType: "inSalon",
  locationAddress: "500 Salon Ave",
  locationAddressIsCustom: false,
  note: "",
  finalTotalCentsOverride: null,
  primaryClient: {
    id: "client_1",
    name: "Anna",
    address: "10 Client St"
  }
};

// A group Appointment kept in memory, with just the calls the group mutations make. Used with
// two mutations started together: each mock yields, so they would interleave if their checks
// were not inside the transaction.
function createGroupDb(clientIds: string[]) {
  const state = {
    primaryClientId: clientIds[0] ?? "",
    clientIds: [...clientIds],
    serviceClientIds: [] as string[],
    photoClientIds: [] as string[]
  };
  const read = () => ({
    ...scheduledAppointment,
    primaryClientId: state.primaryClientId,
    participants: state.clientIds.map((clientId) => ({
      clientId,
      client: {
        id: clientId,
        name: clientId,
        address: ""
      }
    })),
    services: []
  });
  const db = {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist)
    },
    appointment: {
      findFirst: vi.fn(async () => read()),
      findUniqueOrThrow: vi.fn(async () => read()),
      update: vi.fn(async ({ data }: { data: { primaryClientId?: string } }) => {
        state.primaryClientId = data.primaryClientId ?? state.primaryClientId;

        return read();
      })
    },
    appointmentParticipant: {
      findFirst: vi.fn(async ({ where }: { where: { clientId: string } }) =>
        state.clientIds.includes(where.clientId) ? { clientId: where.clientId } : null
      ),
      deleteMany: vi.fn(async ({ where }: { where: { clientId: string } }) => {
        state.clientIds = state.clientIds.filter((clientId) => clientId !== where.clientId);
      })
    },
    appointmentService: {
      count: vi.fn(async ({ where }: { where: { clientId: string } }) =>
        state.serviceClientIds.filter((clientId) => clientId === where.clientId).length
      ),
      create: vi.fn(async ({ data }: { data: { clientId: string } }) => {
        state.serviceClientIds.push(data.clientId);
      })
    },
    appointmentPhoto: {
      count: vi.fn(async ({ where }: { where: { clientId: string } }) =>
        state.photoClientIds.filter((clientId) => clientId === where.clientId).length
      ),
      deleteMany: vi.fn()
    }
  };

  return {
    state,
    db,
    caller: appRouter.createCaller(createContext(db as unknown as TRPCContext["db"]))
  };
}

describe("appointment router", () => {
  it("creates a scheduled in-salon Appointment for an owned primary Client", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const findFirst = vi.fn().mockResolvedValue({
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna",
      address: "10 Client St"
    });
    const findMany = vi.fn().mockResolvedValue([]);
    const create = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt,
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      createdAt,
      updatedAt: createdAt,
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: "10 Client St"
      }
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb(
          {
            findMany,
            create
          },
          {
            findFirst
          }
        )
      )
    );

    await expect(
      caller.appointment.create({
        primaryClientId: "client_1",
        startsAt: startsAt.toISOString()
      })
    ).resolves.toMatchObject({
      appointment: {
        id: "appointment_1",
        primaryClientName: "Anna",
        locationAddress: "500 Salon Ave",
        mapUrl: "https://www.google.com/maps/search/?api=1&query=500%20Salon%20Ave"
      },
      conflicts: []
    });
    expect(create).toHaveBeenCalledWith({
      data: {
        stylistId: "stylist_1",
        primaryClientId: "client_1",
        startsAt,
        endsAt: null,
        locationType: "inSalon",
        locationAddress: "500 Salon Ave",
        locationAddressIsCustom: false,
        note: "",
        finalTotalCentsOverride: null,
        participants: {
          create: [
            {
              clientId: "client_1",
              createdAt: expect.any(Date)
            }
          ]
        }
      },
      include: expect.any(Object)
    });
  });

  it("creates a group Appointment with additional Clients", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const findFirst = vi.fn().mockResolvedValue({
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna",
      address: ""
    });
    const findManyClients = vi.fn().mockResolvedValue([
      {
        id: "client_2",
        stylistId: "stylist_1",
        name: "Mila",
        address: ""
      }
    ]);
    const create = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt,
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: ""
      },
      participants: [
        {
          client: {
            id: "client_1",
            name: "Anna",
            address: ""
          }
        },
        {
          client: {
            id: "client_2",
            name: "Mila",
            address: ""
          }
        }
      ]
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb(
          {
            findMany: vi.fn().mockResolvedValue([]),
            create
          },
          {
            findFirst,
            findMany: findManyClients
          }
        )
      )
    );

    await expect(
      caller.appointment.create({
        primaryClientId: "client_1",
        additionalClientIds: ["client_2"],
        startsAt: startsAt.toISOString()
      })
    ).resolves.toMatchObject({
      appointment: {
        participants: [
          {
            clientId: "client_1",
            isPrimary: true
          },
          {
            clientId: "client_2",
            isPrimary: false
          }
        ]
      }
    });
    expect(create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        participants: {
          create: [
            {
              clientId: "client_1",
              createdAt: expect.any(Date)
            },
            {
              clientId: "client_2",
              createdAt: expect.any(Date)
            }
          ]
        }
      }),
      include: expect.any(Object)
    });

    // The Clients are stored in the order they were picked, a millisecond apart.
    const [first, second] = create.mock.calls[0]?.[0].data.participants.create ?? [];

    expect(second.createdAt.getTime() - first.createdAt.getTime()).toBe(1);
  });

  it("creates an inline minimal Client for Appointment Creation", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const createClient = vi.fn().mockResolvedValue({
      id: "client_2",
      name: "Walk-in",
      address: ""
    });
    const createAppointment = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_2",
      startsAt,
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      primaryClient: {
        id: "client_2",
        name: "Walk-in",
        address: ""
      }
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb(
          {
            findMany: vi.fn().mockResolvedValue([]),
            create: createAppointment
          },
          {
            create: createClient
          }
        )
      )
    );

    await caller.appointment.create({
      inlineClientName: "Walk-in",
      startsAt: startsAt.toISOString()
    });

    expect(createClient).toHaveBeenCalledWith({
      data: {
        stylistId: "stylist_1",
        name: "Walk-in",
        language: "en",
        phone: "",
        email: "",
        address: "",
        note: ""
      }
    });
  });

  it("defaults at-home location from primary Client address and returns non-blocking conflicts", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const findMany = vi.fn().mockResolvedValue([
      {
        id: "conflict_1",
        stylistId: "stylist_1",
        primaryClientId: "client_3",
        startsAt: new Date("2026-06-13T15:15:00.000Z"),
        endsAt: null,
        status: "scheduled",
        locationType: "inSalon",
        locationAddress: "500 Salon Ave",
        primaryClient: {
          id: "client_3",
          name: "Mila",
          address: ""
        }
      }
    ]);
    const create = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt,
      endsAt: null,
      status: "scheduled",
      locationType: "atHome",
      locationAddress: "10 Client St",
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: "10 Client St"
      }
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb(
          {
            findMany,
            create
          },
          {
            findFirst: vi.fn().mockResolvedValue({
              id: "client_1",
              name: "Anna",
              address: "10 Client St"
            })
          }
        )
      )
    );

    await expect(
      caller.appointment.create({
        primaryClientId: "client_1",
        startsAt: startsAt.toISOString(),
        locationType: "atHome"
      })
    ).resolves.toMatchObject({
      appointment: {
        locationAddress: "10 Client St"
      },
      conflicts: [
        {
          id: "conflict_1",
          primaryClientName: "Mila",
          startsAt: "2026-06-13T15:15:00.000Z",
          endsAt: null
        }
      ]
    });
  });

  it("lists calendar Appointments in a requested day range", async () => {
    const findMany = vi.fn().mockResolvedValue([]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findMany
        })
      )
    );

    await caller.appointment.list({
      start: "2026-06-13T00:00:00.000Z",
      end: "2026-06-14T00:00:00.000Z"
    });

    expect(findMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        startsAt: {
          gte: new Date("2026-06-13T00:00:00.000Z"),
          lt: new Date("2026-06-14T00:00:00.000Z")
        }
      },
      include: expect.any(Object),
      orderBy: [{ startsAt: "asc" }, { createdAt: "asc" }, { id: "asc" }]
    });
  });

  it("keeps past scheduled Appointments in the Home View as unresolved", async () => {
    const findMany = vi.fn().mockResolvedValue([
      {
        id: "past_1",
        stylistId: "stylist_1",
        primaryClientId: "client_1",
        startsAt: new Date("2026-06-12T15:00:00.000Z"),
        endsAt: null,
        status: "scheduled",
        locationType: "inSalon",
        locationAddress: "500 Salon Ave",
        note: "",
        primaryClient: {
          id: "client_1",
          name: "Anna",
          address: ""
        }
      }
    ]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findMany
        })
      )
    );

    await expect(
      caller.appointment.home({
        start: "2026-06-13T00:00:00.000Z",
        end: "2026-06-14T00:00:00.000Z"
      })
    ).resolves.toMatchObject([
      {
        id: "past_1",
        status: "scheduled"
      }
    ]);
    expect(findMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        OR: [
          {
            status: "scheduled",
            startsAt: {
              lt: expect.any(Date)
            }
          },
          {
            startsAt: {
              gte: new Date("2026-06-13T00:00:00.000Z"),
              lt: new Date("2026-06-14T00:00:00.000Z")
            }
          }
        ]
      },
      include: expect.any(Object),
      orderBy: [{ startsAt: "asc" }, { createdAt: "asc" }, { id: "asc" }]
    });
  });

  it("updates outcome and appointment-level note for an owned Appointment", async () => {
    const findFirst = vi.fn().mockResolvedValue(scheduledAppointment);
    const update = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt: new Date("2026-06-13T15:00:00.000Z"),
      endsAt: null,
      status: "completed",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "Finished without Services.",
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: ""
      }
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst,
          update
        })
      )
    );

    await expect(
      caller.appointment.update({
        id: "appointment_1",
        status: "completed",
        note: "Finished without Services."
      })
    ).resolves.toMatchObject({
      status: "completed",
      note: "Finished without Services."
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        status: "completed",
        note: "Finished without Services."
      },
      include: expect.any(Object)
    });
  });

  it("allows outcome states to remain editable", async () => {
    const update = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt: new Date("2026-06-13T15:00:00.000Z"),
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: ""
      }
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst: vi.fn().mockResolvedValue({
            ...scheduledAppointment,
            status: "completed",
            locationAddress: "1 Old Salon St"
          }),
          findMany: vi.fn().mockResolvedValue([]),
          update
        })
      )
    );

    await caller.appointment.update({
      id: "appointment_1",
      status: "scheduled"
    });

    // Scheduled again, an in-salon Appointment shows the salon's current address.
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        status: "scheduled",
        locationAddress: "500 Salon Ave",
        locationAddressIsCustom: false
      },
      include: expect.any(Object)
    });
  });

  it("takes the salon's current address when a Scheduled Appointment is saved as in salon again", async () => {
    const findFirst = vi.fn();
    const update = vi.fn().mockResolvedValue(scheduledAppointment);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst,
          update
        })
      )
    );

    // Stored before the salon address changed, and before there was one.
    for (const locationAddress of ["1 Old Salon St", ""]) {
      findFirst.mockResolvedValueOnce({
        ...scheduledAppointment,
        locationAddress
      });
      await caller.appointment.update({
        id: "appointment_1",
        locationType: "inSalon"
      });
      expect(update).toHaveBeenLastCalledWith({
        where: {
          id: "appointment_1"
        },
        data: {
          locationType: "inSalon",
          locationAddress: "500 Salon Ave",
          locationAddressIsCustom: false
        },
        include: expect.any(Object)
      });
    }

    // Without the location in the call the stored address is left alone.
    findFirst.mockResolvedValueOnce({
      ...scheduledAppointment,
      locationAddress: "1 Old Salon St"
    });
    await caller.appointment.update({
      id: "appointment_1",
      note: "Bring the toner."
    });
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        note: "Bring the toner."
      },
      include: expect.any(Object)
    });

    // An Appointment with an outcome keeps the address it had, also when the outcome is set in
    // the same call.
    findFirst.mockResolvedValueOnce({
      ...scheduledAppointment,
      status: "completed",
      locationAddress: "1 Old Salon St"
    });
    await caller.appointment.update({
      id: "appointment_1",
      locationType: "inSalon"
    });
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        locationType: "inSalon"
      },
      include: expect.any(Object)
    });

    findFirst.mockResolvedValueOnce({
      ...scheduledAppointment,
      locationAddress: "1 Old Salon St"
    });
    await caller.appointment.update({
      id: "appointment_1",
      status: "completed",
      locationType: "inSalon"
    });
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        status: "completed",
        locationType: "inSalon"
      },
      include: expect.any(Object)
    });
  });

  it("adds a Service from a menu default as an appointment-specific snapshot", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const appointmentServiceCreate = vi.fn().mockResolvedValue({
      id: "appointment_service_1"
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1",
            participants: [
              {
                clientId: "client_1"
              }
            ],
            services: []
          }),
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            id: "appointment_1",
            stylistId: "stylist_1",
            primaryClientId: "client_1",
            startsAt,
            endsAt: null,
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            note: "",
            finalTotalCentsOverride: null,
            primaryClient: {
              id: "client_1",
              name: "Anna",
              address: ""
            },
            participants: [
              {
                client: {
                  id: "client_1",
                  name: "Anna",
                  address: ""
                }
              }
            ],
            services: [
              {
                id: "appointment_service_1",
                appointmentId: "appointment_1",
                clientId: "client_1",
                menuItemId: "menu_1",
                name: "Haircut",
                priceCents: 8500,
                note: "",
                createdAt: startsAt
              }
            ]
          })
        },
        appointmentService: {
          create: appointmentServiceCreate
        },
        serviceMenuItem: {
          findFirst: vi.fn().mockResolvedValue({
            id: "menu_1",
            name: "Haircut",
            defaultPriceCents: 8500
          })
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.appointment.addService({
        appointmentId: "appointment_1",
        clientId: "client_1",
        menuItemId: "menu_1"
      })
    ).resolves.toMatchObject({
      services: [
        {
          id: "appointment_service_1",
          menuItemId: "menu_1",
          name: "Haircut",
          priceCents: 8500
        }
      ],
      serviceTotalCents: 8500,
      finalTotalCents: 8500
    });
    expect(appointmentServiceCreate).toHaveBeenCalledWith({
      data: {
        appointmentId: "appointment_1",
        clientId: "client_1",
        menuItemId: "menu_1",
        name: "Haircut",
        priceCents: 8500,
        note: ""
      }
    });
  });

  it("adds ad hoc Services and derives per-Client subtotals", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1",
            participants: [
              {
                clientId: "client_1"
              },
              {
                clientId: "client_2"
              }
            ],
            services: [
              {
                priceCents: 8000,
                requestKey: null
              }
            ]
          }),
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            id: "appointment_1",
            stylistId: "stylist_1",
            primaryClientId: "client_1",
            startsAt,
            endsAt: null,
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            note: "",
            finalTotalCentsOverride: null,
            primaryClient: {
              id: "client_1",
              name: "Anna",
              address: ""
            },
            participants: [
              {
                client: {
                  id: "client_1",
                  name: "Anna",
                  address: ""
                }
              },
              {
                client: {
                  id: "client_2",
                  name: "Mila",
                  address: ""
                }
              }
            ],
            services: [
              {
                id: "appointment_service_1",
                appointmentId: "appointment_1",
                clientId: "client_1",
                menuItemId: null,
                name: "Color",
                priceCents: 12000,
                note: "Roots",
                createdAt: startsAt
              },
              {
                id: "appointment_service_2",
                appointmentId: "appointment_1",
                clientId: "client_2",
                menuItemId: null,
                name: "Cut",
                priceCents: 8000,
                note: "",
                createdAt: startsAt
              }
            ]
          })
        },
        appointmentService: {
          create: vi.fn().mockResolvedValue({
            id: "appointment_service_1"
          })
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.appointment.addService({
        appointmentId: "appointment_1",
        clientId: "client_1",
        name: "Color",
        priceCents: 12000,
        note: "Roots"
      })
    ).resolves.toMatchObject({
      participants: [
        {
          clientId: "client_1",
          subtotalCents: 12000
        },
        {
          clientId: "client_2",
          subtotalCents: 8000
        }
      ],
      serviceTotalCents: 20000,
      finalTotalCents: 20000
    });
  });

  it("lets the final total override the sum of Services", async () => {
    const findFirst = vi.fn().mockResolvedValue(scheduledAppointment);
    const update = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt: new Date("2026-06-13T15:00:00.000Z"),
      endsAt: null,
      status: "completed",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      finalTotalCentsOverride: 17500,
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: ""
      },
      services: [
        {
          id: "appointment_service_1",
          appointmentId: "appointment_1",
          clientId: "client_1",
          menuItemId: null,
          name: "Color",
          priceCents: 20000,
          note: "",
          createdAt: new Date("2026-06-13T15:00:00.000Z")
        }
      ]
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst,
          update
        })
      )
    );

    await expect(
      caller.appointment.update({
        id: "appointment_1",
        finalTotalCentsOverride: 17500
      })
    ).resolves.toMatchObject({
      serviceTotalCents: 20000,
      finalTotalCents: 17500,
      finalTotalCentsOverride: 17500
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        finalTotalCentsOverride: 17500
      },
      include: expect.any(Object)
    });
  });

  it("adds, edits, and deletes multiple Color Formulas on a Service", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const colorFormulaCreate = vi.fn().mockResolvedValue({
      id: "formula_2"
    });
    const colorFormulaUpdate = vi.fn().mockResolvedValue({
      id: "formula_1"
    });
    const colorFormulaDelete = vi.fn().mockResolvedValue({
      id: "formula_1"
    });
    const appointmentFindUniqueOrThrow = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt,
      endsAt: null,
      status: "completed",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      finalTotalCentsOverride: null,
      primaryClient: {
        id: "client_1",
        name: "Anna",
        address: ""
      },
      participants: [
        {
          client: {
            id: "client_1",
            name: "Anna",
            address: ""
          }
        }
      ],
      services: [
        {
          id: "appointment_service_1",
          appointmentId: "appointment_1",
          clientId: "client_1",
          menuItemId: null,
          name: "Color",
          priceCents: 12000,
          note: "",
          createdAt: startsAt,
          colorFormulas: [
            {
              id: "formula_1",
              appointmentServiceId: "appointment_service_1",
              formula: "7N + 20 vol",
              placement: "Roots",
              createdAt: startsAt
            },
            {
              id: "formula_2",
              appointmentServiceId: "appointment_service_1",
              formula: "Gloss",
              placement: "",
              createdAt: startsAt
            }
          ]
        }
      ]
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findUniqueOrThrow: appointmentFindUniqueOrThrow
        },
        appointmentService: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_service_1",
            appointmentId: "appointment_1"
          })
        },
        colorFormula: {
          create: colorFormulaCreate,
          findFirst: vi.fn().mockResolvedValue({
            id: "formula_1",
            appointmentService: {
              appointmentId: "appointment_1"
            }
          }),
          update: colorFormulaUpdate,
          delete: colorFormulaDelete
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.appointment.addColorFormula({
        appointmentServiceId: "appointment_service_1",
        formula: "Gloss"
      })
    ).resolves.toMatchObject({
      services: [
        {
          colorFormulas: [
            {
              formula: "7N + 20 vol",
              placement: "Roots"
            },
            {
              formula: "Gloss",
              placement: ""
            }
          ]
        }
      ]
    });
    await caller.appointment.updateColorFormula({
      id: "formula_1",
      formula: "8N + 20 vol",
      placement: "Roots"
    });
    await caller.appointment.deleteColorFormula({
      id: "formula_1"
    });

    expect(colorFormulaCreate).toHaveBeenCalledWith({
      data: {
        appointmentServiceId: "appointment_service_1",
        formula: "Gloss",
        placement: ""
      }
    });
    expect(colorFormulaUpdate).toHaveBeenCalledWith({
      where: {
        id: "formula_1"
      },
      data: {
        formula: "8N + 20 vol",
        placement: "Roots"
      }
    });
    expect(colorFormulaDelete).toHaveBeenCalledWith({
      where: {
        id: "formula_1"
      }
    });
  });

  it("copies Services and Color Formulas from the last completed Appointment by default", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const appointmentFindFirst = vi.fn()
      .mockResolvedValueOnce({
        id: "target_1",
        participants: [
          {
            clientId: "client_1"
          }
        ],
        services: []
      })
      .mockResolvedValueOnce({
        id: "source_last",
        status: "completed",
        participants: [
          {
            clientId: "client_1"
          }
        ],
        services: [
          {
            clientId: "client_1",
            menuItemId: "menu_1",
            name: "Color",
            priceCents: 12000,
            note: "Copy me",
            colorFormulas: [
              {
                formula: "7N",
                placement: "Roots"
              }
            ]
          }
        ]
      });
    const appointmentServiceCreate = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: appointmentFindFirst,
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            id: "target_1",
            stylistId: "stylist_1",
            primaryClientId: "client_1",
            startsAt,
            endsAt: null,
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            note: "",
            finalTotalCentsOverride: null,
            primaryClient: {
              id: "client_1",
              name: "Anna",
              address: ""
            },
            participants: [
              {
                client: {
                  id: "client_1",
                  name: "Anna",
                  address: ""
                }
              }
            ],
            services: []
          })
        },
        appointmentService: {
          create: appointmentServiceCreate
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.copyServices({
      targetAppointmentId: "target_1"
    });

    // The last completed Appointment in which a Client of the target has Services.
    expect(appointmentFindFirst).toHaveBeenNthCalledWith(2, {
      where: {
        stylistId: "stylist_1",
        status: "completed",
        id: {
          not: "target_1"
        },
        OR: [
          {
            participants: {
              some: {
                clientId: "client_1"
              }
            },
            services: {
              some: {
                clientId: "client_1"
              }
            }
          }
        ]
      },
      include: expect.any(Object),
      orderBy: [{ startsAt: "desc" }, { createdAt: "desc" }, { id: "desc" }]
    });
    expect(appointmentServiceCreate).toHaveBeenCalledWith({
      data: {
        appointmentId: "target_1",
        clientId: "client_1",
        menuItemId: "menu_1",
        name: "Color",
        priceCents: 12000,
        note: "Copy me",
        createdAt: expect.any(Date),
        colorFormulas: {
          create: [
            {
              formula: "7N",
              placement: "Roots",
              createdAt: expect.any(Date)
            }
          ]
        }
      }
    });
  });

  it("copies from an explicitly selected older completed Appointment", async () => {
    const appointmentFindFirst = vi.fn()
      .mockResolvedValueOnce({
        id: "target_1",
        participants: [
          {
            clientId: "client_1"
          }
        ],
        services: []
      })
      .mockResolvedValueOnce({
        id: "older_source",
        status: "completed",
        participants: [
          {
            clientId: "client_1"
          }
        ],
        services: [
          {
            clientId: "client_1",
            menuItemId: null,
            name: "Older cut",
            priceCents: 4000,
            note: "",
            colorFormulas: []
          }
        ]
      });
    const appointmentServiceCreate = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: appointmentFindFirst,
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            id: "target_1",
            stylistId: "stylist_1",
            primaryClientId: "client_1",
            startsAt: new Date("2026-06-13T15:00:00.000Z"),
            endsAt: null,
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            note: "",
            finalTotalCentsOverride: null,
            primaryClient: {
              id: "client_1",
              name: "Anna",
              address: ""
            },
            services: []
          })
        },
        appointmentService: {
          create: appointmentServiceCreate
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.copyServices({
      targetAppointmentId: "target_1",
      sourceAppointmentId: "older_source"
    });

    // Whether it is still Completed is checked on the Appointment found, to say so when it is not.
    expect(appointmentFindFirst).toHaveBeenNthCalledWith(2, {
      where: {
        id: "older_source",
        stylistId: "stylist_1"
      },
      include: expect.any(Object),
      orderBy: expect.any(Array)
    });
    expect(appointmentServiceCreate).toHaveBeenCalledTimes(1);
    expect(appointmentServiceCreate).toHaveBeenCalledWith({
      data: expect.objectContaining({
        appointmentId: "target_1",
        name: "Older cut"
      })
    });
  });

  it("copies group Appointment Services only for Clients attached to both Appointments", async () => {
    const appointmentServiceCreate = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn()
            .mockResolvedValueOnce({
              id: "target_1",
              participants: [
                {
                  clientId: "client_1"
                }
              ],
              services: []
            })
            .mockResolvedValueOnce({
              id: "source_1",
              status: "completed",
              participants: [
                {
                  clientId: "client_1"
                },
                {
                  clientId: "client_2"
                }
              ],
              services: [
                {
                  clientId: "client_1",
                  menuItemId: null,
                  name: "Shared client service",
                  priceCents: 5000,
                  note: "",
                  colorFormulas: []
                },
                {
                  clientId: "client_2",
                  menuItemId: null,
                  name: "Excluded service",
                  priceCents: 9000,
                  note: "",
                  colorFormulas: []
                }
              ]
            }),
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            id: "target_1",
            stylistId: "stylist_1",
            primaryClientId: "client_1",
            startsAt: new Date("2026-06-13T15:00:00.000Z"),
            endsAt: null,
            status: "scheduled",
            locationType: "inSalon",
            locationAddress: "500 Salon Ave",
            note: "",
            finalTotalCentsOverride: null,
            primaryClient: {
              id: "client_1",
              name: "Anna",
              address: ""
            },
            services: []
          })
        },
        appointmentService: {
          create: appointmentServiceCreate
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.copyServices({
      targetAppointmentId: "target_1",
      sourceAppointmentId: "source_1"
    });

    expect(appointmentServiceCreate).toHaveBeenCalledTimes(1);
    expect(appointmentServiceCreate).toHaveBeenCalledWith({
      data: expect.objectContaining({
        clientId: "client_1",
        name: "Shared client service"
      })
    });
  });

  it("changes primary Client only to an attached participant", async () => {
    const participantFindFirst = vi.fn().mockResolvedValue({
      appointmentId: "appointment_1",
      clientId: "client_2"
    });
    const update = vi.fn().mockResolvedValue({
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_2",
      startsAt: new Date("2026-06-13T15:00:00.000Z"),
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      primaryClient: {
        id: "client_2",
        name: "Mila",
        address: ""
      },
      participants: [
        {
          client: {
            id: "client_1",
            name: "Anna",
            address: ""
          }
        },
        {
          client: {
            id: "client_2",
            name: "Mila",
            address: ""
          }
        }
      ]
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue(scheduledAppointment),
          update
        },
        appointmentParticipant: {
          findFirst: participantFindFirst
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.update({
      id: "appointment_1",
      primaryClientId: "client_2"
    });

    expect(participantFindFirst).toHaveBeenCalledWith({
      where: {
        appointmentId: "appointment_1",
        clientId: "client_2"
      }
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2"
      },
      include: expect.any(Object)
    });
  });

  it("promotes another Client when removing the primary participant", async () => {
    const appointmentUpdate = vi.fn();
    const participantDeleteMany = vi.fn();
    const serviceCount = vi.fn().mockResolvedValue(0);
    const photoCount = vi.fn().mockResolvedValue(0);
    const photoDeleteMany = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1",
            primaryClientId: "client_1",
            participants: [
              {
                clientId: "client_1"
              },
              {
                clientId: "client_2"
              }
            ]
          }),
          update: appointmentUpdate
        },
        appointmentParticipant: {
          deleteMany: participantDeleteMany
        },
        appointmentService: {
          count: serviceCount
        },
        appointmentPhoto: {
          count: photoCount,
          deleteMany: photoDeleteMany
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.removeParticipant({
      appointmentId: "appointment_1",
      clientId: "client_1"
    });

    expect(appointmentUpdate).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2"
      }
    });
    expect(participantDeleteMany).toHaveBeenCalledWith({
      where: {
        appointmentId: "appointment_1",
        clientId: "client_1"
      }
    });
    expect(serviceCount).toHaveBeenCalledWith({
      where: {
        appointmentId: "appointment_1",
        clientId: "client_1"
      }
    });
    expect(photoCount).toHaveBeenCalledWith({
      where: {
        appointmentId: "appointment_1",
        clientId: "client_1"
      }
    });
    // Removing a Client never deletes Appointment Photos.
    expect(photoDeleteMany).not.toHaveBeenCalled();
  });

  it("adds, updates, and deletes Appointment Photos with UploadThing metadata", async () => {
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const appointmentOutput = {
      id: "appointment_1",
      stylistId: "stylist_1",
      primaryClientId: "client_1",
      startsAt: new Date("2026-06-13T15:00:00.000Z"),
      endsAt: null,
      status: "scheduled",
      locationType: "inSalon",
      locationAddress: "500 Salon Ave",
      note: "",
      finalTotalCentsOverride: null,
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
      services: [],
      photos: [
        {
          id: "photo_1",
          appointmentId: "appointment_1",
          clientId: "client_1",
          category: "after",
          status: "stored",
          provider: "uploadthing",
          fileKey: "ut_file_1",
          url: "https://app-id.ufs.sh/f/ut_file_1",
          thumbnailUrl: "https://app-id.ufs.sh/f/ut_file_1",
          width: 1200,
          height: 900,
          uploadError: null,
          createdAt
        }
      ]
    };
    const photoCreate = vi.fn().mockResolvedValue({
      id: "photo_1"
    });
    const photoUpdate = vi.fn();
    const photoDelete = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1",
            participants: [
              {
                clientId: "client_1"
              }
            ],
            photos: []
          }),
          findUniqueOrThrow: vi.fn().mockResolvedValue(appointmentOutput)
        },
        appointmentPhoto: {
          create: photoCreate,
          // By id: the photo to update or delete. By file: no other photo has it.
          findFirst: vi.fn(async ({ where }: { where: { id?: string } }) =>
            where.id
              ? {
                  id: "photo_1",
                  appointmentId: "appointment_1"
                }
              : null
          ),
          update: photoUpdate,
          delete: photoDelete
        }
      } as unknown as TRPCContext["db"], ["https://app-id.ufs.sh"])
    );

    await expect(
      caller.appointment.addPhoto({
        appointmentId: "appointment_1",
        clientId: "client_1",
        category: "after",
        fileKey: "ut_file_1",
        url: "https://app-id.ufs.sh/f/ut_file_1",
        thumbnailUrl: "https://app-id.ufs.sh/f/ut_file_1",
        width: 1200,
        height: 900
      })
    ).resolves.toMatchObject({
      photos: [
        {
          id: "photo_1",
          clientId: "client_1",
          category: "after",
          status: "stored",
          url: "https://app-id.ufs.sh/f/ut_file_1"
        }
      ]
    });
    expect(photoCreate).toHaveBeenCalledWith({
      data: {
        appointmentId: "appointment_1",
        clientId: "client_1",
        category: "after",
        status: "stored",
        provider: "uploadthing",
        fileKey: "ut_file_1",
        url: "https://app-id.ufs.sh/f/ut_file_1",
        thumbnailUrl: "https://app-id.ufs.sh/f/ut_file_1",
        width: 1200,
        height: 900,
        uploadError: null
      }
    });

    await caller.appointment.updatePhoto({
      id: "photo_1",
      category: "before",
      status: "pendingUpload",
      uploadError: null
    });
    expect(photoUpdate).toHaveBeenCalledWith({
      where: {
        id: "photo_1"
      },
      data: {
        category: "before",
        status: "pendingUpload",
        uploadError: null
      }
    });

    await caller.appointment.deletePhoto({
      id: "photo_1"
    });
    expect(photoDelete).toHaveBeenCalledWith({
      where: {
        id: "photo_1"
      }
    });
  });

  it("blocks removing a participant with Services", async () => {
    const appointmentUpdate = vi.fn();
    const participantDeleteMany = vi.fn();
    const serviceCount = vi.fn().mockResolvedValue(1);
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1",
            primaryClientId: "client_1",
            participants: [
              {
                clientId: "client_1"
              },
              {
                clientId: "client_2"
              }
            ]
          }),
          update: appointmentUpdate
        },
        appointmentParticipant: {
          deleteMany: participantDeleteMany
        },
        appointmentService: {
          count: serviceCount
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_1"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });

    expect(appointmentUpdate).not.toHaveBeenCalled();
    expect(participantDeleteMany).not.toHaveBeenCalled();
  });

  it("refuses to remove a Client who has Appointment Photos and deletes none of them", async () => {
    const { state, db, caller } = createGroupDb(["client_1", "client_2"]);

    state.photoClientIds.push("client_2");

    await expect(
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Client has Appointment Photos attached to this Appointment."
    });

    expect(state.clientIds).toEqual(["client_1", "client_2"]);
    expect(db.appointmentParticipant.deleteMany).not.toHaveBeenCalled();
    expect(db.appointmentPhoto.deleteMany).not.toHaveBeenCalled();
  });

  it("says so when the Client to remove is no longer attached to the Appointment", async () => {
    const { state, caller } = createGroupDb(["client_1"]);

    // One Client is left, but the refusal is about the Client named, not about the last Client.
    await expect(
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Client must be attached to the Appointment."
    });
    await expect(
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_1"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Appointment must keep at least one Client."
    });
    await expect(
      caller.appointment.update({
        id: "appointment_1",
        primaryClientId: "client_2"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Primary Client must be attached to the Appointment."
    });
    await expect(
      caller.appointment.addService({
        appointmentId: "appointment_1",
        clientId: "client_2",
        name: "Cut"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Service Client must be attached to the Appointment."
    });

    expect(state.clientIds).toEqual(["client_1"]);
  });

  it("keeps one Client when both Clients of an Appointment are removed at the same moment", async () => {
    const { state, caller } = createGroupDb(["client_1", "client_2"]);

    const results = await Promise.allSettled([
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_1"
      }),
      caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      })
    ]);

    expect(results.map((result) => result.status)).toEqual(["fulfilled", "rejected"]);
    expect(results[1]).toMatchObject({
      reason: {
        code: "BAD_REQUEST",
        message: "Appointment must keep at least one Client."
      }
    });
    expect(state.clientIds).toEqual(["client_2"]);
    expect(state.primaryClientId).toBe("client_2");
  });

  it("keeps the primary Client attached when make primary and removal arrive together", async () => {
    const makePrimaryFirst = createGroupDb(["client_1", "client_2"]);

    await Promise.allSettled([
      makePrimaryFirst.caller.appointment.update({
        id: "appointment_1",
        primaryClientId: "client_2"
      }),
      makePrimaryFirst.caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      })
    ]);

    // The removal saw client_2 as primary and moved the primary back before removing them.
    expect(makePrimaryFirst.state).toMatchObject({
      primaryClientId: "client_1",
      clientIds: ["client_1"]
    });

    const removalFirst = createGroupDb(["client_1", "client_2"]);
    const results = await Promise.allSettled([
      removalFirst.caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      }),
      removalFirst.caller.appointment.update({
        id: "appointment_1",
        primaryClientId: "client_2"
      })
    ]);

    expect(results[1]).toMatchObject({
      reason: {
        code: "BAD_REQUEST",
        message: "Primary Client must be attached to the Appointment."
      }
    });
    expect(removalFirst.state).toMatchObject({
      primaryClientId: "client_1",
      clientIds: ["client_1"]
    });
  });

  it("never both adds a Service for a Client and removes that Client", async () => {
    const serviceFirst = createGroupDb(["client_1", "client_2"]);
    const serviceFirstResults = await Promise.allSettled([
      serviceFirst.caller.appointment.addService({
        appointmentId: "appointment_1",
        clientId: "client_2",
        name: "Cut"
      }),
      serviceFirst.caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      })
    ]);

    expect(serviceFirstResults[1]).toMatchObject({
      reason: {
        message: "Client has Services attached to this Appointment."
      }
    });
    expect(serviceFirst.state).toMatchObject({
      clientIds: ["client_1", "client_2"],
      serviceClientIds: ["client_2"]
    });

    const removalFirst = createGroupDb(["client_1", "client_2"]);
    const removalFirstResults = await Promise.allSettled([
      removalFirst.caller.appointment.removeParticipant({
        appointmentId: "appointment_1",
        clientId: "client_2"
      }),
      removalFirst.caller.appointment.addService({
        appointmentId: "appointment_1",
        clientId: "client_2",
        name: "Cut"
      })
    ]);

    expect(removalFirstResults[1]).toMatchObject({
      reason: {
        message: "Service Client must be attached to the Appointment."
      }
    });
    expect(removalFirst.state).toMatchObject({
      clientIds: ["client_1"],
      serviceClientIds: []
    });
  });

  it("creates the inline Client inside the transaction that a refusal rolls back", async () => {
    const createClient = vi.fn().mockResolvedValue({
      id: "client_new",
      name: "Walk-in",
      address: ""
    });
    const createAppointment = vi.fn();
    const context = createContext(
      createDb(
        {
          findMany: vi.fn().mockResolvedValue([]),
          create: createAppointment
        },
        {
          create: createClient,
          findMany: vi.fn().mockResolvedValue([])
        }
      )
    );
    const db = context.db as unknown as ReturnType<typeof withTransaction>;

    await expect(
      appRouter.createCaller(context).appointment.create({
        inlineClientName: "Walk-in",
        additionalClientIds: ["missing"],
        startsAt: "2026-06-13T15:00:00.000Z"
      })
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "One or more group Clients were not found."
    });

    // The lock comes first, the Client insert after it, and the refusal fails the transaction,
    // which is what makes the database discard the Client.
    expect(db.$executeRaw.mock.invocationCallOrder[0]).toBeLessThan(createClient.mock.invocationCallOrder[0] ?? 0);
    await expect(db.$transaction.mock.results[0]?.value).rejects.toMatchObject({
      code: "NOT_FOUND"
    });
    expect(createAppointment).not.toHaveBeenCalled();
  });

  it("changes start, end and location of an existing Appointment", async () => {
    const update = vi.fn().mockResolvedValue(scheduledAppointment);
    const findMany = vi.fn().mockResolvedValue([
      {
        ...scheduledAppointment,
        id: "conflict_1",
        primaryClientId: "client_3",
        startsAt: new Date("2026-06-14T10:15:00.000Z"),
        primaryClient: {
          id: "client_3",
          name: "Mila",
          address: ""
        }
      }
    ]);
    const caller = appRouter.createCaller(
      createContext(
        createDb(
          {
            findFirst: vi.fn().mockResolvedValue(scheduledAppointment),
            findMany,
            update
          },
          {
            findUniqueOrThrow: vi.fn().mockResolvedValue({
              address: "10 Client St"
            })
          }
        )
      )
    );

    // Moved into an occupied time: saved, and the reply names the Appointment it overlaps.
    await expect(
      caller.appointment.update({
        id: "appointment_1",
        startsAt: "2026-06-14T10:00:00.000Z",
        endsAt: "2026-06-14T11:00:00.000Z",
        locationType: "atHome",
        customLocationAddress: " 77 Venue Rd "
      })
    ).resolves.toMatchObject({
      conflicts: [
        {
          id: "conflict_1",
          primaryClientName: "Mila",
          startsAt: "2026-06-14T10:15:00.000Z",
          endsAt: null
        }
      ]
    });
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        startsAt: new Date("2026-06-14T10:00:00.000Z"),
        endsAt: new Date("2026-06-14T11:00:00.000Z"),
        locationType: "atHome",
        locationAddress: "77 Venue Rd",
        locationAddressIsCustom: true
      },
      include: expect.any(Object)
    });
    // The overlap check is the one of Appointment Creation, leaving out the Appointment itself.
    expect(findMany).toHaveBeenLastCalledWith({
      where: {
        stylistId: "stylist_1",
        status: "scheduled",
        startsAt: {
          lt: new Date("2026-06-14T11:00:00.000Z")
        },
        id: {
          not: "appointment_1"
        }
      },
      include: expect.any(Object),
      orderBy: [{ startsAt: "asc" }, { createdAt: "asc" }, { id: "asc" }]
    });

    // At home without a custom address is the primary Client's address; null clears the end time.
    await caller.appointment.update({
      id: "appointment_1",
      endsAt: null,
      locationType: "atHome",
      customLocationAddress: ""
    });
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        endsAt: null,
        locationType: "atHome",
        locationAddress: "10 Client St",
        locationAddressIsCustom: false
      },
      include: expect.any(Object)
    });
  });

  it("validates a changed time like Appointment Creation and reports no overlap for other changes", async () => {
    const update = vi.fn().mockResolvedValue(scheduledAppointment);
    const findMany = vi.fn().mockResolvedValue([]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst: vi.fn().mockResolvedValue({
            ...scheduledAppointment,
            endsAt: new Date("2026-06-13T16:00:00.000Z")
          }),
          findMany,
          update
        })
      )
    );

    await expect(
      caller.appointment.update({
        id: "appointment_1",
        endsAt: "2026-06-13T15:00:00.000Z"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "End time must be after start time."
    });
    // A new start is checked against the end the Appointment already has.
    await expect(
      caller.appointment.update({
        id: "appointment_1",
        startsAt: "2026-06-13T17:00:00.000Z"
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "End time must be after start time."
    });
    expect(update).not.toHaveBeenCalled();

    await expect(
      caller.appointment.update({
        id: "appointment_1",
        note: "Bring the toner."
      })
    ).resolves.toMatchObject({
      conflicts: []
    });
    expect(findMany).not.toHaveBeenCalled();
  });

  it("reports the overlap when an Appointment is set back to Scheduled into an occupied time", async () => {
    const findMany = vi.fn().mockResolvedValue([
      {
        ...scheduledAppointment,
        id: "conflict_1",
        endsAt: new Date("2026-06-13T16:00:00.000Z")
      }
    ]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst: vi.fn().mockResolvedValue({
            ...scheduledAppointment,
            status: "canceled"
          }),
          findMany,
          update: vi.fn().mockResolvedValue(scheduledAppointment)
        })
      )
    );

    await expect(
      caller.appointment.update({
        id: "appointment_1",
        status: "scheduled"
      })
    ).resolves.toMatchObject({
      status: "scheduled",
      conflicts: [
        {
          id: "conflict_1",
          primaryClientName: "Anna",
          startsAt: "2026-06-13T15:00:00.000Z",
          endsAt: "2026-06-13T16:00:00.000Z"
        }
      ]
    });
  });

  it("moves an at-home Appointment to the new primary Client's address unless the address is custom", async () => {
    const atHome = {
      ...scheduledAppointment,
      locationType: "atHome",
      locationAddress: "10 Client St"
    };
    const findFirst = vi.fn();
    const update = vi.fn().mockResolvedValue(atHome);
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst,
          update
        },
        appointmentParticipant: {
          findFirst: vi.fn().mockResolvedValue({
            clientId: "client_2"
          })
        },
        client: {
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            address: "22 Bob St"
          })
        }
      } as unknown as TRPCContext["db"])
    );
    const makePrimary = () =>
      caller.appointment.update({
        id: "appointment_1",
        primaryClientId: "client_2"
      });

    findFirst.mockResolvedValueOnce(atHome);
    await makePrimary();
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2",
        locationAddress: "22 Bob St",
        locationAddressIsCustom: false
      },
      include: expect.any(Object)
    });

    findFirst.mockResolvedValueOnce({
      ...atHome,
      locationAddress: "5 Venue Way",
      locationAddressIsCustom: true
    });
    await makePrimary();
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2"
      },
      include: expect.any(Object)
    });

    // From before the flag existed: an address that differs from the primary Client's was typed.
    findFirst.mockResolvedValueOnce({
      ...atHome,
      locationAddress: "5 Venue Way",
      locationAddressIsCustom: null
    });
    await makePrimary();
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2"
      },
      include: expect.any(Object)
    });

    findFirst.mockResolvedValueOnce({
      ...atHome,
      locationAddressIsCustom: null
    });
    await makePrimary();
    expect(update).toHaveBeenLastCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2",
        locationAddress: "22 Bob St",
        locationAddressIsCustom: false
      },
      include: expect.any(Object)
    });
  });

  it("moves the at-home address with the primary Client when the primary is removed", async () => {
    const appointmentUpdate = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: vi.fn().mockResolvedValue({
            ...scheduledAppointment,
            locationType: "atHome",
            locationAddress: "10 Client St",
            participants: [
              {
                clientId: "client_1"
              },
              {
                clientId: "client_2"
              }
            ]
          }),
          update: appointmentUpdate
        },
        appointmentParticipant: {
          deleteMany: vi.fn()
        },
        appointmentService: {
          count: vi.fn().mockResolvedValue(0)
        },
        appointmentPhoto: {
          count: vi.fn().mockResolvedValue(0)
        },
        client: {
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            address: "22 Bob St"
          })
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.removeParticipant({
      appointmentId: "appointment_1",
      clientId: "client_1"
    });

    expect(appointmentUpdate).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        primaryClientId: "client_2",
        locationAddress: "22 Bob St",
        locationAddressIsCustom: false
      }
    });
  });

  it("returns the Clients primary first, then in the order they were added", async () => {
    const addedAt = new Date("2026-06-13T12:00:00.000Z");
    const participant = (id: string, clientId: string, createdAt: Date) => ({
      id,
      createdAt,
      client: {
        id: clientId,
        name: clientId,
        address: ""
      }
    });
    const findMany = vi.fn().mockResolvedValue([
      {
        ...scheduledAppointment,
        primaryClientId: "client_3",
        locationType: "atHome",
        locationAddress: "5 Venue Way",
        locationAddressIsCustom: true,
        // As the database may return them: equal timestamps in no particular order.
        participants: [
          participant("p_b", "client_2", addedAt),
          participant("p_d", "client_4", new Date(addedAt.getTime() + 1)),
          participant("p_c", "client_3", new Date(addedAt.getTime() + 1)),
          participant("p_a", "client_1", addedAt)
        ]
      }
    ]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findMany
        })
      )
    );
    const [appointment] = await caller.appointment.list({
      start: "2026-06-13T00:00:00.000Z",
      end: "2026-06-14T00:00:00.000Z"
    });

    expect(appointment?.participants.map((item) => item.clientId)).toEqual([
      "client_3",
      "client_1",
      "client_2",
      "client_4"
    ]);
    expect(appointment?.participants.map((item) => item.isPrimary)).toEqual([true, false, false, false]);
    // The custom address is reported apart from the address shown.
    expect(appointment).toMatchObject({
      locationAddress: "5 Venue Way",
      customLocationAddress: "5 Venue Way"
    });
  });

  it("deletes an owned Appointment", async () => {
    const deleteMany = vi.fn().mockResolvedValue({
      count: 1
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          deleteMany
        })
      )
    );

    await expect(
      caller.appointment.delete({
        id: "appointment_1"
      })
    ).resolves.toEqual({
      id: "appointment_1"
    });
    expect(deleteMany).toHaveBeenCalledWith({
      where: {
        id: "appointment_1",
        stylistId: "stylist_1"
      }
    });
  });

  // An Appointment whose Services are given by price, with the calls the Service mutations make.
  function createPricedDb(priceCents: number[]) {
    const services = priceCents.map((price, index) => ({
      id: `service_${index + 1}`,
      appointmentId: "appointment_1",
      clientId: "client_1",
      menuItemId: null,
      name: `Service ${index + 1}`,
      priceCents: price,
      note: "",
      requestKey: null,
      createdAt: new Date("2026-06-13T15:00:00.000Z")
    }));
    const read = () => ({
      ...scheduledAppointment,
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
      services: [...services]
    });
    const create = vi.fn(({ data }: { data: { name: string; priceCents: number } }) => {
      services.push({
        ...services[0]!,
        ...data,
        id: `service_${services.length + 1}`
      });

      return Promise.resolve({});
    });
    const update = vi.fn(({ where, data }: { where: { id: string }; data: { priceCents: number } }) => {
      Object.assign(services.find((service) => service.id === where.id) ?? {}, data);

      return Promise.resolve({});
    });
    const db = {
      stylist: {
        upsert: vi.fn().mockResolvedValue(stylist)
      },
      appointment: {
        findFirst: vi.fn(() => Promise.resolve(read())),
        findUniqueOrThrow: vi.fn(() => Promise.resolve(read()))
      },
      appointmentService: {
        findFirst: vi.fn(({ where }: { where: { id: string } }) => {
          const service = services.find((candidate) => candidate.id === where.id);

          return Promise.resolve(service ? { ...service, appointment: { services: [...services] } } : null);
        }),
        create,
        update
      },
      serviceMenuItem: {
        findFirst: vi.fn().mockResolvedValue({
          id: "menu_1",
          name: "Extensions",
          defaultPriceCents: maxAmountCents
        })
      }
    };

    return {
      create,
      update,
      db,
      caller: appRouter.createCaller(createContext(db as unknown as TRPCContext["db"]))
    };
  }

  const totalTooLarge = {
    code: "BAD_REQUEST",
    message: "Appointment total is too large."
  };

  it("refuses a Service that would take the Appointment's Services total past the maximum", async () => {
    const { caller, create } = createPricedDb([maxAmountCents - 1000]);
    const service = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      name: "Second Ten"
    };

    await expect(caller.appointment.addService({ ...service, priceCents: 1001 })).rejects.toMatchObject(totalTooLarge);
    // A Service Menu Item's default price counts the same.
    await expect(caller.appointment.addService({ ...service, menuItemId: "menu_1" })).rejects.toMatchObject(totalTooLarge);
    expect(create).not.toHaveBeenCalled();

    // Up to the maximum is fine, and every total in the reply stays within it.
    await expect(caller.appointment.addService({ ...service, priceCents: 1000 })).resolves.toMatchObject({
      serviceTotalCents: maxAmountCents,
      finalTotalCents: maxAmountCents,
      participants: [
        {
          subtotalCents: maxAmountCents
        }
      ]
    });
    expect(create).toHaveBeenCalledTimes(1);
  });

  it("refuses a Service price change that would take the total past the maximum", async () => {
    const { caller, update } = createPricedDb([maxAmountCents - 1000, 500]);

    await expect(
      caller.appointment.updateService({
        id: "service_2",
        name: "Service 2",
        priceCents: 1001
      })
    ).rejects.toMatchObject(totalTooLarge);
    expect(update).not.toHaveBeenCalled();

    await expect(
      caller.appointment.updateService({
        id: "service_2",
        name: "Service 2",
        priceCents: 1000
      })
    ).resolves.toMatchObject({
      serviceTotalCents: maxAmountCents
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "service_2"
      },
      data: {
        name: "Service 2",
        priceCents: 1000,
        note: ""
      }
    });
  });

  it("lets a Service be renamed or made cheaper in an Appointment that is already past the maximum", async () => {
    // Saved before the limit existed.
    const { caller, update } = createPricedDb([2_147_483_647, 1000]);

    await caller.appointment.updateService({
      id: "service_2",
      name: "Renamed",
      priceCents: 1000
    });
    await caller.appointment.updateService({
      id: "service_2",
      name: "Renamed",
      priceCents: 0
    });

    expect(update).toHaveBeenCalledTimes(2);
  });

  it("refuses to copy Services that would take the total past the maximum and copies none of them", async () => {
    const appointmentServiceCreate = vi.fn();
    const source = (priceCents: number) => ({
      id: "source_1",
      status: "completed",
      participants: [
        {
          clientId: "client_1"
        }
      ],
      services: [
        {
          id: "service_1",
          clientId: "client_1",
          menuItemId: null,
          name: "Cut",
          priceCents: 1000,
          note: "",
          createdAt: new Date("2026-06-01T10:00:00.000Z"),
          colorFormulas: []
        },
        {
          id: "service_2",
          clientId: "client_1",
          menuItemId: null,
          name: "Extensions",
          priceCents,
          note: "",
          createdAt: new Date("2026-06-01T10:30:00.000Z"),
          colorFormulas: []
        }
      ]
    });
    const target = {
      id: "target_1",
      participants: [
        {
          clientId: "client_1"
        }
      ],
      services: [
        {
          priceCents: 5000,
          requestKey: null
        }
      ]
    };
    const appointmentFindFirst = vi.fn()
      .mockResolvedValueOnce(target)
      .mockResolvedValueOnce(source(maxAmountCents - 6000 + 1))
      .mockResolvedValueOnce(target)
      .mockResolvedValueOnce(source(maxAmountCents - 6000));
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        appointment: {
          findFirst: appointmentFindFirst,
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            ...scheduledAppointment,
            id: "target_1",
            participants: [],
            services: []
          })
        },
        appointmentService: {
          create: appointmentServiceCreate
        }
      } as unknown as TRPCContext["db"])
    );
    const copy = {
      targetAppointmentId: "target_1",
      sourceAppointmentId: "source_1"
    };

    await expect(caller.appointment.copyServices(copy)).rejects.toMatchObject(totalTooLarge);
    expect(appointmentServiceCreate).not.toHaveBeenCalled();

    await caller.appointment.copyServices(copy);
    expect(appointmentServiceCreate).toHaveBeenCalledTimes(2);
  });

  // An Appointment Photo is loaded by the app as an image from its URL.
  const photoStorage = "https://app-id.ufs.sh";

  function createPhotoCaller(photoOrigins: string[] = [photoStorage], photosWithFile: { id: string }[] = []) {
    const photoCreate = vi.fn().mockResolvedValue({
      id: "photo_1"
    });
    const photoFindFirst = vi.fn().mockResolvedValue(photosWithFile[0] ?? null);
    const caller = appRouter.createCaller(
      createContext(
        {
          stylist: {
            upsert: vi.fn().mockResolvedValue(stylist)
          },
          appointment: {
            findFirst: vi.fn().mockResolvedValue({
              id: "appointment_1",
              participants: [
                {
                  clientId: "client_1"
                }
              ],
              photos: []
            }),
            findUniqueOrThrow: vi.fn().mockResolvedValue({
              ...scheduledAppointment,
              participants: [],
              services: [],
              photos: []
            })
          },
          appointmentPhoto: {
            create: photoCreate,
            findFirst: photoFindFirst
          }
        } as unknown as TRPCContext["db"],
        photoOrigins
      )
    );

    return {
      caller,
      photoCreate,
      photoFindFirst,
      addPhoto: (url: string, thumbnailUrl?: string, fileKey = "x") =>
        caller.appointment.addPhoto({
          appointmentId: "appointment_1",
          clientId: "client_1",
          category: "other",
          fileKey,
          url,
          thumbnailUrl
        })
    };
  }

  const storageRefusal = {
    code: "BAD_REQUEST",
    message: "Appointment Photo URLs must point at UploadThing storage."
  };

  it.each([
    ["another website", "https://example.com/x.jpg"],
    ["this server", "http://10.0.2.2:3000/profile-shares/token/image?probe=1"],
    ["a script", "javascript:alert(1)"],
    ["a local file", "file:///etc/passwd"],
    ["the photo storage without https", "http://app-id.ufs.sh/f/x"],
    ["the storage of another UploadThing app", "https://other-app.ufs.sh/f/x"],
    ["UploadThing's address for the files of every app", "https://utfs.io/f/x"],
    ["a host that only ends like the photo storage", "https://evilapp-id.ufs.sh/f/x"],
    ["a host that only starts like the photo storage", "https://app-id.ufs.sh.example.com/f/x"],
    ["the photo storage on another port", "https://app-id.ufs.sh:8443/f/x"],
    ["another file in the photo storage", "https://app-id.ufs.sh/f/y"],
    ["a file whose name only ends like this one", "https://app-id.ufs.sh/f/yx"]
  ])("refuses to store %s as an Appointment Photo", async (_label, url) => {
    const { addPhoto, photoCreate } = createPhotoCaller();

    await expect(addPhoto(url)).rejects.toMatchObject(storageRefusal);
    // The thumbnail is loaded by the app as well.
    await expect(addPhoto(`${photoStorage}/f/x`, url)).rejects.toMatchObject(storageRefusal);
    expect(photoCreate).not.toHaveBeenCalled();
  });

  it("stores an Appointment Photo whose URLs are its file in the photo storage", async () => {
    const { addPhoto, photoCreate, photoFindFirst } = createPhotoCaller();

    await addPhoto(`${photoStorage}/f/x`, `${photoStorage}/f/x`);
    await addPhoto(`${photoStorage}/f/x`);
    await addPhoto(`${photoStorage}/f/key%20with%2Fsigns`, undefined, "key with/signs");

    expect(photoCreate).toHaveBeenCalledTimes(3);
    expect(photoFindFirst).toHaveBeenCalledWith({
      where: {
        fileKey: "x"
      },
      select: {
        id: true
      }
    });
  });

  it("refuses a URL that comes without the file it should name", async () => {
    const { caller, photoCreate } = createPhotoCaller();
    const failed = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      category: "other",
      status: "failed"
    } as const;

    await expect(caller.appointment.addPhoto({ ...failed, url: `${photoStorage}/f/x` })).rejects.toMatchObject(storageRefusal);
    await expect(caller.appointment.addPhoto({ ...failed, fileKey: " ", thumbnailUrl: `${photoStorage}/f/` })).rejects.toMatchObject(
      storageRefusal
    );
    expect(photoCreate).not.toHaveBeenCalled();
  });

  it("refuses a stored file that another Appointment Photo already has", async () => {
    // A photo of another Appointment, of this Stylist or of another one: the search is by file alone.
    const { addPhoto, photoCreate } = createPhotoCaller([photoStorage], [{ id: "photo_of_someone" }]);

    await expect(addPhoto(`${photoStorage}/f/x`)).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "This file already belongs to another Appointment Photo."
    });
    expect(photoCreate).not.toHaveBeenCalled();
  });

  it("accepts no stored photo on a server without photo storage", async () => {
    const { addPhoto, photoCreate } = createPhotoCaller([]);

    await expect(addPhoto(`${photoStorage}/f/x`)).rejects.toMatchObject(storageRefusal);
    expect(photoCreate).not.toHaveBeenCalled();
  });

  it("accepts the storage emulator of the dev backend only when the server names its origin", async () => {
    const emulatorUrl = "http://127.0.0.1:3000/_ut/f/x";
    const devBackend = createPhotoCaller(["http://127.0.0.1:3000"]);

    await devBackend.addPhoto(emulatorUrl, emulatorUrl);
    expect(devBackend.photoCreate).toHaveBeenCalledTimes(1);
    await expect(devBackend.addPhoto("http://127.0.0.1:3001/_ut/f/x")).rejects.toMatchObject(storageRefusal);
    await expect(devBackend.addPhoto(`${photoStorage}/f/x`)).rejects.toMatchObject(storageRefusal);

    await expect(createPhotoCaller().addPhoto(emulatorUrl)).rejects.toMatchObject(storageRefusal);
  });
});
