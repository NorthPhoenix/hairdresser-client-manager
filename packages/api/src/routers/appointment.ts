import type { Prisma } from "@hcm/db";
import { annotate, recordError, traced } from "@hcm/telemetry";
import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { inAddedOrder } from "../addedOrder";
import { getClientAddress, getPrimaryClientChange, getSalonAddress, hasCustomAddress, saveAddressToClient } from "../appointmentLocation";
import type { Db } from "../context";
import {
  amountCents,
  maxAmountCents,
  nameText,
  oneLineText,
  refuseChangedRepeat,
  requestKey,
  text,
  textLimits
} from "../input";
import { isStoredFileUrl, markFilesUnused } from "../photoStorage";
import { addTravelFee, noTravelFeeChange, removeTravelFee, type TravelFeeChange } from "../travelFee";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

// What the procedures below tell the request's wide event (docs/observability.md): the ids of the
// records a request is about, the size of what it read and wrote, and the branch it took, as
// counts, amounts and enum values. Never a Client's name, an address, a note or a formula: only
// their lengths, or whether there is one.

/**
 * A refusal on purpose, recorded on the request's wide event with the domain's reason for it, so
 * a search can count each reason. The caller throws it; the reply is the same as before.
 */
function refusal(code: TRPCError["code"], message: string, reason: string) {
  const error = new TRPCError({ code, message });

  recordError(error, { expected: true, reason });
  return error;
}

