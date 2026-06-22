import { TRPCError } from "@trpc/server";
import { z } from "zod";
import type { TRPCContext } from "../context";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const dateRangeInput = z.object({
  start: z.string().datetime(),
  end: z.string().datetime()
});

const appointmentCreateInput = z.object({
  primaryClientId: z.string().optional(),
  additionalClientIds: z.array(z.string()).default([]),
  inlineClientName: z.string().trim().min(1).optional(),
  startsAt: z.string().datetime(),
  endsAt: z.string().datetime().optional(),
  locationType: z.enum(["inSalon", "atHome"]).default("inSalon"),
  customLocationAddress: z.string().trim().optional()
}).refine((input) => input.primaryClientId || input.inlineClientName, {
  message: "Choose a primary Client or create one inline.",
  path: ["primaryClientId"]
});

const appointmentUpdateInput = z.object({
  id: z.string(),
  primaryClientId: z.string().optional(),
  status: z.enum(["scheduled", "completed", "canceled", "noShow"]).optional(),
  note: z.string().trim().optional(),
  finalTotalCentsOverride: z.number().int().min(0).nullable().optional()
});

const appointmentParticipantInput = z.object({
  appointmentId: z.string(),
  clientId: z.string()
});

const appointmentServiceInput = z.object({
  appointmentId: z.string(),
  clientId: z.string(),
  menuItemId: z.string().optional(),
  name: z.string().trim().min(1).optional(),
  priceCents: z.number().int().min(0).optional(),
  note: z.string().trim().optional()
});

const appointmentServiceUpdateInput = z.object({
  id: z.string(),
  name: z.string().trim().min(1),
  priceCents: z.number().int().min(0),
  note: z.string().trim().optional()
});

const appointmentServiceDeleteInput = z.object({
  id: z.string()
});

const colorFormulaInput = z.object({
  appointmentServiceId: z.string(),
  formula: z.string().trim().min(1),
  placement: z.string().trim().optional()
});

const colorFormulaUpdateInput = z.object({
  id: z.string(),
  formula: z.string().trim().min(1),
  placement: z.string().trim().optional()
});

const colorFormulaDeleteInput = z.object({
  id: z.string()
});

const appointmentCopyServicesInput = z.object({
  targetAppointmentId: z.string(),
  sourceAppointmentId: z.string().optional()
});

const appointmentDeleteInput = z.object({
  id: z.string()
});

