import { TRPCError } from "@trpc/server";
import { z } from "zod";
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
  note: z.string().trim().optional()
});

const appointmentParticipantInput = z.object({
  appointmentId: z.string(),
  clientId: z.string()
});

const appointmentDeleteInput = z.object({
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
  primaryClient: {
    id: string;
    name: string;
    address: string | null;
  };
  participants?: {
    client: {
      id: string;
      name: string;
      address: string | null;
    };
  }[];
}) {
  const participants =
    appointment.participants?.map((participant) => ({
      clientId: participant.client.id,
      name: participant.client.name,
      address: participant.client.address ?? "",
      isPrimary: participant.client.id === appointment.primaryClientId
    })) ?? [
      {
        clientId: appointment.primaryClient.id,
        name: appointment.primaryClient.name,
        address: appointment.primaryClient.address ?? "",
        isPrimary: true
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
    mapUrl: appointment.locationAddress
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(appointment.locationAddress)}`
      : null
  };
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
        }
      },
      orderBy: {
        startsAt: "asc"
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
        ...(input.note !== undefined ? { note: input.note } : {})
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
