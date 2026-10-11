import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { firstIssueOf, withTransaction } from "./testSupport";

function createContext(overrides: Partial<TRPCContext>): TRPCContext {
  return {
    clerkAuth: {
      userId: null
    },
    db: {
      stylist: {
        upsert: vi.fn(),
        update: vi.fn()
      }
    },
    ...overrides
  } as unknown as TRPCContext;
}

const updatedAt = new Date("2026-06-13T12:00:00.000Z");

describe("stylist bootstrap", () => {
  it("rejects unauthenticated callers", async () => {
    const ctx = createContext({
      clerkAuth: {
        userId: null
      }
    });
    const caller = appRouter.createCaller(ctx);

    await expect(caller.stylist.bootstrap()).rejects.toMatchObject({
      code: "UNAUTHORIZED"
    });
  });

  it("lazily creates the minimum Stylist without completing onboarding", async () => {
    const upsert = vi.fn().mockResolvedValue({
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "ru",
      timezone: "UTC",
      salonAddress: null,
      onboardingCompletedAt: null,
      updatedAt
    });
    const ctx = createContext({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: {
        stylist: {
          upsert,
          update: vi.fn()
        }
      } as unknown as TRPCContext["db"]
    });
    const caller = appRouter.createCaller(ctx);

    await expect(caller.stylist.bootstrap()).resolves.toMatchObject({
      id: "stylist_1",
      language: "ru",
      timezone: "UTC",
      salonAddress: "",
      onboardingCompletedAt: null,
      onboardingComplete: false
    });
    expect(upsert).toHaveBeenCalledWith({
      where: {
        clerkId: "clerk_1"
      },
      update: {},
      create: {
        clerkId: "clerk_1",
        language: "ru",
        timezone: "UTC"
      }
    });
  });

  it("uses device defaults when lazily creating a Stylist", async () => {
    const upsert = vi.fn().mockResolvedValue({
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "en",
      timezone: "America/Chicago",
      salonAddress: null,
      onboardingCompletedAt: null,
      updatedAt
    });
    const ctx = createContext({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: {
        stylist: {
          upsert,
          update: vi.fn()
        }
      } as unknown as TRPCContext["db"]
    });
    const caller = appRouter.createCaller(ctx);

    await caller.stylist.bootstrap({
      deviceLanguage: "en",
      deviceTimezone: "America/Chicago"
    });

    expect(upsert).toHaveBeenCalledWith({
      where: {
        clerkId: "clerk_1"
      },
      update: {},
      create: {
        clerkId: "clerk_1",
        language: "en",
        timezone: "America/Chicago"
      }
    });
  });

  it("loads an existing Stylist and returns onboarding status", async () => {
    const onboardingCompletedAt = new Date("2026-06-13T12:00:00.000Z");
    const upsert = vi.fn().mockResolvedValue({
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "en",
      timezone: "America/Chicago",
      salonAddress: "123 Main St",
      onboardingCompletedAt,
      updatedAt
    });
    const ctx = createContext({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: {
        stylist: {
          upsert,
          update: vi.fn()
        }
      } as unknown as TRPCContext["db"]
    });
    const caller = appRouter.createCaller(ctx);

    await expect(caller.stylist.bootstrap()).resolves.toMatchObject({
      id: "stylist_1",
      language: "en",
      timezone: "America/Chicago",
      salonAddress: "123 Main St",
      onboardingCompletedAt: "2026-06-13T12:00:00.000Z",
      onboardingComplete: true
    });
  });

  it("saves settings and completes onboarding only through the settings mutation", async () => {
    const update = vi.fn().mockResolvedValue({
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "en",
      timezone: "America/Chicago",
      salonAddress: "123 Main St",
      onboardingCompletedAt: new Date("2026-06-13T12:00:00.000Z"),
      updatedAt
    });
    const appointmentUpdateMany = vi.fn();
    const ctx = createContext({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            clerkId: "clerk_1",
            language: "ru",
            timezone: "UTC",
            salonAddress: null,
            onboardingCompletedAt: null,
            updatedAt
          }),
          update
        },
        appointment: {
          updateMany: appointmentUpdateMany
        }
      })
    });
    const caller = appRouter.createCaller(ctx);

    await expect(
      caller.stylist.saveSettings({
        language: "en",
        timezone: "America/Chicago",
        salonAddress: "123 Main St"
      })
    ).resolves.toMatchObject({
      language: "en",
      timezone: "America/Chicago",
      salonAddress: "123 Main St",
      onboardingComplete: true
    });
    expect(update).toHaveBeenCalledWith({
      where: {
        id: "stylist_1"
      },
      data: {
        language: "en",
        timezone: "America/Chicago",
        salonAddress: "123 Main St",
        onboardingCompletedAt: expect.any(Date)
      }
    });
    // Scheduled in-salon Appointments follow the salon address; ones with an outcome keep theirs.
    expect(appointmentUpdateMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1",
        status: "scheduled",
        locationType: "inSalon"
      },
      data: {
        locationAddress: "123 Main St"
      }
    });
  });

  it("takes the Stylist lock first and fails the transaction when the mutation fails", async () => {
    const update = vi.fn().mockRejectedValue(new Error("connection lost"));
    const db = withTransaction({
      stylist: {
        upsert: vi.fn().mockResolvedValue({
          id: "stylist_1",
          clerkId: "clerk_1",
          language: "ru",
          timezone: "UTC",
          salonAddress: null,
          onboardingCompletedAt: null,
          updatedAt
        }),
        update
      },
      appointment: {
        updateMany: vi.fn()
      }
    });
    const caller = appRouter.createCaller(
      createContext({
        clerkAuth: {
          userId: "clerk_1"
        },
        db
      })
    );

    await expect(
      caller.stylist.saveSettings({
        language: "en",
        timezone: "America/Chicago"
      })
    ).rejects.toMatchObject({
      code: "INTERNAL_SERVER_ERROR"
    });

    // One lock per Stylist, taken before anything is read or written.
    expect(db.$executeRaw.mock.calls[0]?.slice(1)).toEqual(["stylist_1"]);
    expect(String(db.$executeRaw.mock.calls[0]?.[0])).toContain("pg_advisory_xact_lock");
    expect(db.$executeRaw.mock.invocationCallOrder[0]).toBeLessThan(update.mock.invocationCallOrder[0] ?? 0);
    // A transaction whose callback rejects is rolled back by the database.
    await expect(db.$transaction.mock.results[0]?.value).rejects.toBeDefined();
  });

  it.each([
    ["P2002", "CONFLICT", "This record already exists."],
    ["P2003", "NOT_FOUND", "A record this change refers to no longer exists."],
    ["P2025", "NOT_FOUND", "This record no longer exists."]
  ])("answers database failure %s as %s without database text", async (prismaCode, code, message) => {
    const caller = appRouter.createCaller(
      createContext({
        clerkAuth: {
          userId: "clerk_1"
        },
        db: {
          stylist: {
            upsert: vi.fn().mockRejectedValue(
              Object.assign(new Error("Invalid `ctx.db.stylist.upsert()` invocation in /srv/app/trpc.ts"), {
                name: "PrismaClientKnownRequestError",
                code: prismaCode
              })
            )
          }
        } as unknown as TRPCContext["db"]
      })
    );
    const failure = await caller.stylist.bootstrap().catch((error: unknown) => error);

    expect(failure).toMatchObject({
      code,
      message
    });
  });

  // What the device suggests is only used to create the Stylist, by whichever call comes first.
  it.each([
    ["a language the app does not have", { deviceLanguage: "xx" }, "deviceLanguage"],
    ["a timezone that is not text", { deviceTimezone: 12345 }, "deviceTimezone"],
    ["a timezone that does not exist", { deviceTimezone: "Mars/Phobos" }, "deviceTimezone"]
  ])("creates no Stylist from %s", async (_label, input, field) => {
    const upsert = vi.fn();
    const caller = appRouter.createCaller(
      createContext({
        clerkAuth: {
          userId: "clerk_new"
        },
        db: {
          stylist: {
            upsert,
            findUnique: vi.fn().mockResolvedValue(null)
          },
          client: {
            findMany: vi.fn().mockResolvedValue([])
          }
        } as unknown as TRPCContext["db"]
      })
    );

    // Any procedure can be the first call, also one whose own input has no such field.
    await expect(firstIssueOf(caller.clientProfile.list(input as never))).resolves.toMatchObject({
      code: "BAD_REQUEST",
      field
    });
    expect(upsert).not.toHaveBeenCalled();
  });

  it("says that the device timezone is not one when it refuses to create the Stylist", async () => {
    const caller = appRouter.createCaller(
      createContext({
        clerkAuth: {
          userId: "clerk_new"
        },
        db: {
          stylist: {
            upsert: vi.fn(),
            findUnique: vi.fn().mockResolvedValue(null)
          }
        } as unknown as TRPCContext["db"]
      })
    );

    await expect(firstIssueOf(caller.stylist.bootstrap({ deviceTimezone: "Mars/Phobos" }))).resolves.toEqual({
      code: "BAD_REQUEST",
      message: "Timezone is not valid.",
      field: "deviceTimezone"
    });
  });

  it("does not hold up an existing Stylist whose device reports a timezone the server does not know", async () => {
    const upsert = vi.fn();
    const findUnique = vi.fn().mockResolvedValue({
      id: "stylist_1",
      clerkId: "clerk_1",
      language: "en",
      timezone: "America/Chicago",
      salonAddress: null,
      onboardingCompletedAt: null,
      updatedAt
    });
    const caller = appRouter.createCaller(
      createContext({
        clerkAuth: {
          userId: "clerk_1"
        },
        db: {
          stylist: {
            upsert,
            findUnique
          }
        } as unknown as TRPCContext["db"]
      })
    );

    await expect(caller.stylist.bootstrap({ deviceTimezone: "Mars/Phobos" })).resolves.toMatchObject({
      id: "stylist_1",
      timezone: "America/Chicago"
    });
    expect(findUnique).toHaveBeenCalledWith({
      where: {
        clerkId: "clerk_1"
      }
    });
    expect(upsert).not.toHaveBeenCalled();
  });
});

