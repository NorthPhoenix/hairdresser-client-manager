import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";

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
    const ctx = createContext({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: {
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
        }
      } as unknown as TRPCContext["db"]
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
  });
});
