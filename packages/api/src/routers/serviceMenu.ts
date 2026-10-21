import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { addedOrder } from "../addedOrder";
import { amountCents, nameText, oneLineText, refuseChangedRepeat, requestKey, textLimits } from "../input";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

const serviceMenuSaveInput = z.object({
  id: z.string().optional(),
  name: nameText,
  defaultPriceCents: amountCents.default(0),
  // One line; empty or null: no group. Left out: an edit keeps the group the item has, so an app
  // from before groups does not clear it.
  group: oneLineText(textLimits.name).nullable().optional(),
  // Without an id: see requestKey.
  requestKey: requestKey.optional()
});

const serviceMenuDeleteInput = z.object({
  id: z.string()
});

function toServiceMenuItemOutput(item: {
  id: string;
  stylistId: string;
  name: string;
  defaultPriceCents: number;
  group?: string | null;
  createdAt: Date;
  updatedAt: Date;
}) {
  return {
    id: item.id,
    stylistId: item.stylistId,
    name: item.name,
    defaultPriceCents: item.defaultPriceCents,
    group: item.group ?? null,
    createdAt: item.createdAt.toISOString(),
    updatedAt: item.updatedAt.toISOString()
  };
}

/** A group as it is stored: null for none. */
function toStoredGroup(group: string | null | undefined) {
  return group === undefined ? undefined : group || null;
}

export const serviceMenuRouter = createTRPCRouter({
  // In the order the items were created, oldest first: editing an item does not move it.
  list: stylistProcedure.query(async ({ ctx }) => {
    const items = await ctx.db.serviceMenuItem.findMany({
      where: {
        stylistId: ctx.stylist.id
      },
      orderBy: addedOrder
    });
    const uses = items.length
      ? await ctx.db.appointmentService.groupBy({
          by: ["menuItemId"],
          where: {
            menuItemId: {
              in: items.map((item) => item.id)
            },
            appointment: {
              stylistId: ctx.stylist.id
            }
          },
          _count: {
            _all: true
          }
        })
      : [];
    const useCounts = new Map(uses.map((use) => [use.menuItemId, use._count._all]));

    return items.map((item) => ({
      ...toServiceMenuItemOutput(item),
      // How many Services were started from this item, in all Appointments of the Stylist.
      useCount: useCounts.get(item.id) ?? 0
    }));
  }),
  save: stylistTransactionProcedure.input(serviceMenuSaveInput).mutation(async ({ ctx, input }) => {
    const data = {
      name: input.name,
      defaultPriceCents: input.defaultPriceCents,
      group: toStoredGroup(input.group)
    };

    if (!input.id) {
      const repeatedItem = input.requestKey
        ? await ctx.db.serviceMenuItem.findFirst({
            where: {
              stylistId: ctx.stylist.id,
              requestKey: input.requestKey
            }
          })
        : null;

      if (repeatedItem) {
        refuseChangedRepeat(repeatedItem, data);

        return toServiceMenuItemOutput(repeatedItem);
      }

      const item = await ctx.db.serviceMenuItem.create({
        data: {
          ...data,
          stylistId: ctx.stylist.id,
          requestKey: input.requestKey
        }
      });

      return toServiceMenuItemOutput(item);
    }

    const existingItem = await ctx.db.serviceMenuItem.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    if (!existingItem) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Service Menu Item not found."
      });
    }

    const item = await ctx.db.serviceMenuItem.update({
      where: {
        id: existingItem.id
      },
      data
    });

    return toServiceMenuItemOutput(item);
  }),
  delete: stylistProcedure.input(serviceMenuDeleteInput).mutation(async ({ ctx, input }) => {
    await ctx.db.serviceMenuItem.deleteMany({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    return {
      id: input.id
    };
  })
});
