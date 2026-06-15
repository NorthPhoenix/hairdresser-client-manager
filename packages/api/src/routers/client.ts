import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const clientListInput = z.object({
  search: z.string().trim().optional()
}).optional();

const clientSaveInput = z.object({
  id: z.string().optional(),
  name: z.string().trim().min(1),
  language: z.enum(["ru", "en"]).optional(),
  phone: z.string().trim().optional(),
  email: z.string().trim().optional(),
  address: z.string().trim().optional(),
  note: z.string().trim().optional()
});

const clientDeleteInput = z.object({
  id: z.string()
});

const clientAppointmentHistoryInput = z.object({
  id: z.string()
});

function toClientOutput(client: {
  id: string;
  stylistId: string;
  name: string;
  language: "ru" | "en";
  phone: string | null;
  email: string | null;
  address: string | null;
  note: string | null;
  createdAt: Date;
  updatedAt: Date;
}) {
  return {
    id: client.id,
    stylistId: client.stylistId,
    name: client.name,
    language: client.language,
    phone: client.phone ?? "",
    email: client.email ?? "",
    address: client.address ?? "",
    note: client.note ?? "",
    createdAt: client.createdAt.toISOString(),
    updatedAt: client.updatedAt.toISOString()
  };
}

export const clientRouter = createTRPCRouter({
  list: stylistProcedure.input(clientListInput).query(async ({ ctx, input }) => {
    const search = input?.search?.trim();
    const clients = await ctx.db.client.findMany({
      where: {
        stylistId: ctx.stylist.id,
        ...(search
          ? {
              OR: [
                {
                  name: {
                    contains: search,
                    mode: "insensitive"
                  }
                },
                {
                  phone: {
                    contains: search
                  }
                }
              ]
            }
          : {})
      },
      orderBy: {
        updatedAt: "desc"
      }
    });

    return clients.map(toClientOutput);
  }),
  appointmentHistory: stylistProcedure.input(clientAppointmentHistoryInput).query(async ({ ctx, input }) => {
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        participants: {
          some: {
            clientId: input.id
          }
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
        startsAt: "desc"
      }
    });

    return appointments.map((appointment) => ({
      id: appointment.id,
      primaryClientId: appointment.primaryClientId,
      primaryClientName: appointment.primaryClient.name,
      startsAt: appointment.startsAt.toISOString(),
      status: appointment.status,
      participantClientIds: appointment.participants.map((participant) => participant.clientId)
    }));
  }),
  save: stylistProcedure.input(clientSaveInput).mutation(async ({ ctx, input }) => {
    const data = {
      name: input.name,
      language: input.language ?? ctx.stylist.language,
      phone: input.phone ?? "",
      email: input.email ?? "",
      address: input.address ?? "",
      note: input.note ?? ""
    };

    if (!input.id) {
      const client = await ctx.db.client.create({
        data: {
          ...data,
          stylistId: ctx.stylist.id
        }
      });

      return toClientOutput(client);
    }

    const existingClient = await ctx.db.client.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    if (!existingClient) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    const client = await ctx.db.client.update({
      where: {
        id: existingClient.id
      },
      data
    });

    return toClientOutput(client);
  }),
  delete: stylistProcedure.input(clientDeleteInput).mutation(async ({ ctx, input }) => {
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        participants: {
          some: {
            clientId: input.id
          }
        }
      },
      include: {
        participants: {
          orderBy: {
            createdAt: "asc"
          }
        }
      }
    });

    for (const appointment of appointments) {
      const remainingParticipants = appointment.participants.filter((participant) => participant.clientId !== input.id);

      if (remainingParticipants.length === 0) {
        await ctx.db.appointment.delete({
          where: {
            id: appointment.id
          }
        });
        continue;
      }

      if (appointment.primaryClientId === input.id) {
        await ctx.db.appointment.update({
          where: {
            id: appointment.id
          },
          data: {
            primaryClientId: remainingParticipants[0]?.clientId
          }
        });
      }

      await ctx.db.appointmentParticipant.deleteMany({
        where: {
          appointmentId: appointment.id,
          clientId: input.id
        }
      });
    }

    await ctx.db.client.deleteMany({
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