/** An enum value of the database as events write enum values: "noShow" is "no_show". */
function snakeCase(value: string | undefined) {
  return value?.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

const dayMs = 24 * 60 * 60 * 1000;

/** Days from one time to another, to a tenth: how far ahead an Appointment is, how long a range. */
function daysBetween(from: Date, to: Date) {
  return Math.round(((to.getTime() - from.getTime()) / dayMs) * 10) / 10;
}

/**
 * The hour, 0 to 23, an Appointment starts at in the Stylist's timezone. Starts at night are most
 * likely AM and PM mixed up (see Appointment Creation), so a search can count them.
 */
function localStartHour(startsAt: Date, timeZone: string) {
  try {
    const hour = Number(new Intl.DateTimeFormat("en-US", { hour: "numeric", hourCycle: "h23", timeZone }).format(startsAt));

    return Number.isFinite(hour) ? hour : undefined;
  } catch {
    return undefined;
  }
}

/** When an Appointment that is created or moved starts, as the event counts it. */
function annotateStart(startsAt: Date, timeZone: string) {
  annotate(() => ({
    "appointment.start_local_hour": localStartHour(startsAt, timeZone),
    // Negative for a visit written down after it happened.
    "appointment.starts_in_days": daysBetween(new Date(), startsAt)
  }));
}

/**
 * What the Travel Fee did in a save. "not_applicable" when the save could not change it (created
 * in the salon, an edit that keeps the place or of an Appointment with an outcome); "none" when it
 * was looked at and nothing changed (no Travel Fee item, one already there, or the total too large).
 */
function annotateTravelFee(change: TravelFeeChange | null) {
  annotate(() => {
    const services = change ? [...change.added, ...change.removed] : [];

    return {
      "appointment.travel_fee_change":
        change === null ? "not_applicable" : change.added.length > 0 ? "added" : change.removed.length > 0 ? "removed" : "none",
      "appointment.travel_fee_service_count": services.length,
      "appointment.travel_fee_minor": services.reduce((total, service) => total + service.priceCents, 0)
    };
  });
}

const dateRangeInput = z.object({
  start: z.string().datetime(),
  end: z.string().datetime()
});

const appointmentCreateInput = z.object({
  primaryClientId: z.string().optional(),
  additionalClientIds: z.array(z.string()).default([]),
  inlineClientName: nameText.optional(),
  startsAt: z.string().datetime(),
  endsAt: z.string().datetime().optional(),
  locationType: z.enum(["inSalon", "atHome"]).default("inSalon"),
  customLocationAddress: text(textLimits.address).optional(),
  // At home with a customLocationAddress: make it the primary Client's address too, when that
  // Client has none. The Appointment is then at the primary Client's address. Apps before it
  // send nothing, and the Client keeps no address.
  saveAddressToClient: z.boolean().optional(),
  requestKey: requestKey.optional()
}).refine((input) => input.primaryClientId || input.inlineClientName, {
  message: "Choose a primary Client or create one inline.",
  path: ["primaryClientId"]
});

const appointmentUpdateInput = z.object({
  id: z.string(),
  primaryClientId: z.string().optional(),
  status: z.enum(["scheduled", "completed", "canceled", "noShow"]).optional(),
  note: text(textLimits.note).optional(),
  finalTotalCentsOverride: amountCents.nullable().optional(),
  // The tip for the whole visit, apart from the price of its Services; null clears it. Like every
  // other field here, a save that does not send it leaves it as it is.
  tipCents: amountCents.nullable().optional(),
  startsAt: z.string().datetime().optional(),
  // null clears the end time.
  endsAt: z.string().datetime().nullable().optional(),
  locationType: z.enum(["inSalon", "atHome"]).optional(),
  // At home only: an empty string means the primary Client's address.
  customLocationAddress: text(textLimits.address).optional(),
  // With a customLocationAddress: as in appointmentCreateInput.
  saveAddressToClient: z.boolean().optional()
});

const appointmentParticipantInput = z.object({
  appointmentId: z.string(),
  clientId: z.string()
});

const appointmentServiceInput = z.object({
  appointmentId: z.string(),
  clientId: z.string(),
  menuItemId: z.string().optional(),
  name: nameText.optional(),
  priceCents: amountCents.optional(),
  note: text(textLimits.note).optional(),
  requestKey: requestKey.optional()
});

const menuServicesLimit = 100;

const appointmentMenuServicesInput = z
  .object({
    appointmentId: z.string(),
    // For one Client: clientId and menuItemIds, as the app has always sent them.
    clientId: z.string().optional(),
    // In the order the Stylist picked them; one item can be in it more than once.
    menuItemIds: z.array(z.string()).min(1).max(menuServicesLimit).optional(),
    // For several Clients at once, instead of clientId and menuItemIds: each Client once, in the
    // order their Services are added, each with their items in the order they were picked.
    clients: z
      .array(
        z.object({
          clientId: z.string(),
          menuItemIds: z.array(z.string()).min(1).max(menuServicesLimit)
        })
      )
      .min(1)
      .max(menuServicesLimit)
      .optional(),
    requestKey: requestKey.optional()
  })
  .superRefine((input, context) => {
    const single = input.clientId !== undefined || input.menuItemIds !== undefined;

    if (input.clients === undefined) {
      if (input.clientId === undefined || input.menuItemIds === undefined) {
        context.addIssue({ code: "custom", message: "Pick a Client and Service Menu Items." });
      }
      return;
    }

    if (single) {
      context.addIssue({ code: "custom", message: "Send either one Client or several, not both." });
    }

    if (new Set(input.clients.map((client) => client.clientId)).size !== input.clients.length) {
      context.addIssue({ code: "custom", message: "Each Client can be listed once." });
    }

    if (input.clients.reduce((count, client) => count + client.menuItemIds.length, 0) > menuServicesLimit) {
      context.addIssue({ code: "custom", message: "Too many Services at once." });
    }
  });

/** The Services an addMenuServices input asks for, as pairs of whom and which item, in the order they are added. */
function menuServicePicks(input: z.infer<typeof appointmentMenuServicesInput>) {
  const clients = input.clients ?? [{ clientId: input.clientId ?? "", menuItemIds: input.menuItemIds ?? [] }];

  return clients.flatMap((client) => client.menuItemIds.map((menuItemId) => ({ clientId: client.clientId, menuItemId })));
}

const appointmentServiceUpdateInput = z.object({
  id: z.string(),
  name: nameText,
  priceCents: amountCents,
  note: text(textLimits.note).optional()
});

const appointmentServiceDeleteInput = z.object({
  id: z.string()
});

const colorFormulaInput = z.object({
  appointmentServiceId: z.string(),
  formula: text(textLimits.formula).min(1),
  placement: oneLineText(textLimits.placement).optional(),
  requestKey: requestKey.optional()
});

const colorFormulaUpdateInput = z.object({
  id: z.string(),
  formula: text(textLimits.formula).min(1),
  placement: oneLineText(textLimits.placement).optional()
});

const colorFormulaDeleteInput = z.object({
  id: z.string()
});

const appointmentCopyServicesInput = z.object({
  targetAppointmentId: z.string(),
  // Left out: the last completed Appointment that has something to copy.
  sourceAppointmentId: z.string().optional(),
  requestKey: requestKey.optional()
});

const completedSourcesInput = z.object({
  // The Appointment to copy into. Left out: every completed Appointment, whatever it could copy.
  targetAppointmentId: z.string().optional()
}).optional();

const appointmentGetInput = z.object({
  id: z.string()
});

const appointmentDeleteInput = z.object({
  id: z.string()
});

const maxFileKeyLength = 200;
const maxUrlLength = 2000;
// Far more than any camera produces, and inside the database's 32-bit column.
const photoPixels = z.number().int().positive().max(100_000);

const appointmentPhotoInput = z.object({
  appointmentId: z.string(),
  clientId: z.string(),
  category: z.enum(["before", "after", "other"]),
  fileKey: text(maxFileKeyLength).optional(),
  url: text(maxUrlLength).url().optional(),
  thumbnailUrl: text(maxUrlLength).url().optional(),
  width: photoPixels.optional(),
  height: photoPixels.optional(),
  // A failed photo has a state and no stored reason: what to say about it is for the app, in the
  // Stylist's language.
  status: z.enum(["pendingUpload", "stored", "failed"]).default("stored"),
  requestKey: requestKey.optional()
});

const appointmentPhotoUpdateInput = z.object({
  id: z.string(),
  category: z.enum(["before", "after", "other"]).optional(),
  status: z.enum(["pendingUpload", "stored", "failed"]).optional()
});

const appointmentPhotoDeleteInput = z.object({
  id: z.string()
});

// What a mutation that adds or reprices Services reads of the ones already there: their prices
// for the total, and their request keys to recognise a request that is sent again.
const existingServices = {
  select: {
    clientId: true,
    menuItemId: true,
    name: true,
    priceCents: true,
    note: true,
    requestKey: true
  }
} as const;

// The Services total of an Appointment is an amount like any other: see maxAmountCents. Only a
// change that raises the total is refused, so an Appointment from before the limit can be put right.
function refuseTotalAboveMaximum(services: { priceCents: number }[], raisedByCents: number) {
  const totalCents = services.reduce((total, service) => total + service.priceCents, 0);

  annotate(() => ({
    "appointment.service_total_before_minor": totalCents,
    "appointment.service_total_change_minor": raisedByCents
  }));

  if (raisedByCents > 0 && totalCents + raisedByCents > maxAmountCents) {
    throw refusal("BAD_REQUEST", "Appointment total is too large.", "appointment_total_above_maximum");
  }
}

type AttachedClients = {
  id: string;
  participants: { clientId: string }[];
};

// Appointment Copying applies to the Clients attached to both Appointments: what a copy adds to
// the target is their Services in the source. An Appointment has nothing to copy into itself.
function getServicesToCopy<Service extends { clientId: string }>(
  source: AttachedClients & { services: Service[] },
  target: AttachedClients
) {
  const sourceClientIds = new Set(source.participants.map((participant) => participant.clientId));
  const sharedClientIds = new Set(
    target.participants.map((participant) => participant.clientId).filter((clientId) => sourceClientIds.has(clientId))
  );

  return source.id === target.id ? [] : source.services.filter((service) => sharedClientIds.has(service.clientId));
}

// The Stylist's Completed Appointments that getServicesToCopy finds something in for this target.
function getCopySourceFilter(stylistId: string, target: AttachedClients): Prisma.AppointmentWhereInput {
  return {
    stylistId,
    status: "completed",
    id: {
      not: target.id
    },
    OR: target.participants.map(({ clientId }) => ({
      participants: {
        some: {
          clientId
        }
      },
      services: {
        some: {
          clientId
        }
      }
    }))
  };
}

function addMinutes(date: Date, minutes: number) {
  return new Date(date.getTime() + minutes * 60 * 1000);
}

function getConflictWindow(startsAt: Date, endsAt: Date | null) {
  return {
    start: startsAt,
    end: endsAt ?? addMinutes(startsAt, 30)
  };
}

// Appointments that start together keep one order: the one they were created in.
const appointmentOrder: Prisma.AppointmentOrderByWithRelationInput[] = [
  { startsAt: "asc" },
  { createdAt: "asc" },
  { id: "asc" }
];
const appointmentOrderNewestFirst: Prisma.AppointmentOrderByWithRelationInput[] = [
  { startsAt: "desc" },
  { createdAt: "desc" },
  { id: "desc" }
];

// The primary Client first, then the others in the order they were added. The id settles equal
// timestamps, so the order never changes between loads.
function sortParticipants<T extends { id?: string; createdAt?: Date; client: { id: string } }>(
  participants: T[],
  primaryClientId: string
) {
  return [...participants].sort(
    (a, b) =>
      Number(b.client.id === primaryClientId) - Number(a.client.id === primaryClientId) ||
      (a.createdAt?.getTime() ?? 0) - (b.createdAt?.getTime() ?? 0) ||
      Number((a.id ?? "") > (b.id ?? "")) - Number((a.id ?? "") < (b.id ?? ""))
  );
}

function toAppointmentOutput(appointment: {
  id: string;
  stylistId: string;
  primaryClientId: string;
  startsAt: Date;
  endsAt: Date | null;
  status: "scheduled" | "completed" | "canceled" | "noShow";
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
  locationAddressIsCustom?: boolean | null;
  note: string | null;
  finalTotalCentsOverride: number | null;
  tipCents: number | null;
  primaryClient: {
    id: string;
    name: string;
    address: string | null;
    phone?: string | null;
    language?: "ru" | "en";
  };
  participants?: {
    id?: string;
    createdAt?: Date;
    client: {
      id: string;
      name: string;
      address: string | null;
      phone?: string | null;
      language?: "ru" | "en";
    };
  }[];
  services?: {
    id: string;
    appointmentId: string;
    clientId: string;
    menuItemId: string | null;
    name: string;
    priceCents: number;
    note: string | null;
    createdAt: Date;
    colorFormulas?: {
      id: string;
      appointmentServiceId: string;
      formula: string;
      placement: string | null;
      createdAt: Date;
    }[];
  }[];
  photos?: {
    id: string;
    appointmentId: string;
    clientId: string;
    category: "before" | "after" | "other";
    status: "pendingUpload" | "stored" | "failed";
    provider: string;
    fileKey: string | null;
    url: string | null;
    thumbnailUrl: string | null;
    width: number | null;
    height: number | null;
    createdAt: Date;
  }[];
}) {
  // Services and their Color Formulas come out in one order whatever order they were read in.
  const services = inAddedOrder(appointment.services ?? []);
  const serviceTotalCents = services.reduce((total, service) => total + service.priceCents, 0);
  const clientSubtotals = services.reduce<Record<string, number>>((subtotals, service) => {
    subtotals[service.clientId] = (subtotals[service.clientId] ?? 0) + service.priceCents;
    return subtotals;
  }, {});
  const sortedParticipants =
    appointment.participants && sortParticipants(appointment.participants, appointment.primaryClientId);
  const participants =
    sortedParticipants?.map((participant) => ({
      clientId: participant.client.id,
      name: participant.client.name,
      address: participant.client.address ?? "",
      phone: participant.client.phone ?? "",
      language: participant.client.language ?? "ru",
      isPrimary: participant.client.id === appointment.primaryClientId,
      subtotalCents: clientSubtotals[participant.client.id] ?? 0
    })) ?? [
      {
        clientId: appointment.primaryClient.id,
        name: appointment.primaryClient.name,
        address: appointment.primaryClient.address ?? "",
        phone: appointment.primaryClient.phone ?? "",
        language: appointment.primaryClient.language ?? "ru",
        isPrimary: true,
        subtotalCents: clientSubtotals[appointment.primaryClient.id] ?? 0
      }
    ];

  return {
    id: appointment.id,
    stylistId: appointment.stylistId,
    primaryClientId: appointment.primaryClientId,
    primaryClientName: appointment.primaryClient.name,
    participants,
    startsAt: appointment.startsAt.toISOString(),
    endsAt: appointment.endsAt?.toISOString() ?? null,
    status: appointment.status,
    locationType: appointment.locationType,
    locationAddress: appointment.locationAddress ?? "",
    // The address when the Stylist typed it; empty when it is the salon's or the primary Client's.
    customLocationAddress: hasCustomAddress(appointment) ? appointment.locationAddress ?? "" : "",
    note: appointment.note ?? "",
    services: services.map((service) => ({
      id: service.id,
      appointmentId: service.appointmentId,
      clientId: service.clientId,
      menuItemId: service.menuItemId,
      name: service.name,
      priceCents: service.priceCents,
      note: service.note ?? "",
      createdAt: service.createdAt.toISOString(),
      colorFormulas: inAddedOrder(service.colorFormulas ?? []).map((formula) => ({
        id: formula.id,
        appointmentServiceId: formula.appointmentServiceId,
        formula: formula.formula,
        placement: formula.placement ?? "",
        createdAt: formula.createdAt.toISOString()
      }))
    })),
    photos: appointment.photos?.map((photo) => ({
      id: photo.id,
      appointmentId: photo.appointmentId,
      clientId: photo.clientId,
      category: photo.category,
      status: photo.status,
      provider: photo.provider,
      fileKey: photo.fileKey ?? "",
      url: photo.url ?? "",
      thumbnailUrl: photo.thumbnailUrl ?? "",
      width: photo.width,
      height: photo.height,
      createdAt: photo.createdAt.toISOString()
    })) ?? [],
    serviceTotalCents,
    finalTotalCents: appointment.finalTotalCentsOverride ?? serviceTotalCents,
    finalTotalCentsOverride: appointment.finalTotalCentsOverride,
    // Apart from the totals above, which stay the price of the Services: an app that does not know
    // tips shows the price it always showed.
    tipCents: appointment.tipCents ?? null,
    mapUrl: appointment.locationAddress
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(appointment.locationAddress)}`
      : null
  };
}

type AppointmentRow = Parameters<typeof toAppointmentOutput>[0];

/**
 * The Appointment a request is about, as its reply shows it: its state, its size and what it
 * comes to. Written by every procedure that answers with one Appointment, so its fields describe
 * the Appointment after the change. Telemetry never fails a request: a row it cannot read is left out.
 */
function annotateAppointment(appointment: AppointmentRow) {
  try {
    const services = appointment.services ?? [];
    const photos = appointment.photos;
    const serviceTotalCents = services.reduce((total, service) => total + service.priceCents, 0);

    annotate(() => ({
      "appointment.id": appointment.id,
      "appointment.primary_client_id": appointment.primaryClientId,
      "appointment.status": snakeCase(appointment.status),
      "appointment.location_type": snakeCase(appointment.locationType),
      "appointment.has_custom_address": hasCustomAddress(appointment),
      "appointment.has_end_time": appointment.endsAt !== null,
      "appointment.duration_minutes": appointment.endsAt
        ? Math.round((appointment.endsAt.getTime() - appointment.startsAt.getTime()) / 60_000)
        : undefined,
      "appointment.client_count": appointment.participants?.length ?? 1,
      "appointment.service_count": services.length,
      "appointment.menu_service_count": services.filter((service) => service.menuItemId !== null).length,
      "appointment.color_formula_count": services.reduce((count, service) => count + (service.colorFormulas?.length ?? 0), 0),
      // Only where the reply has photos.
      "appointment.photo_count": photos?.length,
      "appointment.pending_photo_count": photos?.filter((photo) => photo.status === "pendingUpload").length,
      "appointment.failed_photo_count": photos?.filter((photo) => photo.status === "failed").length,
      "appointment.service_total_minor": serviceTotalCents,
      "appointment.has_total_override": appointment.finalTotalCentsOverride !== null,
      "appointment.final_total_minor": appointment.finalTotalCentsOverride ?? serviceTotalCents,
      "appointment.tip_minor": appointment.tipCents ?? undefined,
      "appointment.note_length": appointment.note?.length ?? 0
    }));
  } catch {
    // Left out of the event; the reply is built from the same row and says what is wrong with it.
  }
}

/** toAppointmentOutput for the one Appointment a request is about, described on its event. */
function toDescribedOutput(appointment: AppointmentRow) {
  annotateAppointment(appointment);
  return toAppointmentOutput(appointment);
}

/**
 * Several Appointments a query read (list, home, completedSources): how many, and how much they
 * hold, which is what the reply's size and the time to build it follow.
 */
function annotateAppointmentList(appointments: AppointmentRow[]) {
  try {
    const services = appointments.flatMap((appointment) => appointment.services ?? []);

    annotate(() => ({
      "appointment.count": appointments.length,
      "appointment.listed_client_count": appointments.reduce((count, appointment) => count + (appointment.participants?.length ?? 1), 0),
      "appointment.listed_service_count": services.length,
      "appointment.listed_color_formula_count": services.reduce((count, service) => count + (service.colorFormulas?.length ?? 0), 0),
      "appointment.listed_photo_count": appointments.reduce((count, appointment) => count + (appointment.photos?.length ?? 0), 0)
    }));
  } catch {
    // As in annotateAppointment.
  }
}

async function getAppointmentOutput(db: Db, appointmentId: string) {
  const appointment = await db.appointment.findUniqueOrThrow({
    where: {
      id: appointmentId
    },
    include: {
      primaryClient: {
        select: {
          id: true,
          name: true,
          address: true,
          phone: true,
          language: true
        }
      },
      participants: {
        include: {
          client: {
            select: {
              id: true,
              name: true,
              address: true,
              phone: true,
              language: true
            }
          }
        },
        orderBy: {
          createdAt: "asc"
        }
      },
      services: {
        include: {
          colorFormulas: {
            orderBy: {
              createdAt: "asc"
            }
          }
        },
        orderBy: {
          createdAt: "asc"
        }
      },
      photos: {
        orderBy: {
          createdAt: "asc"
        }
      }
    }
  });

  return toDescribedOutput(appointment);
}

// The Stylist's Scheduled Appointments whose time overlaps the given one. An Appointment without
// an end time counts as 30 minutes long.
async function findConflicts(
  db: Db,
  stylistId: string,
  startsAt: Date,
  endsAt: Date | null,
  exceptAppointmentId?: string
) {
  const window = getConflictWindow(startsAt, endsAt);
  const possibleConflicts = await db.appointment.findMany({
    where: {
      stylistId,
      status: "scheduled",
      startsAt: {
        lt: window.end
      },
      ...(exceptAppointmentId ? { id: { not: exceptAppointmentId } } : {})
    },
    include: {
      primaryClient: {
        select: {
          id: true,
          name: true,
          address: true,
          phone: true,
          language: true
        }
      },
      participants: {
        include: {
          client: {
            select: {
              id: true,
              name: true,
              address: true,
              phone: true,
              language: true
            }
          }
        },
        orderBy: {
          createdAt: "asc"
        }
      }
    },
    orderBy: appointmentOrder
  });

  const conflicts = possibleConflicts.filter((appointment) => {
    const conflictWindow = getConflictWindow(appointment.startsAt, appointment.endsAt);

    return conflictWindow.end > window.start;
  });

  annotate(() => ({
    "appointment.overlap_check": conflicts.length === 0 ? "clear" : "overlaps",
    // Every Scheduled Appointment that starts before this one ends is read, past unresolved ones
    // included: the query's cost grows with them.
    "appointment.conflict_candidate_count": possibleConflicts.length,
    "appointment.conflict_count": conflicts.length,
    "appointment.conflict_ids": conflicts.map((appointment) => appointment.id)
  }));

  return conflicts;
}

export const appointmentRouter = createTRPCRouter({
  list: stylistProcedure.input(dateRangeInput).query(async ({ ctx, input }) => {
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        startsAt: {
          gte: new Date(input.start),
          lt: new Date(input.end)
        }
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        photos: {
          orderBy: {
            createdAt: "asc"
          }
        }
      },
      orderBy: appointmentOrder
    });

    annotate(() => ({ "appointment.range_days": daysBetween(new Date(input.start), new Date(input.end)) }));
    annotateAppointmentList(appointments);

    return appointments.map(toAppointmentOutput);
  }),
  // One Appointment by its id. Its start time can change, so the app cannot find it again by the
  // time it had.
  get: stylistProcedure.input(appointmentGetInput).query(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input.id }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      },
      select: {
        id: true
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    return getAppointmentOutput(ctx.db, appointment.id);
  }),
  home: stylistProcedure.input(dateRangeInput).query(async ({ ctx, input }) => {
    const now = new Date();
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        OR: [
          {
            status: "scheduled",
            startsAt: {
              lt: now
            }
          },
          {
            startsAt: {
              gte: new Date(input.start),
              lt: new Date(input.end)
            }
          }
        ]
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        photos: {
          orderBy: {
            createdAt: "asc"
          }
        }
      },
      orderBy: appointmentOrder
    });

    annotate(() => ({
      "appointment.range_days": daysBetween(new Date(input.start), new Date(input.end)),
      // Past Appointments still Scheduled, which Home lists until the Stylist marks an outcome.
      "appointment.unresolved_count": appointments.filter((appointment) => appointment.status === "scheduled" && appointment.startsAt < now).length
    }));
    annotateAppointmentList(appointments);

    return appointments.map(toAppointmentOutput);
  }),
  // What Appointment Copying can copy from, newest first. For a target: only the Appointments
  // with something to copy into it, the first one being the source that copyServices takes when
  // none is named.
  completedSources: stylistProcedure.input(completedSourcesInput).query(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input?.targetAppointmentId, "appointment.has_copy_target": Boolean(input?.targetAppointmentId) }));

    const targetAppointment = input?.targetAppointmentId
      ? await ctx.db.appointment.findFirst({
          where: {
            id: input.targetAppointmentId,
            stylistId: ctx.stylist.id
          },
          include: {
            participants: true
          }
        })
      : null;

    if (input?.targetAppointmentId && !targetAppointment) {
      throw refusal("NOT_FOUND", "Target Appointment not found.", "copy_target_not_found");
    }

    const appointments = await ctx.db.appointment.findMany({
      where: targetAppointment
        ? getCopySourceFilter(ctx.stylist.id, targetAppointment)
        : {
            stylistId: ctx.stylist.id,
            status: "completed"
          },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      },
      orderBy: appointmentOrderNewestFirst
    });

    annotateAppointmentList(appointments);
    // The source copyServices takes when none is named.
    annotate(() => ({ "appointment.copy_default_source_id": targetAppointment ? appointments[0]?.id : undefined }));

    return appointments.map((appointment) => {
      const output = toAppointmentOutput(appointment);

      if (!targetAppointment) {
        return output;
      }

      const servicesToCopy = getServicesToCopy(appointment, targetAppointment);

      return {
        ...output,
        // What a copy from this Appointment into the target adds, and for which of its Clients.
        copyableServiceCount: servicesToCopy.length,
        copyableClientIds: output.participants
          .map((participant) => participant.clientId)
          .filter((clientId) => servicesToCopy.some((service) => service.clientId === clientId))
      };
    });
  }),
  create: stylistTransactionProcedure.input(appointmentCreateInput).mutation(async ({ ctx, input }) => {
    const startsAt = new Date(input.startsAt);
    const endsAt = input.endsAt ? new Date(input.endsAt) : null;

    annotate(() => ({
      "appointment.primary_client_source": input.primaryClientId ? "existing" : "inline"
    }));
    annotateStart(startsAt, ctx.stylist.timezone);

    if (endsAt && endsAt <= startsAt) {
      throw refusal("BAD_REQUEST", "End time must be after start time.", "end_not_after_start");
    }

    const additionalClientIds = [...new Set(input.additionalClientIds.filter((clientId) => clientId !== input.primaryClientId))];

    // Ids the app sent twice, or the primary Client again among the others: dropped, as before.
    annotate(() => ({ "appointment.dropped_client_id_count": input.additionalClientIds.length - additionalClientIds.length }));

    const repeatedAppointment = input.requestKey
      ? await ctx.db.appointment.findFirst({
          where: {
            stylistId: ctx.stylist.id,
            requestKey: input.requestKey
          },
          include: {
            primaryClient: {
              select: {
                name: true
              }
            },
            participants: {
              select: {
                clientId: true
              }
            }
          }
        })
      : null;

    annotate(() => ({ "appointment.is_repeat": input.requestKey ? Boolean(repeatedAppointment) : undefined }));

    if (repeatedAppointment) {
      const atCustomAddress = input.locationType === "atHome" && Boolean(input.customLocationAddress);

      refuseChangedRepeat(
        {
          ...repeatedAppointment,
          inlineClientName: repeatedAppointment.primaryClient.name,
          clientIds: repeatedAppointment.participants.map((participant) => participant.clientId).sort().join(),
          // An address the first call saved to the primary Client is the Appointment's, as typed.
          customLocationAddress:
            repeatedAppointment.locationAddressIsCustom || (input.saveAddressToClient && repeatedAppointment.locationType === "atHome")
              ? repeatedAppointment.locationAddress
              : ""
        },
        {
          startsAt,
          endsAt,
          locationType: input.locationType,
          customLocationAddress: atCustomAddress ? input.customLocationAddress : "",
          primaryClientId: input.primaryClientId,
          // The Client the first call created inline is recognised by the name it was given.
          inlineClientName: input.primaryClientId ? undefined : input.inlineClientName,
          clientIds: [input.primaryClientId ?? repeatedAppointment.primaryClientId, ...additionalClientIds].sort().join()
        },
        "appointment"
      );

      // The Appointment the first call created, with the overlap warning that call answered with.
      const repeatedConflicts =
        repeatedAppointment.status === "scheduled"
          ? await findConflicts(
              ctx.db,
              ctx.stylist.id,
              repeatedAppointment.startsAt,
              repeatedAppointment.endsAt,
              repeatedAppointment.id
            )
          : [];

      return {
        appointment: await getAppointmentOutput(ctx.db, repeatedAppointment.id),
        conflicts: repeatedConflicts.map(toAppointmentOutput),
        // The first call said what it added.
        travelFee: noTravelFeeChange
      };
    }

    const primaryClient = input.primaryClientId
      ? await ctx.db.client.findFirst({
          where: {
            id: input.primaryClientId,
            stylistId: ctx.stylist.id
          }
        })
      : await ctx.db.client.create({
          data: {
            stylistId: ctx.stylist.id,
            name: input.inlineClientName ?? "",
            language: ctx.stylist.language,
            phone: "",
            email: "",
            address: "",
            note: ""
          }
        });

    if (!primaryClient) {
      throw refusal("NOT_FOUND", "Primary Client not found.", "primary_client_not_found");
    }

    // The id of a Client created inline too.
    annotate(() => ({ "appointment.primary_client_id": primaryClient.id }));

    const additionalClients =
      additionalClientIds.length > 0
        ? await ctx.db.client.findMany({
            where: {
              id: {
                in: additionalClientIds
              },
              stylistId: ctx.stylist.id
            }
          })
        : [];

    if (additionalClients.length !== additionalClientIds.length) {
      throw refusal("NOT_FOUND", "One or more group Clients were not found.", "group_client_not_found");
    }

    const typedAddress = input.locationType === "atHome" ? input.customLocationAddress ?? "" : "";
    // Before the Appointment is created, so that it comes back with its primary Client's new address.
    const addressSavedToClient =
      input.saveAddressToClient === true && (await saveAddressToClient(ctx.db, ctx.stylist.id, primaryClient.id, typedAddress));
    const locationAddress =
      input.locationType === "atHome"
        ? typedAddress || primaryClient.address || ""
        : await getSalonAddress(ctx.db, ctx.stylist.id);

    annotate(() => ({
      // Where the address of the new Appointment came from.
      "appointment.location_source":
        input.locationType === "inSalon" ? "salon" : typedAddress ? (addressSavedToClient ? "typed_saved_to_client" : "typed") : "client",
      // Asked for: whether the primary Client had no address and so took the typed one.
      "appointment.is_address_saved_to_client": input.saveAddressToClient === true ? addressSavedToClient : undefined,
      "appointment.has_location_address": locationAddress.length > 0
    }));

    const conflicts = await findConflicts(ctx.db, ctx.stylist.id, startsAt, endsAt);
    const addedAt = Date.now();
    const appointment = await ctx.db.appointment.create({
      data: {
        stylistId: ctx.stylist.id,
        primaryClientId: primaryClient.id,
        startsAt,
        endsAt,
        locationType: input.locationType,
        locationAddress,
        locationAddressIsCustom: Boolean(typedAddress) && !addressSavedToClient,
        note: "",
        finalTotalCentsOverride: null,
        requestKey: input.requestKey,
        participants: {
          // One millisecond apart, so the Clients keep the order they were picked in.
          create: [primaryClient.id, ...additionalClientIds].map((clientId, index) => ({
            clientId,
            createdAt: new Date(addedAt + index)
          }))
        }
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    // A new Appointment is Scheduled: at home, it gets the Travel Fee for its primary Client.
    const travelFee =
      input.locationType === "atHome"
        ? await addTravelFee(ctx.db, ctx.stylist.id, appointment.id, primaryClient.id)
        : noTravelFeeChange;

    annotateTravelFee(input.locationType === "atHome" ? travelFee : null);

    return {
      appointment: travelFee.added.length > 0 ? await getAppointmentOutput(ctx.db, appointment.id) : toDescribedOutput(appointment),
      conflicts: conflicts.map(toAppointmentOutput),
      // The Services the Travel Fee added, for the app to say so and offer to remove them.
      travelFee
    };
  }),
  update: stylistTransactionProcedure.input(appointmentUpdateInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input.id }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      },
      include: {
        primaryClient: {
          select: {
            address: true
          }
        }
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    annotate(() => ({ "appointment.previous_status": snakeCase(appointment.status) }));

    const startsAt = input.startsAt ? new Date(input.startsAt) : appointment.startsAt;
    const endsAt = input.endsAt === undefined ? appointment.endsAt : input.endsAt ? new Date(input.endsAt) : null;

    if ((input.startsAt || input.endsAt) && endsAt && endsAt <= startsAt) {
      throw refusal("BAD_REQUEST", "End time must be after start time.", "end_not_after_start");
    }

    if (input.primaryClientId) {
      const participant = await ctx.db.appointmentParticipant.findFirst({
        where: {
          appointmentId: appointment.id,
          clientId: input.primaryClientId
        }
      });

      if (!participant) {
        throw refusal("BAD_REQUEST", "Primary Client must be attached to the Appointment.", "primary_client_not_attached");
      }
    }

    const primaryClientId = input.primaryClientId ?? appointment.primaryClientId;
    const status = input.status ?? appointment.status;
    const locationType = input.locationType ?? appointment.locationType;
    const becomesScheduled = status === "scheduled" && appointment.status !== "scheduled";
    const locationTypeChanges = locationType !== appointment.locationType;
    // Saved as in salon while Scheduled, also when it already was in salon: its stored address
    // can be older than the salon's.
    const savedAsInSalon = input.locationType === "inSalon" && status === "scheduled";
    let location: { locationAddress: string; locationAddressIsCustom: boolean } | undefined;
    // Where the saved address came from, for the event; "unchanged" when the save keeps it.
    let locationSource = "unchanged";
    let addressSavedToClient: boolean | undefined;

    if (locationType === "inSalon") {
      // A Scheduled in-salon Appointment shows the salon's current address; one with an outcome
      // keeps the address it had.
      if (locationTypeChanges || becomesScheduled || savedAsInSalon) {
        location = {
          locationAddress: await getSalonAddress(ctx.db, ctx.stylist.id),
          locationAddressIsCustom: false
        };
        locationSource = "salon";
      }
    } else if (input.customLocationAddress) {
      addressSavedToClient =
        input.saveAddressToClient === true &&
        (await saveAddressToClient(ctx.db, ctx.stylist.id, primaryClientId, input.customLocationAddress));

      location = {
        locationAddress: input.customLocationAddress,
        locationAddressIsCustom: !addressSavedToClient
      };
      locationSource = addressSavedToClient ? "typed_saved_to_client" : "typed";
    } else if (
      input.customLocationAddress !== undefined ||
      locationTypeChanges ||
      (primaryClientId !== appointment.primaryClientId && !hasCustomAddress(appointment))
    ) {
      // At home without a custom address is at the primary Client's address, so it follows a
      // change of primary Client.
      location = {
        locationAddress: await getClientAddress(ctx.db, primaryClientId),
        locationAddressIsCustom: false
      };
      locationSource = "client";
    }

    // Which of the fields the save sent change the Appointment. A save that changes none is a
    // no-op, such as the same form saved twice; it still writes the row.
    annotate(() => {
      const changedFields = [
        input.primaryClientId !== undefined && primaryClientId !== appointment.primaryClientId && "primary_client",
        input.status !== undefined && status !== appointment.status && "status",
        input.startsAt !== undefined && startsAt.getTime() !== appointment.startsAt.getTime() && "starts_at",
        input.endsAt !== undefined && endsAt?.getTime() !== appointment.endsAt?.getTime() && "ends_at",
        locationTypeChanges && "location_type",
        location !== undefined && location.locationAddress !== (appointment.locationAddress ?? "") && "location_address",
        input.note !== undefined && input.note !== (appointment.note ?? "") && "note",
        input.finalTotalCentsOverride !== undefined &&
          input.finalTotalCentsOverride !== (appointment.finalTotalCentsOverride ?? null) &&
          "total_override",
        input.tipCents !== undefined && input.tipCents !== (appointment.tipCents ?? null) && "tip"
      ].filter((field): field is string => typeof field === "string");

      return {
        "appointment.changed_fields": changedFields,
        "appointment.changed_field_count": changedFields.length,
        "appointment.is_noop_save": changedFields.length === 0,
        "appointment.is_back_to_scheduled": becomesScheduled,
        "appointment.location_source": locationSource,
        "appointment.is_address_saved_to_client": input.saveAddressToClient === true ? addressSavedToClient : undefined
      };
    });

    if (input.startsAt !== undefined) {
      annotateStart(startsAt, ctx.stylist.timezone);
    }

    // The Travel Fee follows a Scheduled Appointment that changes place: to at home it is added
    // for the primary Client, to the salon it is removed. Nothing else adds or removes it, so an
    // edit that keeps the place leaves the Services as the Stylist made them.
    const travelFee =
      status === "scheduled" && locationTypeChanges
        ? locationType === "atHome"
          ? await addTravelFee(ctx.db, ctx.stylist.id, appointment.id, primaryClientId)
          : await removeTravelFee(ctx.db, ctx.stylist.id, appointment.id)
        : noTravelFeeChange;

    annotateTravelFee(status === "scheduled" && locationTypeChanges ? travelFee : null);

    const updatedAppointment = await ctx.db.appointment.update({
      where: {
        id: appointment.id
      },
      data: {
        ...(input.primaryClientId ? { primaryClientId: input.primaryClientId } : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.note !== undefined ? { note: input.note } : {}),
        ...(input.finalTotalCentsOverride !== undefined ? { finalTotalCentsOverride: input.finalTotalCentsOverride } : {}),
        ...(input.tipCents !== undefined ? { tipCents: input.tipCents } : {}),
        ...(input.startsAt ? { startsAt } : {}),
        ...(input.endsAt !== undefined ? { endsAt } : {}),
        ...(input.locationType ? { locationType: input.locationType } : {}),
        ...location
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });
    // The overlap warning of Appointment Creation, for an Appointment that moved or went back to
    // Scheduled.
    const timeChanges =
      startsAt.getTime() !== appointment.startsAt.getTime() || endsAt?.getTime() !== appointment.endsAt?.getTime();
    const conflicts =
      status === "scheduled" && (timeChanges || becomesScheduled)
        ? await findConflicts(ctx.db, ctx.stylist.id, startsAt, endsAt, appointment.id)
        : [];

    if (!(status === "scheduled" && (timeChanges || becomesScheduled))) {
      annotate(() => ({ "appointment.overlap_check": "skipped" }));
    }

    return {
      ...toDescribedOutput(updatedAppointment),
      conflicts: conflicts.map(toAppointmentOutput),
      // The Services the Travel Fee added or removed, for the app to say so and offer to undo it.
      travelFee
    };
  }),
  addService: stylistTransactionProcedure.input(appointmentServiceInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({
      "appointment.id": input.appointmentId,
      "appointment_service.client_id": input.clientId,
      "appointment_service.menu_item_id": input.menuItemId,
      "appointment_service.source": input.menuItemId ? "menu" : "ad_hoc",
      "appointment_service.name_length": input.name?.length,
      "appointment_service.note_length": input.note?.length ?? 0
    }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true,
        services: existingServices
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    const repeatedService = input.requestKey
      ? appointment.services.find((service) => service.requestKey === input.requestKey)
      : undefined;

    annotate(() => ({ "appointment_service.is_repeat": input.requestKey ? Boolean(repeatedService) : undefined }));

    if (repeatedService) {
      // A name or price left out came from the Service Menu Item: there is nothing to compare.
      refuseChangedRepeat(repeatedService, {
        clientId: input.clientId,
        menuItemId: input.menuItemId ?? null,
        name: input.name,
        priceCents: input.priceCents,
        note: input.note ?? ""
      }, "appointment_service");

      return getAppointmentOutput(ctx.db, appointment.id);
    }

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw refusal("BAD_REQUEST", "Service Client must be attached to the Appointment.", "service_client_not_attached");
    }

    const menuItem = input.menuItemId
      ? await ctx.db.serviceMenuItem.findFirst({
          where: {
            id: input.menuItemId,
            stylistId: ctx.stylist.id
          }
        })
      : null;

    if (input.menuItemId && !menuItem) {
      throw refusal("NOT_FOUND", "Service Menu Item not found.", "menu_item_not_found");
    }

    if (!menuItem && !input.name) {
      throw refusal("BAD_REQUEST", "Service name is required.", "service_name_missing");
    }

    const priceCents = input.priceCents ?? menuItem?.defaultPriceCents ?? 0;

    annotate(() => ({
      "appointment_service.price_minor": priceCents,
      // "sent" with a menu item: the Stylist typed a price of her own, or kept the default.
      "appointment_service.price_source": input.priceCents !== undefined ? "sent" : menuItem ? "menu_default" : "none",
      "appointment_service.is_menu_price_changed": menuItem && input.priceCents !== undefined ? input.priceCents !== menuItem.defaultPriceCents : undefined,
      "appointment_service.is_menu_name_changed": menuItem && input.name !== undefined ? input.name !== menuItem.name : undefined,
      "appointment_service.is_travel_fee": menuItem ? menuItem.travelFee : undefined
    }));

    refuseTotalAboveMaximum(appointment.services, priceCents);

    const createdService = await ctx.db.appointmentService.create({
      data: {
        appointmentId: appointment.id,
        clientId: input.clientId,
        menuItemId: menuItem?.id ?? null,
        name: input.name ?? menuItem?.name ?? "",
        priceCents,
        note: input.note ?? "",
        requestKey: input.requestKey
      }
    });

    annotate(() => ({ "appointment_service.id": createdService?.id }));

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: appointment.id
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    return toDescribedOutput(updatedAppointment);
  }),
  // Several Services from the Service Menu at once, each at its item's default price, for one
  // Client in the order of menuItemIds, or for several Clients (clients) one after another. All
  // are added or none.
  addMenuServices: stylistTransactionProcedure.input(appointmentMenuServicesInput).mutation(async ({ ctx, input }) => {
    const picks = menuServicePicks(input);
    const pickedItemIds = [...new Set(picks.map((pick) => pick.menuItemId))];

    annotate(() => ({
      "appointment.id": input.appointmentId,
      // The input's two forms: one Client with menuItemIds, or several Clients at once.
      "appointment_service.picks_kind": input.clients ? "clients" : "one_client",
      "appointment_service.pick_count": picks.length,
      "appointment_service.pick_client_count": new Set(picks.map((pick) => pick.clientId)).size,
      "appointment_service.menu_item_count": pickedItemIds.length,
      "appointment_service.menu_item_ids": pickedItemIds
    }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true,
        services: {
          select: {
            ...existingServices.select,
            id: true,
            createdAt: true
          }
        }
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    const repeatedServices = input.requestKey
      ? inAddedOrder(appointment.services.filter((service) => service.requestKey === input.requestKey))
      : [];

    annotate(() => ({ "appointment_service.is_repeat": input.requestKey ? repeatedServices.length > 0 : undefined }));

    if (repeatedServices.length > 0) {
      // The prices came from the menu: there is nothing to compare but whose and which items.
      refuseChangedRepeat(
        { services: repeatedServices.map((service) => `${service.clientId}:${service.menuItemId}`).join(" ") },
        { services: picks.map((pick) => `${pick.clientId}:${pick.menuItemId}`).join(" ") },
        "appointment_service"
      );

      return getAppointmentOutput(ctx.db, appointment.id);
    }

    if (!picks.every((pick) => appointment.participants.some((participant) => participant.clientId === pick.clientId))) {
      throw refusal("BAD_REQUEST", "Service Client must be attached to the Appointment.", "service_client_not_attached");
    }

    const menuItems = await ctx.db.serviceMenuItem.findMany({
      where: {
        id: {
          in: pickedItemIds
        },
        stylistId: ctx.stylist.id
      }
    });
    const picked = picks.map((pick) => ({ clientId: pick.clientId, item: menuItems.find((item) => item.id === pick.menuItemId) }));

    if (picked.some((pick) => !pick.item)) {
      throw refusal("NOT_FOUND", "Service Menu Item not found.", "menu_item_not_found");
    }

    const items = picked.flatMap(({ clientId, item }) => (item ? [{ clientId, item }] : []));
    const addedCents = items.reduce((total, { item }) => total + item.defaultPriceCents, 0);

    annotate(() => ({
      "appointment_service.added_minor": addedCents,
      "appointment_service.travel_fee_count": items.filter(({ item }) => item.travelFee).length
    }));

    refuseTotalAboveMaximum(appointment.services, addedCents);

    // Dated one millisecond apart, as Appointment Copying does: they keep the order they were picked in.
    const addedAt = Date.now();

    // One insert per Service, up to a hundred: a unit of work of its own in the trace.
    await traced(
      "appointment.add_menu_services",
      async () => {
        for (const [index, { clientId, item }] of items.entries()) {
          await ctx.db.appointmentService.create({
            data: {
              appointmentId: appointment.id,
              clientId,
              menuItemId: item.id,
              name: item.name,
              priceCents: item.defaultPriceCents,
              note: "",
              requestKey: input.requestKey,
              createdAt: new Date(addedAt + index)
            }
          });
        }
      },
      { "appointment_service.count": items.length }
    );

    return getAppointmentOutput(ctx.db, appointment.id);
  }),
  updateService: stylistTransactionProcedure.input(appointmentServiceUpdateInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment_service.id": input.id }));

    const service = await ctx.db.appointmentService.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      },
      include: {
        appointment: {
          select: {
            services: existingServices
          }
        }
      }
    });

    if (!service) {
      throw refusal("NOT_FOUND", "Appointment Service not found.", "service_not_found");
    }

    annotate(() => {
      const changedFields = [
        input.name !== service.name && "name",
        input.priceCents !== service.priceCents && "price",
        (input.note ?? "") !== (service.note ?? "") && "note"
      ].filter((field): field is string => typeof field === "string");

      return {
        "appointment.id": service.appointmentId,
        "appointment_service.client_id": service.clientId,
        "appointment_service.menu_item_id": service.menuItemId ?? undefined,
        "appointment_service.previous_price_minor": service.priceCents,
        "appointment_service.price_minor": input.priceCents,
        "appointment_service.name_length": input.name.length,
        "appointment_service.note_length": input.note?.length ?? 0,
        "appointment_service.changed_fields": changedFields,
        "appointment_service.is_noop_save": changedFields.length === 0
      };
    });

    refuseTotalAboveMaximum(service.appointment.services, input.priceCents - service.priceCents);

    await ctx.db.appointmentService.update({
      where: {
        id: service.id
      },
      data: {
        name: input.name,
        priceCents: input.priceCents,
        note: input.note ?? ""
      }
    });

    // The whole Appointment, photos included: the app can show the reply as it is.
    return getAppointmentOutput(ctx.db, service.appointmentId);
  }),
  deleteService: stylistProcedure.input(appointmentServiceDeleteInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment_service.id": input.id }));

    const service = await ctx.db.appointmentService.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!service) {
      throw refusal("NOT_FOUND", "Appointment Service not found.", "service_not_found");
    }

    annotate(() => ({
      "appointment.id": service.appointmentId,
      "appointment_service.client_id": service.clientId,
      "appointment_service.menu_item_id": service.menuItemId ?? undefined,
      "appointment_service.source": service.menuItemId ? "menu" : "ad_hoc",
      "appointment_service.price_minor": service.priceCents
    }));

    await ctx.db.appointmentService.delete({
      where: {
        id: service.id
      }
    });

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: service.appointmentId
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    return toDescribedOutput(updatedAppointment);
  }),
  addPhoto: stylistTransactionProcedure.input(appointmentPhotoInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({
      "appointment.id": input.appointmentId,
      "photo.client_id": input.clientId,
      "photo.category": input.category,
      "photo.status": snakeCase(input.status),
      // Whether the app sent the stored file's details, never the details themselves.
      "photo.has_file_key": Boolean(input.fileKey),
      "photo.has_url": Boolean(input.url),
      "photo.has_thumbnail_url": Boolean(input.thumbnailUrl),
      "photo.width": input.width,
      "photo.height": input.height
    }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true,
        photos: {
          select: {
            clientId: true,
            category: true,
            fileKey: true,
            requestKey: true
          }
        }
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    // A request that is sent again, recognised by its key or by the stored file: one file is one
    // Appointment Photo.
    const repeatedPhoto = appointment.photos.find(
      (photo) =>
        (input.requestKey && photo.requestKey === input.requestKey) ||
        (input.fileKey && photo.fileKey === input.fileKey)
    );

    annotate(() => ({
      "photo.is_repeat": repeatedPhoto !== undefined,
      "photo.repeat_kind": repeatedPhoto
        ? input.requestKey && repeatedPhoto.requestKey === input.requestKey
          ? "request_key"
          : "file"
        : undefined
    }));

    if (repeatedPhoto) {
      // Not the file: a picture that is uploaded again for the same key is a new file each time.
      refuseChangedRepeat(repeatedPhoto, {
        clientId: input.clientId,
        category: input.category
      }, "photo");

      return getAppointmentOutput(ctx.db, appointment.id);
    }

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw refusal("BAD_REQUEST", "Photo Client must be attached to the Appointment.", "photo_client_not_attached");
    }

    if (input.status === "stored" && (!input.fileKey || !input.url)) {
      throw refusal("BAD_REQUEST", "Stored Appointment Photos require UploadThing file metadata.", "stored_photo_without_file");
    }

    // The app loads both URLs as images: each has to be the stored file this photo names.
    if ([input.url, input.thumbnailUrl].some((url) => url && !isStoredFileUrl(url, input.fileKey ?? "", ctx.photoOrigins))) {
      throw refusal("BAD_REQUEST", "Appointment Photo URLs must point at UploadThing storage.", "photo_not_in_storage");
    }

    // One stored file is one Appointment Photo, of any Stylist: deleting a photo may then delete
    // its file. A photo of this Appointment with the file was answered above.
    if (
      input.fileKey &&
      (await ctx.db.appointmentPhoto.findFirst({
        where: {
          fileKey: input.fileKey
        },
        select: {
          id: true
        }
      }))
    ) {
      throw refusal("BAD_REQUEST", "This file already belongs to another Appointment Photo.", "photo_file_taken");
    }

    const createdPhoto = await ctx.db.appointmentPhoto.create({
      data: {
        appointmentId: appointment.id,
        clientId: input.clientId,
        category: input.category,
        status: input.status,
        provider: "uploadthing",
        fileKey: input.fileKey ?? null,
        url: input.url ?? null,
        thumbnailUrl: input.thumbnailUrl ?? null,
        width: input.width ?? null,
        height: input.height ?? null,
        requestKey: input.requestKey
      }
    });

    annotate(() => ({ "photo.id": createdPhoto?.id }));

    return getAppointmentOutput(ctx.db, appointment.id);
  }),
  updatePhoto: stylistProcedure.input(appointmentPhotoUpdateInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "photo.id": input.id }));

    const photo = await ctx.db.appointmentPhoto.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!photo) {
      throw refusal("NOT_FOUND", "Appointment Photo not found.", "photo_not_found");
    }

    // The status moves of a photo (pending_upload to stored or failed) say how uploads go.
    annotate(() => {
      const changedFields = [
        input.category !== undefined && input.category !== photo.category && "category",
        input.status !== undefined && input.status !== photo.status && "status"
      ].filter((field): field is string => typeof field === "string");

      return {
        "appointment.id": photo.appointmentId,
        "photo.client_id": photo.clientId,
        "photo.previous_category": photo.category,
        "photo.category": input.category ?? photo.category,
        "photo.previous_status": snakeCase(photo.status),
        "photo.status": snakeCase(input.status ?? photo.status),
        "photo.changed_fields": changedFields,
        "photo.is_noop_save": changedFields.length === 0
      };
    });

    await ctx.db.appointmentPhoto.update({
      where: {
        id: photo.id
      },
      data: {
        ...(input.category ? { category: input.category } : {}),
        ...(input.status ? { status: input.status } : {})
      }
    });

    return getAppointmentOutput(ctx.db, photo.appointmentId);
  }),
  deletePhoto: stylistTransactionProcedure.input(appointmentPhotoDeleteInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "photo.id": input.id }));

    const photo = await ctx.db.appointmentPhoto.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!photo) {
      throw refusal("NOT_FOUND", "Appointment Photo not found.", "photo_not_found");
    }

    annotate(() => ({
      "appointment.id": photo.appointmentId,
      "photo.client_id": photo.clientId,
      "photo.category": photo.category,
      "photo.status": snakeCase(photo.status),
      // Whether a stored file goes with it, to delete after the commit (tx.unused_file_count).
      "photo.has_file_key": Boolean(photo.fileKey)
    }));

    await ctx.db.appointmentPhoto.delete({
      where: {
        id: photo.id
      }
    });
    await markFilesUnused(ctx, [photo]);

    return getAppointmentOutput(ctx.db, photo.appointmentId);
  }),
  addColorFormula: stylistTransactionProcedure.input(colorFormulaInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({
      "appointment_service.id": input.appointmentServiceId,
      "color_formula.formula_length": input.formula.length,
      "color_formula.has_placement": Boolean(input.placement)
    }));

    const service = await ctx.db.appointmentService.findFirst({
      where: {
        id: input.appointmentServiceId,
        appointment: {
          stylistId: ctx.stylist.id
        }
      },
      include: {
        colorFormulas: {
          select: {
            formula: true,
            placement: true,
            requestKey: true
          }
        }
      }
    });

    if (!service) {
      throw refusal("NOT_FOUND", "Appointment Service not found.", "service_not_found");
    }

    const repeatedFormula = input.requestKey
      ? service.colorFormulas.find((formula) => formula.requestKey === input.requestKey)
      : undefined;

    annotate(() => ({
      "appointment.id": service.appointmentId,
      "appointment_service.menu_item_id": service.menuItemId ?? undefined,
      // Formulas the Service had before: a second one is the exception.
      "appointment_service.color_formula_count": service.colorFormulas.length,
      "color_formula.is_repeat": input.requestKey ? Boolean(repeatedFormula) : undefined
    }));

    if (repeatedFormula) {
      refuseChangedRepeat(repeatedFormula, {
        formula: input.formula,
        placement: input.placement ?? ""
      }, "color_formula");

      return getAppointmentOutput(ctx.db, service.appointmentId);
    }

    const createdFormula = await ctx.db.colorFormula.create({
      data: {
        appointmentServiceId: service.id,
        formula: input.formula,
        placement: input.placement ?? "",
        requestKey: input.requestKey
      }
    });

    annotate(() => ({ "color_formula.id": createdFormula?.id }));

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: service.appointmentId
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    return toDescribedOutput(updatedAppointment);
  }),
  updateColorFormula: stylistProcedure.input(colorFormulaUpdateInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({
      "color_formula.id": input.id,
      "color_formula.formula_length": input.formula.length,
      "color_formula.has_placement": Boolean(input.placement)
    }));

    const colorFormula = await ctx.db.colorFormula.findFirst({
      where: {
        id: input.id,
        appointmentService: {
          appointment: {
            stylistId: ctx.stylist.id
          }
        }
      },
      include: {
        appointmentService: true
      }
    });

    if (!colorFormula) {
      throw refusal("NOT_FOUND", "Color Formula not found.", "color_formula_not_found");
    }

    annotate(() => {
      const changedFields = [
        input.formula !== colorFormula.formula && "formula",
        (input.placement ?? "") !== (colorFormula.placement ?? "") && "placement"
      ].filter((field): field is string => typeof field === "string");

      return {
        "appointment.id": colorFormula.appointmentService.appointmentId,
        "appointment_service.id": colorFormula.appointmentServiceId,
        "color_formula.changed_fields": changedFields,
        "color_formula.is_noop_save": changedFields.length === 0
      };
    });

    await ctx.db.colorFormula.update({
      where: {
        id: colorFormula.id
      },
      data: {
        formula: input.formula,
        placement: input.placement ?? ""
      }
    });

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: colorFormula.appointmentService.appointmentId
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    return toDescribedOutput(updatedAppointment);
  }),
  deleteColorFormula: stylistProcedure.input(colorFormulaDeleteInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "color_formula.id": input.id }));

    const colorFormula = await ctx.db.colorFormula.findFirst({
      where: {
        id: input.id,
        appointmentService: {
          appointment: {
            stylistId: ctx.stylist.id
          }
        }
      },
      include: {
        appointmentService: true
      }
    });

    if (!colorFormula) {
      throw refusal("NOT_FOUND", "Color Formula not found.", "color_formula_not_found");
    }

    annotate(() => ({
      "appointment.id": colorFormula.appointmentService.appointmentId,
      "appointment_service.id": colorFormula.appointmentServiceId
    }));

    await ctx.db.colorFormula.delete({
      where: {
        id: colorFormula.id
      }
    });

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: colorFormula.appointmentService.appointmentId
      },
      include: {
        primaryClient: {
          select: {
            id: true,
            name: true,
            address: true,
            phone: true,
            language: true
          }
        },
        participants: {
          include: {
            client: {
              select: {
                id: true,
                name: true,
                address: true,
                phone: true,
                language: true
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        },
        services: {
          include: {
            colorFormulas: {
              orderBy: {
                createdAt: "asc"
              }
            }
          },
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    return toDescribedOutput(updatedAppointment);
  }),
  copyServices: stylistTransactionProcedure.input(appointmentCopyServicesInput).mutation(async ({ ctx, input }) => {
    // The Appointment copied into is the one the request is about.
    annotate(() => ({
      "appointment.id": input.targetAppointmentId,
      "appointment.copy_source_id": input.sourceAppointmentId,
      "appointment.copy_source_choice": input.sourceAppointmentId ? "named" : "latest"
    }));

    const targetAppointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.targetAppointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true,
        services: existingServices
      }
    });

    if (!targetAppointment) {
      throw refusal("NOT_FOUND", "Target Appointment not found.", "copy_target_not_found");
    }

    const repeatedServiceCount = input.requestKey
      ? targetAppointment.services.filter((service) => service.requestKey === input.requestKey).length
      : 0;

    annotate(() => ({ "appointment_service.is_repeat": input.requestKey ? repeatedServiceCount > 0 : undefined }));

    if (repeatedServiceCount > 0) {
      annotate(() => ({ "appointment_service.copied_count": repeatedServiceCount }));

      return {
        ...(await getAppointmentOutput(ctx.db, targetAppointment.id)),
        copiedServiceCount: repeatedServiceCount
      };
    }

    // Without a named source: the last completed Appointment that has something to copy.
    const sourceAppointment = await ctx.db.appointment.findFirst({
      where: input.sourceAppointmentId
        ? {
            id: input.sourceAppointmentId,
            stylistId: ctx.stylist.id
          }
        : getCopySourceFilter(ctx.stylist.id, targetAppointment),
      include: {
        participants: true,
        services: {
          include: {
            colorFormulas: true
          }
        }
      },
      orderBy: appointmentOrderNewestFirst
    });

    if (!sourceAppointment) {
      throw input.sourceAppointmentId
        ? refusal("NOT_FOUND", "Source Appointment not found.", "copy_source_not_found")
        : refusal("NOT_FOUND", "Completed source Appointment not found.", "no_completed_copy_source");
    }

    annotate(() => ({
      "appointment.copy_source_id": sourceAppointment.id,
      "appointment.copy_source_status": snakeCase(sourceAppointment.status),
      "appointment.copy_source_service_count": sourceAppointment.services.length,
      // How old what is copied is: the Stylist's usual time between two visits of a Client.
      "appointment.copy_source_age_days": daysBetween(sourceAppointment.startsAt, new Date())
    }));

    if (sourceAppointment.status !== "completed") {
      throw refusal("NOT_FOUND", "Source Appointment is no longer Completed.", "copy_source_not_completed");
    }

    const servicesToCopy = inAddedOrder(getServicesToCopy(sourceAppointment, targetAppointment));
    const copiedCents = servicesToCopy.reduce((total, service) => total + service.priceCents, 0);
    // For the event only, so it reads nothing that could be missing.
    const copiedFormulaCount = servicesToCopy.reduce((count, service) => count + (service.colorFormulas?.length ?? 0), 0);

    annotate(() => ({
      "appointment_service.copied_count": servicesToCopy.length,
      "appointment_service.copied_minor": copiedCents,
      "appointment_service.copied_client_count": new Set(servicesToCopy.map((service) => service.clientId)).size,
      "color_formula.copied_count": copiedFormulaCount
    }));

    if (servicesToCopy.length === 0) {
      throw refusal("BAD_REQUEST", "Source Appointment has no Services to copy.", "nothing_to_copy");
    }

    refuseTotalAboveMaximum(targetAppointment.services, copiedCents);

    // Rows written together would share a timestamp, and the timestamp is their order. The copies
    // are dated one millisecond apart instead, so they keep the order of the source for good.
    // Writing them takes longer than the milliseconds they span, so what is added next comes after.
    const copiedAt = Date.now();

    // One insert per Service with its Color Formulas: a unit of work of its own in the trace.
    await traced(
      "appointment.copy_services",
      async () => {
        for (const [index, service] of servicesToCopy.entries()) {
          await ctx.db.appointmentService.create({
            data: {
              appointmentId: targetAppointment.id,
              clientId: service.clientId,
              menuItemId: service.menuItemId,
              name: service.name,
              priceCents: service.priceCents,
              note: service.note ?? "",
              requestKey: input.requestKey,
              createdAt: new Date(copiedAt + index),
              colorFormulas: {
                create: inAddedOrder(service.colorFormulas).map((formula, formulaIndex) => ({
                  formula: formula.formula,
                  placement: formula.placement ?? "",
                  createdAt: new Date(copiedAt + formulaIndex)
                }))
              }
            }
          });
        }
      },
      { "appointment_service.count": servicesToCopy.length, "color_formula.count": copiedFormulaCount }
    );

    return {
      ...(await getAppointmentOutput(ctx.db, targetAppointment.id)),
      copiedServiceCount: servicesToCopy.length
    };
  }),
  addParticipant: stylistTransactionProcedure.input(appointmentParticipantInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input.appointmentId, "client.id": input.clientId }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      }
    });
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      }
    });

    if (!appointment || !client) {
      throw refusal("NOT_FOUND", "Appointment or Client not found.", appointment ? "client_not_found" : "appointment_not_found");
    }

    await ctx.db.appointmentParticipant.upsert({
      where: {
        appointmentId_clientId: {
          appointmentId: appointment.id,
          clientId: client.id
        }
      },
      update: {},
      create: {
        appointmentId: appointment.id,
        clientId: client.id
      }
    });

    return {
      appointmentId: appointment.id,
      clientId: client.id
    };
  }),
  removeParticipant: stylistTransactionProcedure.input(appointmentParticipantInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input.appointmentId, "client.id": input.clientId }));

    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        primaryClient: {
          select: {
            address: true
          }
        },
        participants: {
          orderBy: [{ createdAt: "asc" }, { id: "asc" }]
        }
      }
    });

    if (!appointment) {
      throw refusal("NOT_FOUND", "Appointment not found.", "appointment_not_found");
    }

    // The Clients before the removal.
    annotate(() => ({ "appointment.client_count": appointment.participants.length }));

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw refusal("BAD_REQUEST", "Client must be attached to the Appointment.", "client_not_attached");
    }

    const remainingParticipants = appointment.participants.filter((participant) => participant.clientId !== input.clientId);
    const nextPrimaryParticipant = remainingParticipants[0];

    if (!nextPrimaryParticipant) {
      throw refusal("BAD_REQUEST", "Appointment must keep at least one Client.", "last_client");
    }

    const serviceCount = await ctx.db.appointmentService.count({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });

    annotate(() => ({ "client.appointment_service_count": serviceCount }));

    if (serviceCount > 0) {
      throw refusal("BAD_REQUEST", "Client has Services attached to this Appointment.", "client_has_services");
    }

    const photoCount = await ctx.db.appointmentPhoto.count({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });

    annotate(() => ({ "client.appointment_photo_count": photoCount }));

    if (photoCount > 0) {
      throw refusal("BAD_REQUEST", "Client has Appointment Photos attached to this Appointment.", "client_has_photos");
    }

    const primaryClientChanges = !remainingParticipants.some((participant) => participant.clientId === appointment.primaryClientId);

    annotate(() => ({
      "appointment.is_primary_client_changed": primaryClientChanges,
      "appointment.primary_client_id": primaryClientChanges ? nextPrimaryParticipant.clientId : appointment.primaryClientId
    }));

    // The primary Client is always one of the Appointment's Clients.
    if (primaryClientChanges) {
      await ctx.db.appointment.update({
        where: {
          id: appointment.id
        },
        data: await getPrimaryClientChange(ctx.db, appointment, nextPrimaryParticipant.clientId)
      });
    }

    await ctx.db.appointmentParticipant.deleteMany({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });

    return {
      appointmentId: appointment.id,
      clientId: input.clientId
    };
  }),
  delete: stylistTransactionProcedure.input(appointmentDeleteInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "appointment.id": input.id }));

    // The Appointment Photos go with the Appointment, and their stored files with them.
    const photos = await ctx.db.appointmentPhoto.findMany({
      where: {
        appointment: {
          id: input.id,
          stylistId: ctx.stylist.id
        }
      },
      select: {
        fileKey: true
      }
    });

    const deleted = await ctx.db.appointment.deleteMany({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    annotate(() => ({
      // "not_found": already deleted, such as a delete sent again, or not the Stylist's. The reply is the same.
      "appointment.delete_result": deleted?.count ? "deleted" : "not_found",
      "appointment.photo_count": photos.length,
      "appointment.stored_photo_count": photos.filter((photo) => photo.fileKey).length
    }));

    await markFilesUnused(ctx, photos);

    return {
      id: input.id
    };
  })
});
