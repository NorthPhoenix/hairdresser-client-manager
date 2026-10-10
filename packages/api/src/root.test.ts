import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { withTransaction } from "./testSupport";

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
      onboardingCompletedAt: null
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
      onboardingCompletedAt: null
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
      onboardingCompletedAt
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
      onboardingCompletedAt: new Date("2026-06-13T12:00:00.000Z")
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
            onboardingCompletedAt: null
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
          onboardingCompletedAt: null
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
});
