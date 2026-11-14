import { recordError } from "@hcm/telemetry";
import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { addedOrder } from "../addedOrder";
import { amountCents, annotateSafely, nameText, oneLineText, refuseChangedRepeat, requestKey, textLimits } from "../input";
import { unmarkOtherTravelFees } from "../travelFee";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

const serviceMenuSaveInput = z.object({
  id: z.string().optional(),
  name: nameText,
  defaultPriceCents: amountCents.default(0),
  // One line; empty or null: no group. Left out: an edit keeps the group the item has, so an app
  // from before groups does not clear it.
  group: oneLineText(textLimits.name).nullable().optional(),
  // Whether this item is the Travel Fee; marking one unmarks any other. Left out: a new item is
  // not, and an edit keeps what the item is, so an app from before the Travel Fee does not clear it.
  travelFee: z.boolean().optional(),
  // Without an id: see requestKey.
  requestKey: requestKey.optional()
});

/** How many Services the names of formulaNames are read from. A Stylist has a handful of formulas, not thousands. */
const formulaNamesLimit = 1000;

const serviceMenuDeleteInput = z.object({
  id: z.string()
});

function toServiceMenuItemOutput(item: {
  id: string;
  stylistId: string;
  name: string;
  defaultPriceCents: number;
  group?: string | null;
  travelFee?: boolean;
  createdAt: Date;
  updatedAt: Date;
}) {
  return {
    id: item.id,
    stylistId: item.stylistId,
    name: item.name,
    defaultPriceCents: item.defaultPriceCents,
    group: item.group ?? null,
    travelFee: item.travelFee ?? false,
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
    // The same count, of the Services that have a Color Formula: the app offers the full formula
    // button on a Service started from an item that has had one.
    const formulaUses = items.length
      ? await ctx.db.appointmentService.groupBy({
          by: ["menuItemId"],
          where: {
            menuItemId: {
              in: items.map((item) => item.id)
            },
            appointment: {
              stylistId: ctx.stylist.id
            },
            colorFormulas: {
              some: {}
            }
          },
          _count: {
            _all: true
          }
        })
      : [];
    const formulaCounts = new Map(formulaUses.map((use) => [use.menuItemId, use._count._all]));

    annotateSafely(() => ({
      "service_menu.item_count": items.length,
      "service_menu.group_count": new Set(items.flatMap((item) => (item.group ? [item.group] : []))).size,
      "service_menu.grouped_item_count": items.filter((item) => item.group).length,
      "service_menu.travel_fee_item_count": items.filter((item) => item.travelFee).length,
      "service_menu.used_item_count": useCounts.size,
      "service_menu.formula_item_count": formulaCounts.size,
      "service_menu.is_empty": items.length === 0
    }));

    return items.map((item) => ({
      ...toServiceMenuItemOutput(item),
      // How many Services were started from this item, in all Appointments of the Stylist.
      useCount: useCounts.get(item.id) ?? 0,
      // How many of those Services have at least one Color Formula.
      formulaCount: formulaCounts.get(item.id) ?? 0
    }));
  }),
  // The names of the Stylist's Services that have a Color Formula, each once, as they were typed:
  // the app compares them with the name of a Service that has no menu item (see ColorFormulaOffer).
  // The most recently added first, at most formulaNamesLimit.
  formulaNames: stylistProcedure.query(async ({ ctx }) => {
    const services = await ctx.db.appointmentService.findMany({
      where: {
        appointment: {
          stylistId: ctx.stylist.id
        },
        colorFormulas: {
          some: {}
        }
      },
      select: {
        name: true
      },
      orderBy: {
        createdAt: "desc"
      },
      take: formulaNamesLimit
    });

    const names = [...new Set(services.map((service) => service.name))];

    annotateSafely(() => ({
      "service_menu.formula_service_count": services.length,
      "service_menu.formula_name_count": names.length,
      // Whether the limit cut the list: older Services' names are missing.
      "service_menu.formula_names_truncated": services.length >= formulaNamesLimit
    }));

    return names;
  }),
  save: stylistTransactionProcedure.input(serviceMenuSaveInput).mutation(async ({ ctx, input }) => {
    annotateSafely(() => ({
      "service_menu.is_new": !input.id,
      "service_menu.has_request_key": Boolean(input.requestKey),
      "service_menu.name_length": input.name.length,
      "service_menu.price_minor": input.defaultPriceCents,
      // "unchanged": the app left the group out; "cleared": empty or null; "set": a group.
      "service_menu.group_input": input.group === undefined ? "unchanged" : input.group ? "set" : "cleared",
      "service_menu.travel_fee_input": input.travelFee === undefined ? "unchanged" : input.travelFee ? "marked" : "unmarked"
    }));

    const data = {
      name: input.name,
      defaultPriceCents: input.defaultPriceCents,
      group: toStoredGroup(input.group),
      travelFee: input.travelFee
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
        annotateSafely(() => ({ "service_menu.id": repeatedItem.id }));
        refuseChangedRepeat(repeatedItem, data, "service_menu");
        annotateSafely(() => ({ "service_menu.save_result": "repeat_returned_first" }));

        return toServiceMenuItemOutput(repeatedItem);
      }

      const item = await ctx.db.serviceMenuItem.create({
        data: {
          ...data,
          stylistId: ctx.stylist.id,
          requestKey: input.requestKey
        }
      });

      annotateSafely(() => ({
        "service_menu.id": item.id,
        "service_menu.save_result": "created",
        "service_menu.is_travel_fee": Boolean(item.travelFee)
      }));

      if (item.travelFee) {
        await unmarkOtherTravelFees(ctx.db, ctx.stylist.id, item.id);
      }

      return toServiceMenuItemOutput(item);
    }

    annotateSafely(() => ({ "service_menu.id": input.id }));

    const existingItem = await ctx.db.serviceMenuItem.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    if (!existingItem) {
      const refusal = new TRPCError({
        code: "NOT_FOUND",
        message: "Service Menu Item not found."
      });

      recordError(refusal, { expected: true, reason: "service_menu_item_not_found" });
      throw refusal;
    }

    const item = await ctx.db.serviceMenuItem.update({
      where: {
        id: existingItem.id
      },
      data
    });

    annotateSafely(() => {
      const changed = [
        existingItem.name !== item.name ? "name" : null,
        existingItem.defaultPriceCents !== item.defaultPriceCents ? "price" : null,
        (existingItem.group ?? null) !== (item.group ?? null) ? "group" : null,
        Boolean(existingItem.travelFee) !== Boolean(item.travelFee) ? "travel_fee" : null
      ].filter((field) => field !== null);

      return {
        "service_menu.save_result": "updated",
        "service_menu.changed_fields": changed,
        "service_menu.is_noop_save": changed.length === 0,
        "service_menu.is_travel_fee": Boolean(item.travelFee),
        "service_menu.price_before_minor": existingItem.defaultPriceCents
      };
    });

    if (item.travelFee) {
      await unmarkOtherTravelFees(ctx.db, ctx.stylist.id, item.id);
    }

    return toServiceMenuItemOutput(item);
  }),
  delete: stylistProcedure.input(serviceMenuDeleteInput).mutation(async ({ ctx, input }) => {
    const deleted = await ctx.db.serviceMenuItem.deleteMany({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    annotateSafely(() => ({
      "service_menu.id": input.id,
      // "not_found": already gone, or not this Stylist's; the reply is the same.
      "service_menu.delete_result": deleted?.count === 0 ? "not_found" : "deleted"
    }));

    return {
      id: input.id
    };
  })
});