const appointmentPhotoInput = z.object({
  appointmentId: z.string(),
  clientId: z.string(),
  category: z.enum(["before", "after", "other"]),
  fileKey: z.string().trim().optional(),
  url: z.string().trim().url().optional(),
  thumbnailUrl: z.string().trim().url().optional(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
  status: z.enum(["pendingUpload", "stored", "failed"]).default("stored"),
  uploadError: z.string().trim().optional()
});

const appointmentPhotoUpdateInput = z.object({
  id: z.string(),
  category: z.enum(["before", "after", "other"]).optional(),
  status: z.enum(["pendingUpload", "stored", "failed"]).optional(),
  uploadError: z.string().trim().nullable().optional()
});

const appointmentPhotoDeleteInput = z.object({
  id: z.string()
});

function addMinutes(date: Date, minutes: number) {
  return new Date(date.getTime() + minutes * 60 * 1000);
}

function getConflictWindow(startsAt: Date, endsAt: Date | null) {
  return {
    start: startsAt,
    end: endsAt ?? addMinutes(startsAt, 30)
  };
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
  const services = appointment.services ?? [];
  const serviceTotalCents = services.reduce((total, service) => total + service.priceCents, 0);
  const clientSubtotals = services.reduce<Record<string, number>>((subtotals, service) => {
    subtotals[service.clientId] = (subtotals[service.clientId] ?? 0) + service.priceCents;
    return subtotals;
  }, {});
  const participants =
    appointment.participants?.map((participant) => ({
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
      colorFormulas: service.colorFormulas?.map((formula) => ({
        id: formula.id,
        appointmentServiceId: formula.appointmentServiceId,
        formula: formula.formula,
        placement: formula.placement ?? "",
        createdAt: formula.createdAt.toISOString()
      })) ?? []
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

async function getAppointmentOutput(ctx: Pick<TRPCContext, "db">, appointmentId: string) {
  const appointment = await ctx.db.appointment.findUniqueOrThrow({
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
      orderBy: {
        startsAt: "asc"
      }
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
      orderBy: {
        startsAt: "asc"
      }
    });

    return appointments.map(toAppointmentOutput);
  }),
  completedSources: stylistProcedure.query(async ({ ctx }) => {
    const appointments = await ctx.db.appointment.findMany({
      where: {
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
      orderBy: {
        startsAt: "desc"
      }
    });

    return appointments.map(toAppointmentOutput);
  }),
  create: stylistProcedure.input(appointmentCreateInput).mutation(async ({ ctx, input }) => {
    const startsAt = new Date(input.startsAt);
    const endsAt = input.endsAt ? new Date(input.endsAt) : null;

    if (endsAt && endsAt <= startsAt) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "End time must be after start time."
      });
    }

    const additionalClientIds = [...new Set(input.additionalClientIds.filter((clientId) => clientId !== input.primaryClientId))];
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
        : ctx.stylist.salonAddress ?? "";
    const window = getConflictWindow(startsAt, endsAt);
    const possibleConflicts = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        status: "scheduled",
        startsAt: {
          lt: window.end
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
        }
      },
      orderBy: {
        startsAt: "asc"
      }
    });
    const conflicts = possibleConflicts.filter((appointment) => {
      const conflictWindow = getConflictWindow(appointment.startsAt, appointment.endsAt);

      return conflictWindow.end > window.start;
    });
    const appointment = await ctx.db.appointment.create({
      data: {
        stylistId: ctx.stylist.id,
        primaryClientId: primaryClient.id,
        startsAt,
        endsAt,
        locationType: input.locationType,
        locationAddress,
        note: "",
        finalTotalCentsOverride: null,
        participants: {
          create: [primaryClient.id, ...additionalClientIds].map((clientId) => ({
            clientId
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
  update: stylistProcedure.input(appointmentUpdateInput).mutation(async ({ ctx, input }) => {
    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    if (!appointment) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Appointment not found."
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

    const updatedAppointment = await ctx.db.appointment.update({
      where: {
        id: appointment.id
      },
      data: {
        ...(input.primaryClientId ? { primaryClientId: input.primaryClientId } : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.note !== undefined ? { note: input.note } : {}),
        ...(input.finalTotalCentsOverride !== undefined ? { finalTotalCentsOverride: input.finalTotalCentsOverride } : {})
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
  addService: stylistProcedure.input(appointmentServiceInput).mutation(async ({ ctx, input }) => {
    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true
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

    await ctx.db.appointmentService.create({
      data: {
        appointmentId: appointment.id,
        clientId: input.clientId,
        menuItemId: menuItem?.id ?? null,
        name: input.name ?? menuItem?.name ?? "",
        priceCents: input.priceCents ?? menuItem?.defaultPriceCents ?? 0,
        note: input.note ?? ""
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
  updateService: stylistProcedure.input(appointmentServiceUpdateInput).mutation(async ({ ctx, input }) => {
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
  addPhoto: stylistProcedure.input(appointmentPhotoInput).mutation(async ({ ctx, input }) => {
    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true
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
        message: "Photo Client must be attached to the Appointment."
      });
    }

    if (input.status === "stored" && (!input.fileKey || !input.url)) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Stored Appointment Photos require UploadThing file metadata."
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
        uploadError: input.uploadError ?? null
      }
    });

    return getAppointmentOutput(ctx, appointment.id);
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

    return getAppointmentOutput(ctx, photo.appointmentId);
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

    return getAppointmentOutput(ctx, photo.appointmentId);
  }),
  addColorFormula: stylistProcedure.input(colorFormulaInput).mutation(async ({ ctx, input }) => {
    const service = await ctx.db.appointmentService.findFirst({
      where: {
        id: input.appointmentServiceId,
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

    await ctx.db.colorFormula.create({
      data: {
        appointmentServiceId: service.id,
        formula: input.formula,
        placement: input.placement ?? ""
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
  copyServices: stylistProcedure.input(appointmentCopyServicesInput).mutation(async ({ ctx, input }) => {
    const targetAppointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.targetAppointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: true
      }
    });

    if (!targetAppointment) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Target Appointment not found."
      });
    }

    const sourceAppointment = input.sourceAppointmentId
      ? await ctx.db.appointment.findFirst({
          where: {
            id: input.sourceAppointmentId,
            stylistId: ctx.stylist.id,
            status: "completed"
          },
          include: {
            participants: true,
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
        })
      : await ctx.db.appointment.findFirst({
          where: {
            stylistId: ctx.stylist.id,
            status: "completed",
            id: {
              not: targetAppointment.id
            }
          },
          include: {
            participants: true,
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
          orderBy: {
            startsAt: "desc"
          }
        });

    if (!sourceAppointment) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Completed source Appointment not found."
      });
    }

    const sourceClientIds = new Set(sourceAppointment.participants.map((participant) => participant.clientId));
    const targetClientIds = new Set(targetAppointment.participants.map((participant) => participant.clientId));
    const sharedClientIds = new Set(
      [...sourceClientIds].filter((clientId) => targetClientIds.has(clientId))
    );
    const servicesToCopy = sourceAppointment.services.filter((service) => sharedClientIds.has(service.clientId));

    for (const service of servicesToCopy) {
      await ctx.db.appointmentService.create({
        data: {
          appointmentId: targetAppointment.id,
          clientId: service.clientId,
          menuItemId: service.menuItemId,
          name: service.name,
          priceCents: service.priceCents,
          note: service.note ?? "",
          colorFormulas: {
            create: service.colorFormulas.map((formula) => ({
              formula: formula.formula,
              placement: formula.placement ?? ""
            }))
          }
        }
      });
    }

    const updatedAppointment = await ctx.db.appointment.findUniqueOrThrow({
      where: {
        id: targetAppointment.id
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
      }
    });

    return toAppointmentOutput(updatedAppointment);
  }),
  addParticipant: stylistProcedure.input(appointmentParticipantInput).mutation(async ({ ctx, input }) => {
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
  removeParticipant: stylistProcedure.input(appointmentParticipantInput).mutation(async ({ ctx, input }) => {
    const appointment = await ctx.db.appointment.findFirst({
      where: {
        id: input.appointmentId,
        stylistId: ctx.stylist.id
      },
      include: {
        participants: {
          orderBy: {
            createdAt: "asc"
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

    if (appointment.participants.length <= 1) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Appointment must keep at least one Client."
      });
    }

    const remainingParticipant = appointment.participants.find((participant) => participant.clientId !== input.clientId);
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

    if (appointment.primaryClientId === input.clientId && remainingParticipant) {
      await ctx.db.appointment.update({
        where: {
          id: appointment.id
        },
        data: {
          primaryClientId: remainingParticipant.clientId
        }
      });
    }

    await ctx.db.appointmentParticipant.deleteMany({
      where: {
        appointmentId: appointment.id,
        clientId: input.clientId
      }
    });
    await ctx.db.appointmentPhoto.deleteMany({
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
