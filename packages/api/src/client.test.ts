import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

function createContext(db: TRPCContext["db"]): TRPCContext {
  return {
    clerkAuth: {
      userId: "clerk_1"
    },
    db,
    stylist: {
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "ru",
      timezone: "America/Chicago",
      salonAddress: null,
      onboardingCompletedAt: new Date(),
      createdAt: new Date(),
      updatedAt: new Date()
    }
  } as unknown as TRPCContext;
}

function createDb(client: Record<string, unknown>) {
  return {
    stylist: {
      upsert: vi.fn().mockResolvedValue({
        id: "stylist_1",
        clerkId: "clerk_1",
        language: "ru",
        timezone: "America/Chicago",
        salonAddress: null,
        onboardingCompletedAt: new Date()
      })
    },
    client,
    appointment: {
      findMany: vi.fn().mockResolvedValue([])
    },
    appointmentParticipant: {
      deleteMany: vi.fn()
    }
  } as unknown as TRPCContext["db"];
}

describe("client router", () => {
  it("creates a display-name-only Client scoped to the Stylist", async () => {
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const create = vi.fn().mockResolvedValue({
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna",
      language: "ru",
      phone: "",
      email: "",
      address: "",
      note: "",
      createdAt,
      updatedAt: createdAt
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          create
        })
      )
    );

    await expect(
      caller.clientProfile.save({
        name: "Anna"
      })
    ).resolves.toMatchObject({
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna",
      language: "ru"
    });
    expect(create).toHaveBeenCalledWith({
      data: {
        stylistId: "stylist_1",
        name: "Anna",
        language: "ru",
        phone: "",
        email: "",
        address: "",
        note: ""
      }
    });
  });

  it("searches Clients by name or phone within the Stylist scope", async () => {
    const findMany = vi.fn().mockResolvedValue([]);
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findMany
        })
      )
    );

    await caller.clientProfile.list({
      search: "anna"
    });

    expect(findMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        OR: [
          {
            name: {
              contains: "anna",
              mode: "insensitive"
            }
          },
          {
            phone: {
              contains: "anna"
            }
          }
        ]
      },
      orderBy: {
        updatedAt: "desc"
      }
    });
  });

  it("edits only a Client owned by the current Stylist", async () => {
    const updatedAt = new Date("2026-06-13T12:00:00.000Z");
    const findFirst = vi.fn().mockResolvedValue({
      id: "client_1"
    });
    const update = vi.fn().mockResolvedValue({
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna Petrova",
      language: "en",
      phone: "+15551234567",
      email: "anna@example.com",
      address: "123 Main St",
      note: "Likes warm blonde.",
      createdAt: updatedAt,
      updatedAt
    });
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          findFirst,
          update
        })
      )
    );

    await caller.clientProfile.save({
      id: "client_1",
      name: "Anna Petrova",
      language: "en",
      phone: "+15551234567",
      email: "anna@example.com",
      address: "123 Main St",
      note: "Likes warm blonde."
    });

    expect(findFirst).toHaveBeenCalledWith({
      where: {
        id: "client_1",
        stylistId: "stylist_1"
      }
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "client_1"
      },
      data: {
        name: "Anna Petrova",
        language: "en",
        phone: "+15551234567",
        email: "anna@example.com",
        address: "123 Main St",
        note: "Likes warm blonde."
      }
    });
  });

  it("deletes a Client within the Stylist scope", async () => {
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
      caller.clientProfile.delete({
        id: "client_1"
      })
    ).resolves.toEqual({
      id: "client_1"
    });
    expect(deleteMany).toHaveBeenCalledWith({
      where: {
        id: "client_1",
        stylistId: "stylist_1"
      }
    });
  });

  it("filters Appointment history to Appointments that include the Client", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const appointmentFindMany = vi.fn().mockResolvedValue([
      {
        id: "appointment_1",
        primaryClientId: "client_2",
        startsAt,
        status: "completed",
        primaryClient: {
          id: "client_2",
          name: "Mila",
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
          },
          {
            clientId: "client_2",
            client: {
              id: "client_2",
              name: "Mila",
              address: ""
            }
          }
        ]
      }
    ]);
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            clerkId: "clerk_1",
            language: "ru",
            timezone: "America/Chicago",
            salonAddress: null,
            onboardingCompletedAt: new Date()
          })
        },
        appointment: {
          findMany: appointmentFindMany
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.clientProfile.appointmentHistory({
        id: "client_1"
      })
    ).resolves.toMatchObject([
      {
        id: "appointment_1",
        participantClientIds: ["client_1", "client_2"]
      }
    ]);
    expect(appointmentFindMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        participants: {
          some: {
            clientId: "client_1"
          }
        }
      },
      include: expect.any(Object),
      orderBy: {
        startsAt: "desc"
      }
    });
  });

  it("promotes another participant when deleting the primary Client from a group Appointment", async () => {
    const appointmentUpdate = vi.fn();
    const participantDeleteMany = vi.fn();
    const clientDeleteMany = vi.fn().mockResolvedValue({
      count: 1
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            clerkId: "clerk_1",
            language: "ru",
            timezone: "America/Chicago",
            salonAddress: null,
            onboardingCompletedAt: new Date()
          })
        },
        appointment: {
          findMany: vi.fn().mockResolvedValue([
            {
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
            }
          ]),
          update: appointmentUpdate,
          delete: vi.fn()
        },
        appointmentParticipant: {
          deleteMany: participantDeleteMany
        },
        client: {
          deleteMany: clientDeleteMany
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.clientProfile.delete({
      id: "client_1"
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
    expect(clientDeleteMany).toHaveBeenCalledWith({
      where: {
        id: "client_1",
        stylistId: "stylist_1"
      }
    });
  });

  it("rejects Client creation without a display name", async () => {
    const caller = appRouter.createCaller(
      createContext(
        createDb({
          create: vi.fn()
        })
      )
    );

    await expect(
      caller.clientProfile.save({
        name: ""
      })
    ).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
  });
});
