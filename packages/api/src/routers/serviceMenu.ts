import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const serviceMenuSaveInput = z.object({
  id: z.string().optional(),
  name: z.string().trim().min(1),
  defaultPriceCents: z.number().int().min(0).default(0)
});

const serviceMenuDeleteInput = z.object({
  id: z.string()
});

function toServiceMenuItemOutput(item: {
  id: string;
  stylistId: string;
  name: string;
  defaultPriceCents: number;
  createdAt: Date;
  updatedAt: Date;
}) {
  return {
    id: item.id,
    stylistId: item.stylistId,
    name: item.name,
    defaultPriceCents: item.defaultPriceCents,
    createdAt: item.createdAt.toISOString(),
    updatedAt: item.updatedAt.toISOString()
  };
}

export const serviceMenuRouter = createTRPCRouter({
  list: stylistProcedure.query(async ({ ctx }) => {
    const items = await ctx.db.serviceMenuItem.findMany({
      where: {
        stylistId: ctx.stylist.id
      },
      orderBy: {
        updatedAt: "desc"
      }
    });

    return items.map(toServiceMenuItemOutput);
  }),
  save: stylistProcedure.input(serviceMenuSaveInput).mutation(async ({ ctx, input }) => {
    const data = {
      name: input.name,
      defaultPriceCents: input.defaultPriceCents
    };

    if (!input.id) {
      const item = await ctx.db.serviceMenuItem.create({
        data: {
          ...data,
          stylistId: ctx.stylist.id
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
