import type { Prisma } from "@hcm/db";
import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { inAddedOrder } from "../addedOrder";
import { getClientAddress, getPrimaryClientChange, getSalonAddress, hasCustomAddress } from "../appointmentLocation";
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
import { isStoredFileUrl } from "../photoStorage";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

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
  startsAt: z.string().datetime().optional(),
  // null clears the end time.
  endsAt: z.string().datetime().nullable().optional(),
  locationType: z.enum(["inSalon", "atHome"]).optional(),
  // At home only: an empty string means the primary Client's address.
  customLocationAddress: text(textLimits.address).optional()
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

const appointmentDeleteInput = z.object({
  id: z.string()
});

const maxFileKeyLength = 200;
const maxUrlLength = 2000;
const maxUploadErrorLength = 500;
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
  status: z.enum(["pendingUpload", "stored", "failed"]).default("stored"),
  uploadError: text(maxUploadErrorLength).optional(),
  requestKey: requestKey.optional()
});

const appointmentPhotoUpdateInput = z.object({
  id: z.string(),
  category: z.enum(["before", "after", "other"]).optional(),
  status: z.enum(["pendingUpload", "stored", "failed"]).optional(),
  uploadError: text(maxUploadErrorLength).nullable().optional()
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

  if (raisedByCents > 0 && totalCents + raisedByCents > maxAmountCents) {
    throw new TRPCError({
      code: "BAD_REQUEST",
      message: "Appointment total is too large."
    });
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
    uploadError: string | null;
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
      uploadError: photo.uploadError ?? "",
      createdAt: photo.createdAt.toISOString()
    })) ?? [],
    serviceTotalCents,
    finalTotalCents: appointment.finalTotalCentsOverride ?? serviceTotalCents,
    finalTotalCentsOverride: appointment.finalTotalCentsOverride,
    mapUrl: appointment.locationAddress
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(appointment.locationAddress)}`
      : null
  };
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

  return toAppointmentOutput(appointment);
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

  return possibleConflicts.filter((appointment) => {
    const conflictWindow = getConflictWindow(appointment.startsAt, appointment.endsAt);

    return conflictWindow.end > window.start;
  });
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

    return appointments.map(toAppointmentOutput);
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

    return appointments.map(toAppointmentOutput);
  }),
  // What Appointment Copying can copy from, newest first. For a target: only the Appointments
  // with something to copy into it, the first one being the source that copyServices takes when
  // none is named.
  completedSources: stylistProcedure.input(completedSourcesInput).query(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Target Appointment not found."
      });
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

    if (endsAt && endsAt <= startsAt) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "End time must be after start time."
      });
    }

    const additionalClientIds = [...new Set(input.additionalClientIds.filter((clientId) => clientId !== input.primaryClientId))];
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

    if (repeatedAppointment) {
      const atCustomAddress = input.locationType === "atHome" && Boolean(input.customLocationAddress);

      refuseChangedRepeat(
        {
          ...repeatedAppointment,
          inlineClientName: repeatedAppointment.primaryClient.name,
          clientIds: repeatedAppointment.participants.map((participant) => participant.clientId).sort().join(),
          customLocationAddress: repeatedAppointment.locationAddressIsCustom ? repeatedAppointment.locationAddress : ""
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
        }
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
        conflicts: repeatedConflicts.map(toAppointmentOutput)
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Primary Client not found."
      });
    }

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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "One or more group Clients were not found."
      });
    }

    const locationAddress =
      input.locationType === "atHome"
        ? input.customLocationAddress || primaryClient.address || ""
        : await getSalonAddress(ctx.db, ctx.stylist.id);
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
        locationAddressIsCustom: input.locationType === "atHome" && Boolean(input.customLocationAddress),
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

    return {
      appointment: toAppointmentOutput(appointment),
      conflicts: conflicts.map(toAppointmentOutput)
    };
  }),
  update: stylistTransactionProcedure.input(appointmentUpdateInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment not found."
      });
    }

    const startsAt = input.startsAt ? new Date(input.startsAt) : appointment.startsAt;
    const endsAt = input.endsAt === undefined ? appointment.endsAt : input.endsAt ? new Date(input.endsAt) : null;

    if ((input.startsAt || input.endsAt) && endsAt && endsAt <= startsAt) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "End time must be after start time."
      });
    }

    if (input.primaryClientId) {
      const participant = await ctx.db.appointmentParticipant.findFirst({
        where: {
          appointmentId: appointment.id,
          clientId: input.primaryClientId
        }
      });

      if (!participant) {
        throw new TRPCError({
          code: "BAD_REQUEST",
          message: "Primary Client must be attached to the Appointment."
        });
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

    if (locationType === "inSalon") {
      // A Scheduled in-salon Appointment shows the salon's current address; one with an outcome
      // keeps the address it had.
      if (locationTypeChanges || becomesScheduled || savedAsInSalon) {
        location = {
          locationAddress: await getSalonAddress(ctx.db, ctx.stylist.id),
          locationAddressIsCustom: false
        };
      }
    } else if (input.customLocationAddress) {
      location = {
        locationAddress: input.customLocationAddress,
        locationAddressIsCustom: true
      };
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
    }

    const updatedAppointment = await ctx.db.appointment.update({
      where: {
        id: appointment.id
      },
      data: {
        ...(input.primaryClientId ? { primaryClientId: input.primaryClientId } : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.note !== undefined ? { note: input.note } : {}),
        ...(input.finalTotalCentsOverride !== undefined ? { finalTotalCentsOverride: input.finalTotalCentsOverride } : {}),
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

    return {
      ...toAppointmentOutput(updatedAppointment),
      conflicts: conflicts.map(toAppointmentOutput)
    };
  }),
  addService: stylistTransactionProcedure.input(appointmentServiceInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment not found."
      });
    }

    const repeatedService = input.requestKey
      ? appointment.services.find((service) => service.requestKey === input.requestKey)
      : undefined;

    if (repeatedService) {
      // A name or price left out came from the Service Menu Item: there is nothing to compare.
      refuseChangedRepeat(repeatedService, {
        clientId: input.clientId,
        menuItemId: input.menuItemId ?? null,
        name: input.name,
        priceCents: input.priceCents,
        note: input.note ?? ""
      });

      return getAppointmentOutput(ctx.db, appointment.id);
    }

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Service Client must be attached to the Appointment."
      });
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Service Menu Item not found."
      });
    }

    if (!menuItem && !input.name) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Service name is required."
      });
    }

    const priceCents = input.priceCents ?? menuItem?.defaultPriceCents ?? 0;

    refuseTotalAboveMaximum(appointment.services, priceCents);

    await ctx.db.appointmentService.create({
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

    return toAppointmentOutput(updatedAppointment);
  }),
  updateService: stylistTransactionProcedure.input(appointmentServiceUpdateInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment Service not found."
      });
    }

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

    return toAppointmentOutput(updatedAppointment);
  }),
  deleteService: stylistProcedure.input(appointmentServiceDeleteInput).mutation(async ({ ctx, input }) => {
    const service = await ctx.db.appointmentService.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!service) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment Service not found."
      });
    }

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

    return toAppointmentOutput(updatedAppointment);
  }),
  addPhoto: stylistTransactionProcedure.input(appointmentPhotoInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment not found."
      });
    }

    // A request that is sent again, recognised by its key or by the stored file: one file is one
    // Appointment Photo.
    const repeatedPhoto = appointment.photos.find(
      (photo) =>
        (input.requestKey && photo.requestKey === input.requestKey) ||
        (input.fileKey && photo.fileKey === input.fileKey)
    );

    if (repeatedPhoto) {
      // Not the file: a picture that is uploaded again for the same key is a new file each time.
      refuseChangedRepeat(repeatedPhoto, {
        clientId: input.clientId,
        category: input.category
      });

      return getAppointmentOutput(ctx.db, appointment.id);
    }

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Photo Client must be attached to the Appointment."
      });
    }

    if (input.status === "stored" && (!input.fileKey || !input.url)) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Stored Appointment Photos require UploadThing file metadata."
      });
    }

    // The app loads both URLs as images: each has to be the stored file this photo names.
    if ([input.url, input.thumbnailUrl].some((url) => url && !isStoredFileUrl(url, input.fileKey ?? "", ctx.photoOrigins))) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Appointment Photo URLs must point at UploadThing storage."
      });
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
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "This file already belongs to another Appointment Photo."
      });
    }

    await ctx.db.appointmentPhoto.create({
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
        uploadError: input.uploadError ?? null,
        requestKey: input.requestKey
      }
    });

    return getAppointmentOutput(ctx.db, appointment.id);
  }),
  updatePhoto: stylistProcedure.input(appointmentPhotoUpdateInput).mutation(async ({ ctx, input }) => {
    const photo = await ctx.db.appointmentPhoto.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!photo) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment Photo not found."
      });
    }

    await ctx.db.appointmentPhoto.update({
      where: {
        id: photo.id
      },
      data: {
        ...(input.category ? { category: input.category } : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.uploadError !== undefined ? { uploadError: input.uploadError } : {})
      }
    });

    return getAppointmentOutput(ctx.db, photo.appointmentId);
  }),
  deletePhoto: stylistProcedure.input(appointmentPhotoDeleteInput).mutation(async ({ ctx, input }) => {
    const photo = await ctx.db.appointmentPhoto.findFirst({
      where: {
        id: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      }
    });

    if (!photo) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment Photo not found."
      });
    }

    await ctx.db.appointmentPhoto.delete({
      where: {
        id: photo.id
      }
    });

    return getAppointmentOutput(ctx.db, photo.appointmentId);
  }),
  addColorFormula: stylistTransactionProcedure.input(colorFormulaInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment Service not found."
      });
    }

    const repeatedFormula = input.requestKey
      ? service.colorFormulas.find((formula) => formula.requestKey === input.requestKey)
      : undefined;

    if (repeatedFormula) {
      refuseChangedRepeat(repeatedFormula, {
        formula: input.formula,
        placement: input.placement ?? ""
      });

      return getAppointmentOutput(ctx.db, service.appointmentId);
    }

    await ctx.db.colorFormula.create({
      data: {
        appointmentServiceId: service.id,
        formula: input.formula,
        placement: input.placement ?? "",
        requestKey: input.requestKey
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

    return toAppointmentOutput(updatedAppointment);
  }),
  updateColorFormula: stylistProcedure.input(colorFormulaUpdateInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Color Formula not found."
      });
    }

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

    return toAppointmentOutput(updatedAppointment);
  }),
  deleteColorFormula: stylistProcedure.input(colorFormulaDeleteInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Color Formula not found."
      });
    }

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

    return toAppointmentOutput(updatedAppointment);
  }),
  copyServices: stylistTransactionProcedure.input(appointmentCopyServicesInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Target Appointment not found."
      });
    }

    const repeatedServiceCount = input.requestKey
      ? targetAppointment.services.filter((service) => service.requestKey === input.requestKey).length
      : 0;

    if (repeatedServiceCount > 0) {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: input.sourceAppointmentId ? "Source Appointment not found." : "Completed source Appointment not found."
      });
    }

    if (sourceAppointment.status !== "completed") {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Source Appointment is no longer Completed."
      });
    }

    const servicesToCopy = inAddedOrder(getServicesToCopy(sourceAppointment, targetAppointment));

    if (servicesToCopy.length === 0) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Source Appointment has no Services to copy."
      });
    }

    refuseTotalAboveMaximum(
      targetAppointment.services,
      servicesToCopy.reduce((total, service) => total + service.priceCents, 0)
    );

    // Rows written together would share a timestamp, and the timestamp is their order. The copies
    // are dated one millisecond apart instead, so they keep the order of the source for good.
    // Writing them takes longer than the milliseconds they span, so what is added next comes after.
    const copiedAt = Date.now();

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

    return {
      ...(await getAppointmentOutput(ctx.db, targetAppointment.id)),
      copiedServiceCount: servicesToCopy.length
    };
  }),
  addParticipant: stylistTransactionProcedure.input(appointmentParticipantInput).mutation(async ({ ctx, input }) => {
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment or Client not found."
      });
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
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment not found."
      });
    }

    if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Client must be attached to the Appointment."
      });
    }

    const remainingParticipants = appointment.participants.filter((participant) => participant.clientId !== input.clientId);
    const nextPrimaryParticipant = remainingParticipants[0];

    if (!nextPrimaryParticipant) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Appointment must keep at least one Client."
      });
    }

    const serviceCount = await ctx.db.appointmentService.count({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });

    if (serviceCount > 0) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Client has Services attached to this Appointment."
      });
    }

    const photoCount = await ctx.db.appointmentPhoto.count({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });

    if (photoCount > 0) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Client has Appointment Photos attached to this Appointment."
      });
    }

    // The primary Client is always one of the Appointment's Clients.
    if (!remainingParticipants.some((participant) => participant.clientId === appointment.primaryClientId)) {
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
  delete: stylistProcedure.input(appointmentDeleteInput).mutation(async ({ ctx, input }) => {
    await ctx.db.appointment.deleteMany({
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
