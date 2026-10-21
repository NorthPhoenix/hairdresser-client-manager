import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { withTransaction } from "./testSupport";

function createContext(db: TRPCContext["db"]): TRPCContext {
  return {
    clerkAuth: {
      userId: "clerk_1"
    },
    // No Appointment Photos, unless the database of a test has its own.
    db: withTransaction({
      appointmentPhoto: {
        findMany: vi.fn().mockResolvedValue([])
      },
      ...(db as object)
    }),
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
      },
      include: expect.any(Object)
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
          findFirst: vi.fn().mockResolvedValue({ familyId: null }),
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

  it("returns an existing active Profile Share instead of creating another one", async () => {
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const profileShareCreate = vi.fn();
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
        client: {
          findFirst: vi.fn().mockResolvedValue({
            id: "client_1",
            language: "ru",
            profileShares: [
              {
                id: "share_1",
                token: "existing-token",
                language: "ru",
                includeColorFormulas: false,
                includeAfterPhotos: true,
                includeOtherPhotos: true,
                revokedAt: null,
                createdAt
              }
            ]
          })
        },
        profileShare: {
          create: profileShareCreate
        }
      } as unknown as TRPCContext["db"])
    );

    // The existing Profile Share is answered as it is: creating again changes nothing of it.
    await expect(
      caller.clientProfile.createProfileShare({
        clientId: "client_1",
        includeColorFormulas: true,
        includeOtherPhotos: false
      })
    ).resolves.toEqual({
      id: "share_1",
      token: "existing-token",
      language: "ru",
      includeColorFormulas: false,
      includeAfterPhotos: true,
      includeOtherPhotos: true,
      createdAt: createdAt.toISOString()
    });
    expect(profileShareCreate).not.toHaveBeenCalled();
  });

  it("creates a new unguessable Profile Share token after prior shares were revoked", async () => {
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const profileShareCreate = vi.fn().mockResolvedValue({
      id: "share_2",
      token: "new-token",
      language: "en",
      createdAt
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
        client: {
          findFirst: vi.fn().mockResolvedValue({
            id: "client_1",
            language: "en",
            profileShares: []
          })
        },
        profileShare: {
          create: profileShareCreate
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.clientProfile.createProfileShare({
      clientId: "client_1"
    });

    // Nothing was chosen, so the Profile Share shows what one shows by default.
    expect(profileShareCreate).toHaveBeenCalledWith({
      data: {
        clientId: "client_1",
        token: expect.stringMatching(/^[A-Za-z0-9_-]{40,}$/),
        language: "en",
        includeColorFormulas: true,
        includeAfterPhotos: true,
        includeOtherPhotos: false
      }
    });
  });

  it("revokes only the active Profile Share for an owned Client", async () => {
    const updateMany = vi.fn().mockResolvedValue({
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
        client: {
          findFirst: vi.fn().mockResolvedValue({
            id: "client_1"
          })
        },
        profileShare: {
          updateMany
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.clientProfile.revokeProfileShare({
        clientId: "client_1"
      })
    ).resolves.toEqual({
      clientId: "client_1"
    });
    expect(updateMany).toHaveBeenCalledWith({
      where: {
        clientId: "client_1",
        revokedAt: null
      },
      data: {
        revokedAt: expect.any(Date)
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
        ],
        services: [
          {
            id: "appointment_service_1",
            clientId: "client_1",
            menuItemId: "menu_1",
            name: "Old haircut name",
            priceCents: 8500,
            note: "History snapshot",
            colorFormulas: [
              {
                id: "formula_1",
                formula: "7N",
                placement: "Roots"
              }
            ]
          }
        ],
        photos: [
          {
            id: "photo_1",
            clientId: "client_1",
            category: "after",
            url: "https://utfs.io/f/photo_1",
            thumbnailUrl: "https://utfs.io/f/photo_1-thumb",
            width: 1200,
            height: 900,
            createdAt: startsAt
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
        participantClientIds: ["client_1", "client_2"],
        services: [
          {
            id: "appointment_service_1",
            clientId: "client_1",
            menuItemId: "menu_1",
            name: "Old haircut name",
            priceCents: 8500,
            note: "History snapshot",
            colorFormulas: [
              {
                id: "formula_1",
                formula: "7N",
                placement: "Roots"
              }
            ]
          }
        ],
        photos: [
          {
            id: "photo_1",
            clientId: "client_1",
            category: "after",
            url: "https://utfs.io/f/photo_1"
          }
        ]
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

  it("gives the Appointment history only this Client's Services, Color Formulas and stored photos", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const group = {
      id: "appointment_1",
      primaryClientId: "client_2",
      startsAt,
      status: "completed",
      locationType: "inSalon",
      locationAddress: "100 Salon St",
      locationAddressIsCustom: false,
      primaryClient: {
        id: "client_2",
        name: "Mila",
        address: ""
      },
      participants: [
        {
          clientId: "client_2"
        },
        {
          clientId: "client_1"
        }
      ]
    };
    const services = [
      {
        id: "service_1",
        clientId: "client_1",
        menuItemId: "menu_1",
        name: "Root color",
        priceCents: 9500,
        note: "Left side first",
        colorFormulas: [
          {
            id: "formula_1",
            formula: "7N + 20 vol",
            placement: "Roots"
          },
          {
            id: "formula_2",
            formula: "8G gloss",
            placement: null
          }
        ]
      },
      {
        id: "service_2",
        clientId: "client_2",
        menuItemId: null,
        name: "Blowout",
        priceCents: 3000,
        note: "Mila only",
        colorFormulas: [
          {
            id: "formula_3",
            formula: "9A",
            placement: "Ends"
          }
        ]
      },
      {
        id: "service_3",
        clientId: "client_1",
        menuItemId: null,
        name: "Haircut",
        priceCents: 0,
        note: null,
        colorFormulas: []
      }
    ];
    const photo = (id: string, clientId: string, status: string) => ({
      id,
      clientId,
      category: "after",
      status,
      url: status === "stored" ? `https://utfs.io/f/${id}` : null,
      thumbnailUrl: null,
      width: 1200,
      height: 900,
      createdAt: startsAt
    });
    const photos = [
      photo("photo_1", "client_1", "stored"),
      photo("photo_2", "client_2", "stored"),
      photo("photo_3", "client_1", "pendingUpload"),
      photo("photo_4", "client_1", "failed")
    ];
    type Filter = { where?: { clientId?: string; status?: string } };
    // Applies the filters the query asks for, as the database does.
    const appointmentFindMany = vi.fn(({ include }: { include: { services: Filter; photos: Filter } }) =>
      Promise.resolve([
        {
          ...group,
          services: services.filter(({ clientId }) => clientId === (include.services.where?.clientId ?? clientId)),
          photos: photos.filter(
            ({ clientId, status }) =>
              clientId === (include.photos.where?.clientId ?? clientId) && status === (include.photos.where?.status ?? status)
          )
        }
      ])
    );
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
    ).resolves.toEqual([
      {
        id: "appointment_1",
        primaryClientId: "client_2",
        primaryClientName: "Mila",
        startsAt: startsAt.toISOString(),
        status: "completed",
        locationType: "inSalon",
        locationAddress: "100 Salon St",
        customLocationAddress: "",
        participantClientIds: ["client_2", "client_1"],
        tipCents: null,
        // Not Mila's Blowout, its Color Formula or her photo.
        services: [
          {
            id: "service_1",
            clientId: "client_1",
            menuItemId: "menu_1",
            name: "Root color",
            priceCents: 9500,
            note: "Left side first",
            colorFormulas: [
              {
                id: "formula_1",
                formula: "7N + 20 vol",
                placement: "Roots"
              },
              {
                id: "formula_2",
                formula: "8G gloss",
                placement: ""
              }
            ]
          },
          {
            id: "service_3",
            clientId: "client_1",
            menuItemId: null,
            name: "Haircut",
            priceCents: 0,
            note: "",
            colorFormulas: []
          }
        ],
        // Nor a photo that is not stored: it has nothing to show.
        photos: [
          {
            id: "photo_1",
            clientId: "client_1",
            category: "after",
            url: "https://utfs.io/f/photo_1",
            thumbnailUrl: "",
            width: 1200,
            height: 900,
            createdAt: startsAt.toISOString()
          }
        ]
      }
    ]);

    // Services and Color Formulas in the order they were added, as on the Appointment itself.
    const addedOrder = [{ createdAt: "asc" }, { id: "asc" }];

    expect(appointmentFindMany.mock.calls[0]?.[0].include).toMatchObject({
      services: {
        orderBy: addedOrder,
        include: {
          colorFormulas: {
            orderBy: addedOrder
          }
        }
      }
    });
  });

  it("says in the Appointment history where each Appointment was, so the next one can be booked at the same place", async () => {
    const startsAt = new Date("2026-06-13T15:00:00.000Z");
    const appointment = (id: string, place: object, primaryClient: { id: string; address: string | null }) => ({
      id,
      primaryClientId: primaryClient.id,
      startsAt,
      status: "completed",
      ...place,
      primaryClient: { ...primaryClient, name: "Anna" },
      participants: [{ clientId: "client_1" }],
      services: [],
      photos: []
    });
    const appointmentFindMany = vi.fn().mockResolvedValue([
      appointment("typed", { locationType: "atHome", locationAddress: "12 Elm St", locationAddressIsCustom: true }, { id: "client_1", address: "1 Oak St" }),
      appointment("own", { locationType: "atHome", locationAddress: "1 Oak St", locationAddressIsCustom: false }, { id: "client_1", address: "1 Oak St" }),
      // From before the flag: told apart by the primary Client's address.
      appointment("old", { locationType: "atHome", locationAddress: "9 Pine St", locationAddressIsCustom: null }, { id: "client_1", address: "1 Oak St" }),
      appointment("salon", { locationType: "inSalon", locationAddress: null, locationAddressIsCustom: false }, { id: "client_1", address: "1 Oak St" })
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

    const history = await caller.clientProfile.appointmentHistory({ id: "client_1" });

    expect(history.map(({ id, locationType, locationAddress, customLocationAddress }) => ({ id, locationType, locationAddress, customLocationAddress }))).toEqual([
      { id: "typed", locationType: "atHome", locationAddress: "12 Elm St", customLocationAddress: "12 Elm St" },
      { id: "own", locationType: "atHome", locationAddress: "1 Oak St", customLocationAddress: "" },
      { id: "old", locationType: "atHome", locationAddress: "9 Pine St", customLocationAddress: "9 Pine St" },
      { id: "salon", locationType: "inSalon", locationAddress: "", customLocationAddress: "" }
    ]);
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
          findFirst: vi.fn().mockResolvedValue({ familyId: null }),
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

  it("finds the Client's Appointments by Client or primary Client and deletes the ones left without Clients", async () => {
    const appointmentFindMany = vi.fn().mockResolvedValue([
      {
        // Names the Client as primary without listing them: nobody is left, so it goes too.
        id: "appointment_1",
        primaryClientId: "client_1",
        participants: []
      }
    ]);
    const appointmentDelete = vi.fn();
    const clientDeleteMany = vi.fn().mockResolvedValue({
      count: 1
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            clerkId: "clerk_1"
          })
        },
        appointment: {
          findMany: appointmentFindMany,
          delete: appointmentDelete
        },
        client: {
          findFirst: vi.fn().mockResolvedValue({ familyId: null }),
          deleteMany: clientDeleteMany
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.clientProfile.delete({
      id: "client_1"
    });

    expect(appointmentFindMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        OR: [
          {
            primaryClientId: "client_1"
          },
          {
            participants: {
              some: {
                clientId: "client_1"
              }
            }
          }
        ]
      },
      include: expect.any(Object)
    });
    expect(appointmentDelete).toHaveBeenCalledWith({
      where: {
        id: "appointment_1"
      }
    });
    expect(appointmentDelete.mock.invocationCallOrder[0]).toBeLessThan(clientDeleteMany.mock.invocationCallOrder[0] ?? 0);
  });

  it("moves an at-home Appointment to the promoted Client's address when its primary Client is deleted", async () => {
    const appointmentUpdate = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            clerkId: "clerk_1"
          })
        },
        appointment: {
          findMany: vi.fn().mockResolvedValue([
            {
              id: "appointment_1",
              primaryClientId: "client_1",
              locationType: "atHome",
              locationAddress: "9 Lamar Blvd",
              locationAddressIsCustom: false,
              primaryClient: {
                address: "9 Lamar Blvd"
              },
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
          update: appointmentUpdate
        },
        appointmentParticipant: {
          deleteMany: vi.fn()
        },
        client: {
          findUniqueOrThrow: vi.fn().mockResolvedValue({
            address: "22 Bob St"
          }),
          findFirst: vi.fn().mockResolvedValue({ familyId: null }),
          deleteMany: vi.fn().mockResolvedValue({
            count: 1
          })
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
        primaryClientId: "client_2",
        locationAddress: "22 Bob St",
        locationAddressIsCustom: false
      }
    });
  });

  it("deletes a Client in one transaction that a failing step rolls back", async () => {
    const appointmentDelete = vi.fn();
    const context = createContext({
      stylist: {
        upsert: vi.fn().mockResolvedValue({
          id: "stylist_1",
          clerkId: "clerk_1"
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
              }
            ]
          }
        ]),
        delete: appointmentDelete
      },
      client: {
        findFirst: vi.fn().mockResolvedValue({ familyId: null }),
        // What Prisma throws for a foreign key violation.
        deleteMany: vi.fn().mockRejectedValue(
          Object.assign(new Error("Foreign key constraint violated on the constraint: `Appointment_primaryClientId_fkey`"), {
            name: "PrismaClientKnownRequestError",
            code: "P2003"
          })
        )
      }
    } as unknown as TRPCContext["db"]);
    const db = context.db as unknown as ReturnType<typeof withTransaction>;
    const failure = await appRouter
      .createCaller(context)
      .clientProfile.delete({
        id: "client_1"
      })
      .catch((error: unknown) => error);

    // The Appointment was deleted inside the transaction, and the transaction failed: the
    // database undoes that step. The reply carries no database text.
    expect(db.$executeRaw.mock.invocationCallOrder[0]).toBeLessThan(appointmentDelete.mock.invocationCallOrder[0] ?? 0);
    await expect(db.$transaction.mock.results[0]?.value).rejects.toBeDefined();
    expect(failure).toMatchObject({
      code: "NOT_FOUND",
      message: "A record this change refers to no longer exists."
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
