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
        finalTotalCentsOverride: null,
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
        ]
      })
      .mockResolvedValueOnce({
        id: "source_last",
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

    expect(appointmentFindFirst).toHaveBeenNthCalledWith(2, {
      where: {
        stylistId: "stylist_1",
        status: "completed",
        id: {
          not: "target_1"
        }
      },
      include: expect.any(Object),
      orderBy: {
        startsAt: "desc"
      }
    });
    expect(appointmentServiceCreate).toHaveBeenCalledWith({
      data: {
        appointmentId: "target_1",
        clientId: "client_1",
        menuItemId: "menu_1",
        name: "Color",
        priceCents: 12000,
        note: "Copy me",
        colorFormulas: {
          create: [
            {
              formula: "7N",
              placement: "Roots"
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
        ]
      })
      .mockResolvedValueOnce({
        id: "older_source",
        participants: [
          {
            clientId: "client_1"
          }
        ],
        services: []
      });
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
          create: vi.fn()
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.appointment.copyServices({
      targetAppointmentId: "target_1",
      sourceAppointmentId: "older_source"
    });

    expect(appointmentFindFirst).toHaveBeenNthCalledWith(2, {
      where: {
        id: "older_source",
        stylistId: "stylist_1",
        status: "completed"
      },
      include: expect.any(Object)
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
              ]
            })
            .mockResolvedValueOnce({
              id: "source_1",
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
    const serviceCount = vi.fn().mockResolvedValue(0);
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
    expect(photoDeleteMany).toHaveBeenCalledWith({
      where: {
        appointmentId: "appointment_1",
        clientId: "client_1"
      }
    });
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
          url: "https://utfs.io/f/ut_file_1",
          thumbnailUrl: "https://utfs.io/f/ut_file_1-thumb",
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
            ]
          }),
          findUniqueOrThrow: vi.fn().mockResolvedValue(appointmentOutput)
        },
        appointmentPhoto: {
          create: photoCreate,
          findFirst: vi.fn().mockResolvedValue({
            id: "photo_1",
            appointmentId: "appointment_1"
          }),
          update: photoUpdate,
          delete: photoDelete
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.appointment.addPhoto({
        appointmentId: "appointment_1",
        clientId: "client_1",
        category: "after",
        fileKey: "ut_file_1",
        url: "https://utfs.io/f/ut_file_1",
        thumbnailUrl: "https://utfs.io/f/ut_file_1-thumb",
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
          url: "https://utfs.io/f/ut_file_1"
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
        url: "https://utfs.io/f/ut_file_1",
        thumbnailUrl: "https://utfs.io/f/ut_file_1-thumb",
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
