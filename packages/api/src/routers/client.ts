import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { addedOrder } from "../addedOrder";
import { getPrimaryClientChange } from "../appointmentLocation";
import {
  emailText,
  expectedUpdatedAt,
  nameText,
  oneLineText,
  refuseChangedRepeat,
  refuseOutdatedSave,
  requestKey,
  text,
  textLimits
} from "../input";
import { markFilesUnused } from "../photoStorage";
import { buildShareImageSvg } from "../shareImage";
import { createTRPCRouter, stylistProcedure, stylistTransactionProcedure } from "../trpc";

const clientListInput = z.object({
  search: text(textLimits.name).optional()
}).optional();

const clientSaveInput = z.object({
  id: z.string().optional(),
  name: nameText,
  language: z.enum(["ru", "en"]).optional(),
  phone: oneLineText(textLimits.phone).optional(),
  email: emailText.optional(),
  address: text(textLimits.address).optional(),
  note: text(textLimits.note).optional(),
  // With an id: the updatedAt of the Client the form was loaded from.
  expectedUpdatedAt: expectedUpdatedAt.optional(),
  // Without an id: see requestKey.
  requestKey: requestKey.optional()
});

const clientDeleteInput = z.object({
  id: z.string()
});

const clientAppointmentHistoryInput = z.object({
  id: z.string()
});

const clientProfileShareInput = z.object({
  clientId: z.string()
});

const clientProfileShareImageInput = z.object({
  clientId: z.string(),
  language: z.enum(["ru", "en"])
});

