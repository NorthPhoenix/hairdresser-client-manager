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

describe("service menu router", () => {
  it("creates and lists Service Menu Items with default prices", async () => {
    const createdAt = new Date("2026-06-13T12:00:00.000Z");
    const create = vi.fn().mockResolvedValue({
      id: "service_1",
      stylistId: "stylist_1",
      name: "Haircut",
      defaultPriceCents: 8500,
      createdAt,
      updatedAt: createdAt
    });
    const findMany = vi.fn().mockResolvedValue([
      {
        id: "service_1",
        stylistId: "stylist_1",
        name: "Haircut",
        defaultPriceCents: 8500,
        createdAt,
        updatedAt: createdAt
      }
    ]);
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        serviceMenuItem: {
          create,
          findMany
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(
      caller.serviceMenu.save({
        name: "Haircut",
        defaultPriceCents: 8500
      })
    ).resolves.toMatchObject({
      id: "service_1",
      name: "Haircut",
      defaultPriceCents: 8500
    });
    await expect(caller.serviceMenu.list()).resolves.toEqual([
      {
        id: "service_1",
        stylistId: "stylist_1",
        name: "Haircut",
        defaultPriceCents: 8500,
        createdAt: createdAt.toISOString(),
        updatedAt: createdAt.toISOString()
      }
    ]);
    expect(create).toHaveBeenCalledWith({
      data: {
        stylistId: "stylist_1",
        name: "Haircut",
        defaultPriceCents: 8500
      }
    });
  });

  it("edits and deletes only the stylist-owned Service Menu Item", async () => {
    const updatedAt = new Date("2026-06-13T12:00:00.000Z");
    const findFirst = vi.fn().mockResolvedValue({
      id: "service_1",
      stylistId: "stylist_1"
    });
    const update = vi.fn().mockResolvedValue({
      id: "service_1",
      stylistId: "stylist_1",
      name: "Blowout",
      defaultPriceCents: 6500,
      createdAt: updatedAt,
      updatedAt
    });
    const deleteMany = vi.fn().mockResolvedValue({
      count: 1
    });
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        serviceMenuItem: {
          findFirst,
          update,
          deleteMany
        }
      } as unknown as TRPCContext["db"])
    );

    await caller.serviceMenu.save({
      id: "service_1",
      name: "Blowout",
      defaultPriceCents: 6500
    });
    await caller.serviceMenu.delete({
      id: "service_1"
    });

    expect(update).toHaveBeenCalledWith({
      where: {
        id: "service_1"
      },
      data: {
        name: "Blowout",
        defaultPriceCents: 6500
      }
    });
    expect(deleteMany).toHaveBeenCalledWith({
      where: {
        id: "service_1",
        stylistId: "stylist_1"
      }
    });
  });
});
