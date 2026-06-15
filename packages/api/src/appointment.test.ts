import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

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

function createContext(db: TRPCContext["db"]): TRPCContext {
  return {
    clerkAuth: {
      userId: "clerk_1"
    },
    db,
    stylist
  } as unknown as TRPCContext;
}

function createDb(appointment: Record<string, unknown>, client: Record<string, unknown> = {}) {
  return {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist)
    },
    appointment,
    client
  } as unknown as TRPCContext["db"];
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
        note: "",
        participants: {
          create: [
            {
              clientId: "client_1"
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
              clientId: "client_1"
            },
            {
              clientId: "client_2"
            }
          ]
        }
      }),
      include: expect.any(Object)
    });
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
          id: "conflict_1"
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
      orderBy: {
        startsAt: "asc"
      }
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
      orderBy: {
        startsAt: "asc"
      }
    });
  });

  it("updates outcome and appointment-level note for an owned Appointment", async () => {
    const findFirst = vi.fn().mockResolvedValue({
      id: "appointment_1"
    });
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
            id: "appointment_1"
          }),
          update
        })
      )
    );

    await caller.appointment.update({
      id: "appointment_1",
      status: "scheduled"
    });

    expect(update).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      },
      data: {
        status: "scheduled"
      },
      include: expect.any(Object)
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
          findFirst: vi.fn().mockResolvedValue({
            id: "appointment_1"
          }),
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
});