function createProfileShareToken(): string {
  const bytes = new Uint8Array(32);
  const webCrypto = (globalThis as unknown as {
    crypto: {
      getRandomValues: (array: Uint8Array) => Uint8Array;
    };
  }).crypto;

  webCrypto.getRandomValues(bytes);

  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

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
  profileShares?: {
    id: string;
    token: string;
    language: "ru" | "en";
    revokedAt: Date | null;
    createdAt: Date;
  }[];
}) {
  const activeProfileShare = client.profileShares?.find((share) => !share.revokedAt);

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
    updatedAt: client.updatedAt.toISOString(),
    activeProfileShare: activeProfileShare
      ? {
          id: activeProfileShare.id,
          token: activeProfileShare.token,
          language: activeProfileShare.language,
          createdAt: activeProfileShare.createdAt.toISOString()
        }
      : null
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
      },
      include: {
        profileShares: {
          where: {
            revokedAt: null
          },
          orderBy: {
            createdAt: "desc"
          },
          take: 1
        }
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
        },
        // A Client Profile shows only this Client's part of a group Appointment: the Services
        // and the stored Appointment Photos attached to this Client.
        services: {
          where: {
            clientId: input.id
          },
          include: {
            colorFormulas: {
              orderBy: addedOrder
            }
          },
          orderBy: addedOrder
        },
        photos: {
          where: {
            clientId: input.id,
            status: "stored"
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
      participantClientIds: appointment.participants.map((participant) => participant.clientId),
      services: appointment.services.map((service) => ({
        id: service.id,
        clientId: service.clientId,
        menuItemId: service.menuItemId,
        name: service.name,
        priceCents: service.priceCents,
        note: service.note ?? "",
        colorFormulas: service.colorFormulas.map((formula) => ({
          id: formula.id,
          formula: formula.formula,
          placement: formula.placement ?? ""
        }))
      })),
      photos: appointment.photos.map((photo) => ({
        id: photo.id,
        clientId: photo.clientId,
        category: photo.category,
        url: photo.url ?? "",
        thumbnailUrl: photo.thumbnailUrl ?? "",
        width: photo.width,
        height: photo.height,
        createdAt: photo.createdAt.toISOString()
      }))
    }));
  }),
  save: stylistTransactionProcedure.input(clientSaveInput).mutation(async ({ ctx, input }) => {
    const data = {
      name: input.name,
      language: input.language ?? ctx.stylist.language,
      phone: input.phone ?? "",
      email: input.email ?? "",
      address: input.address ?? "",
      note: input.note ?? ""
    };

    if (!input.id) {
      const repeatedClient = input.requestKey
        ? await ctx.db.client.findFirst({
            where: {
              stylistId: ctx.stylist.id,
              requestKey: input.requestKey
            }
          })
        : null;

      if (repeatedClient) {
        refuseChangedRepeat(repeatedClient, {
          ...data,
          language: input.language
        });

        return toClientOutput(repeatedClient);
      }

      const client = await ctx.db.client.create({
        data: {
          ...data,
          stylistId: ctx.stylist.id,
          requestKey: input.requestKey
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

    refuseOutdatedSave(
      existingClient,
      input.expectedUpdatedAt,
      (Object.keys(data) as (keyof typeof data)[]).some((field) => (existingClient[field] ?? "") !== data[field])
    );

    const client = await ctx.db.client.update({
      where: {
        id: existingClient.id
      },
      data
    });

    return toClientOutput(client);
  }),
  // Under the Stylist lock, so two requests at once cannot both find no active Profile Share and
  // each create one. A request sent again gets the Profile Share the first one created.
  createProfileShare: stylistTransactionProcedure.input(clientProfileShareInput).mutation(async ({ ctx, input }) => {
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      },
      include: {
        profileShares: {
          where: {
            revokedAt: null
          },
          orderBy: {
            createdAt: "desc"
          },
          take: 1
        }
      }
    });

    if (!client) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    const existingShare = client.profileShares[0];

    if (existingShare) {
      return {
        id: existingShare.id,
        token: existingShare.token,
        language: existingShare.language,
        createdAt: existingShare.createdAt.toISOString()
      };
    }

    const share = await ctx.db.profileShare.create({
      data: {
        clientId: client.id,
        token: createProfileShareToken(),
        language: client.language
      }
    });

    return {
      id: share.id,
      token: share.token,
      language: share.language,
      createdAt: share.createdAt.toISOString()
    };
  }),
  revokeProfileShare: stylistProcedure.input(clientProfileShareInput).mutation(async ({ ctx, input }) => {
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      }
    });

    if (!client) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    await ctx.db.profileShare.updateMany({
      where: {
        clientId: client.id,
        revokedAt: null
      },
      data: {
        revokedAt: new Date()
      }
    });

    return {
      clientId: client.id
    };
  }),
  buildProfileShareImage: stylistProcedure.input(clientProfileShareImageInput).mutation(async ({ ctx, input }) => {
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      }
    });

    if (!client) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    const nextAppointment = await ctx.db.appointment.findFirst({
      where: {
        stylistId: ctx.stylist.id,
        status: "scheduled",
        startsAt: {
          gte: new Date()
        },
        participants: {
          some: {
            clientId: client.id
          }
        }
      },
      orderBy: {
        startsAt: "asc"
      }
    });
    const lastCompletedAppointment = await ctx.db.appointment.findFirst({
      where: {
        stylistId: ctx.stylist.id,
        status: "completed",
        participants: {
          some: {
            clientId: client.id
          }
        }
      },
      include: {
        services: {
          where: {
            clientId: client.id
          },
          include: {
            colorFormulas: {
              orderBy: addedOrder
            }
          },
          orderBy: addedOrder
        }
      },
      orderBy: {
        startsAt: "desc"
      }
    });

    return {
      svg: buildShareImageSvg({
        clientName: client.name,
        locale: input.language,
        nextAppointment,
        lastCompletedAppointment,
        services: lastCompletedAppointment?.services ?? []
      })
    };
  }),
  delete: stylistTransactionProcedure.input(clientDeleteInput).mutation(async ({ ctx, input }) => {
    // The Client's Appointment Photos go with the Client, and their stored files with them. An
    // Appointment that is deleted below has no other Client, so it has no other Client's photos.
    const photos = await ctx.db.appointmentPhoto.findMany({
      where: {
        clientId: input.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      },
      select: {
        fileKey: true
      }
    });

    // Also Appointments that name the Client as primary without listing them as a Client: the
    // primary Client relation would otherwise block the delete.
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        OR: [
          {
            primaryClientId: input.id
          },
          {
            participants: {
              some: {
                clientId: input.id
              }
            }
          }
        ]
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

    for (const appointment of appointments) {
      const remainingParticipants = appointment.participants.filter((participant) => participant.clientId !== input.id);
      const nextPrimaryParticipant = remainingParticipants[0];

      if (!nextPrimaryParticipant) {
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
          data: await getPrimaryClientChange(ctx.db, appointment, nextPrimaryParticipant.clientId)
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
    await markFilesUnused(ctx, photos);

    return {
      id: input.id
    };
  })
});
