import { describe, expect, it, vi } from "vitest";
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

function createContext(db: TRPCContext["db"]): TRPCContext {
  return {
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
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
    const groupBy = vi.fn().mockResolvedValue([{ menuItemId: "service_1", _count: { _all: 3 } }]);
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        serviceMenuItem: {
          create,
          findMany
        },
        appointmentService: {
          groupBy
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
        group: null,
        useCount: 3,
        createdAt: createdAt.toISOString(),
        updatedAt: createdAt.toISOString()
      }
    ]);
    // Oldest first, so that editing an item does not move it.
    expect(findMany).toHaveBeenCalledWith({
      where: {
        stylistId: "stylist_1"
      },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }]
    });
    // Services started from an item count only in the Stylist's own Appointments.
    expect(groupBy).toHaveBeenCalledWith(
      expect.objectContaining({
        where: {
          menuItemId: {
            in: ["service_1"]
          },
          appointment: {
            stylistId: "stylist_1"
          }
        }
      })
    );
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

  it("keeps the group of an edited item that the save leaves out, and clears it when it is empty", async () => {
    const updatedAt = new Date("2026-06-13T12:00:00.000Z");
    const update = vi.fn(({ data }: { data: Record<string, unknown> }) => ({
      id: "service_1",
      stylistId: "stylist_1",
      group: "Coloring",
      createdAt: updatedAt,
      updatedAt,
      // As Prisma does: a field that is undefined is left as it is.
      ...Object.fromEntries(Object.entries(data).filter(([, value]) => value !== undefined))
    }));
    const create = vi.fn(({ data }: { data: Record<string, unknown> }) => ({ id: "service_2", createdAt: updatedAt, updatedAt, ...data }));
    const caller = appRouter.createCaller(
      createContext({
        stylist: {
          upsert: vi.fn().mockResolvedValue(stylist)
        },
        serviceMenuItem: {
          findFirst: vi.fn().mockResolvedValue({ id: "service_1", stylistId: "stylist_1" }),
          update,
          create
        }
      } as unknown as TRPCContext["db"])
    );

    await expect(caller.serviceMenu.save({ id: "service_1", name: "Short", defaultPriceCents: 5000 })).resolves.toMatchObject({
      group: "Coloring"
    });
    expect(update.mock.calls[0]?.[0].data).not.toHaveProperty("group", expect.anything());

    await expect(caller.serviceMenu.save({ id: "service_1", name: "Short", defaultPriceCents: 5000, group: "" })).resolves.toMatchObject({
      group: null
    });
    await caller.serviceMenu.save({ id: "service_1", name: "Short", defaultPriceCents: 5000, group: null });
    expect(update.mock.calls[2]?.[0].data).toMatchObject({ group: null });

    // A group is one line, like a name.
    await expect(
      caller.serviceMenu.save({ name: "Coloring short", defaultPriceCents: 5000, group: "  Coloring\n one color " })
    ).resolves.toMatchObject({
      group: "Coloring one color"
    });
  });
});