// What a caller of the HTTP API gets, as the web app serves it.
async function post(path: string, input: unknown, db: unknown) {
  const response = await fetchRequestHandler({
    endpoint: "/api/trpc",
    req: new Request(`http://localhost/api/trpc/${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json"
      },
      body: JSON.stringify(input)
    }),
    router: appRouter,
    createContext: () =>
      createContext({
        clerkAuth: {
          userId: "clerk_1"
        },
        db: db as TRPCContext["db"]
      })
  });

  return {
    status: response.status,
    text: await response.text()
  };
}

describe("error replies", () => {
  const existingStylist = {
    id: "stylist_1",
    clerkId: "clerk_1",
    language: "en",
    timezone: "America/Chicago",
    salonAddress: null,
    onboardingCompletedAt: null,
    updatedAt
  };

  it("answers an unexpected failure without the text of what failed and without a stack trace", async () => {
    const databaseText =
      "Invalid `ctx.db.serviceMenuItem.create()` invocation in /home/app/.next/server/chunks/root.js:42:7 Unable to fit integer value";
    const reply = await post(
      "serviceMenu.save",
      {
        name: "Haircut",
        defaultPriceCents: 8500
      },
      withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue(existingStylist)
        },
        serviceMenuItem: {
          create: vi.fn().mockRejectedValue(Object.assign(new Error(databaseText), { name: "PrismaClientValidationError" }))
        }
      })
    );

    expect(reply.status).toBe(500);
    expect(JSON.parse(reply.text)).toEqual({
      error: {
        message: "Internal server error.",
        code: -32603,
        data: {
          code: "INTERNAL_SERVER_ERROR",
          httpStatus: 500,
          path: "serviceMenu.save"
        }
      }
    });
    expect(reply.text).not.toContain("ctx.db");
    expect(reply.text).not.toContain("/home/app");
  });

  it("keeps the code and message of a refusal and adds no stack trace", async () => {
    const reply = await post(
      "serviceMenu.save",
      {
        id: "service_other",
        name: "Haircut"
      },
      withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue(existingStylist)
        },
        serviceMenuItem: {
          findFirst: vi.fn().mockResolvedValue(null)
        }
      })
    );

    expect(reply.status).toBe(404);
    expect(JSON.parse(reply.text)).toEqual({
      error: {
        message: "Service Menu Item not found.",
        code: -32004,
        data: {
          code: "NOT_FOUND",
          httpStatus: 404,
          path: "serviceMenu.save"
        }
      }
    });
  });

  it("answers a number the database cannot hold as invalid input, not as a failure", async () => {
    const create = vi.fn();
    const reply = await post(
      "serviceMenu.save",
      {
        name: "x",
        defaultPriceCents: 2_147_483_648
      },
      withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue(existingStylist)
        },
        serviceMenuItem: {
          create
        }
      })
    );
    const error = (JSON.parse(reply.text) as { error: { message: string; data: Record<string, unknown> } }).error;

    expect(reply.status).toBe(400);
    expect(error.data).toEqual({
      code: "BAD_REQUEST",
      httpStatus: 400,
      path: "serviceMenu.save"
    });
    // The list of issues the app already reads: the first one names the rule and the field.
    expect(JSON.parse(error.message)).toMatchObject([
      {
        path: ["defaultPriceCents"],
        message: "Amount is too large."
      }
    ]);
    expect(create).not.toHaveBeenCalled();
  });
});
