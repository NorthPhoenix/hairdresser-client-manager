import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { addedOrder } from "../addedOrder";
import { getClientAddress, getPrimaryClientChange, hasCustomAddress } from "../appointmentLocation";
import type { Db } from "../context";
import { dissolveIfAlone, leaveFamily, storedRole } from "../family";
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
import { loadProfileShareContent, resolveProfileShareOptions } from "../profileShare";
import { buildShareImage } from "../shareImage";
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
  // Who recommended this Client. Not stated: a Client who exists keeps theirs, a new one has none.
  // Null: none (clears it).
  referredById: z.string().nullable().optional(),
  // With an id: the updatedAt of the Client the form was loaded from.
  expectedUpdatedAt: expectedUpdatedAt.optional(),
  // Without an id: see requestKey.
  requestKey: requestKey.optional()
});

const clientDeleteInput = z.object({
  id: z.string()
});

// Two Clients of the Stylist: the one that stays, and the one whose Appointments, Services and
// photos go to it before it is deleted.
const clientMergeInput = z.object({
  keepId: z.string(),
  removeId: z.string()
});

// A Client, for what is read or changed of their Family.
const clientFamilyInput = z.object({
  clientId: z.string()
});

// A role in a Family as the Stylist typed it, such as "сын"; empty for none.
const familyRole = oneLineText(textLimits.familyRole);

// [memberId] joins the Family of [clientId]; see addFamilyMember. [role] is the member's role:
// not stated, a member who is in the Family already keeps theirs.
const addFamilyMemberInput = z.object({
  clientId: z.string(),
  memberId: z.string(),
  role: familyRole.optional()
});

const setFamilyRoleInput = z.object({
  clientId: z.string(),
  role: familyRole
});

// Two Clients of the Stylist who are different people although their phone numbers match.
const clientDistinctInput = z.object({
  clientId: z.string(),
  otherClientId: z.string()
});

const clientAppointmentHistoryInput = z.object({
  id: z.string()
});

const clientProfileShareInput = z.object({
  clientId: z.string()
});

// What the Stylist chose to show of the last completed Appointment, see ProfileShareOptions.
const profileShareOptionsInput = {
  includeColorFormulas: z.boolean().optional(),
  includeAfterPhotos: z.boolean().optional(),
  includeOtherPhotos: z.boolean().optional()
};

const clientProfileShareOptionsInput = z.object({
  clientId: z.string(),
  ...profileShareOptionsInput
});

const clientProfileShareImageInput = z.object({
  clientId: z.string(),
  language: z.enum(["ru", "en"]),
  ...profileShareOptionsInput
});

// The one Profile Share of a Client that is not revoked.
const activeProfileShare = {
  where: {
    revokedAt: null
  },
  orderBy: {
    createdAt: "desc"
  },
  take: 1
} as const;

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

type ProfileShareRow = {
  id: string;
  token: string;
  language: "ru" | "en";
  includeColorFormulas?: boolean;
  includeAfterPhotos?: boolean;
  includeOtherPhotos?: boolean;
  revokedAt?: Date | null;
  createdAt: Date;
};

function toProfileShareOutput(share: ProfileShareRow) {
  return {
    id: share.id,
    token: share.token,
    language: share.language,
    ...resolveProfileShareOptions(share),
    createdAt: share.createdAt.toISOString()
  };
}

// The pairs a Client is in that the Stylist said are different people, from either side.
const distinctPairs = {
  distinctPairsAsFirst: {
    select: {
      secondClientId: true
    }
  },
  distinctPairsAsSecond: {
    select: {
      firstClientId: true
    }
  }
} as const;

function toClientOutput(client: {
  id: string;
  stylistId: string;
  name: string;
  language: "ru" | "en";
  phone: string | null;
  email: string | null;
  address: string | null;
  note: string | null;
  referredById?: string | null;
  referredBy?: { name: string } | null;
  familyId?: string | null;
  familyRole?: string | null;
  createdAt: Date;
  updatedAt: Date;
  profileShares?: ProfileShareRow[];
  distinctPairsAsFirst?: { secondClientId: string }[];
  distinctPairsAsSecond?: { firstClientId: string }[];
}) {
  const activeShare = client.profileShares?.find((share) => !share.revokedAt);
  const distinctFrom = [
    ...(client.distinctPairsAsFirst ?? []).map((pair) => pair.secondClientId),
    ...(client.distinctPairsAsSecond ?? []).map((pair) => pair.firstClientId)
  ];

  return {
    id: client.id,
    stylistId: client.stylistId,
    name: client.name,
    language: client.language,
    phone: client.phone ?? "",
    email: client.email ?? "",
    address: client.address ?? "",
    note: client.note ?? "",
    // Who recommended the Client, by id and by name (null: nobody).
    referredById: client.referredById ?? null,
    referredByName: client.referredById ? client.referredBy?.name ?? null : null,
    // The Family the Client is in (null: none), and their role in it (empty: none).
    familyId: client.familyId ?? null,
    familyRole: client.familyId ? client.familyRole ?? "" : "",
    createdAt: client.createdAt.toISOString(),
    updatedAt: client.updatedAt.toISOString(),
    activeProfileShare: activeShare ? toProfileShareOutput(activeShare) : null,
    // Only the list states it: the other Clients this one is not the same person as, although
    // their phone numbers match. Absent from a saved Client, whose pairs a save does not change.
    ...(client.distinctPairsAsFirst || client.distinctPairsAsSecond ? { distinctFromIds: distinctFrom } : {})
  };
}

/** The Client with the name of whoever recommended them, for the reply to a save. */
async function withReferrer<Row extends { referredById?: string | null }>(db: Db, client: Row) {
  if (!client.referredById) {
    return client;
  }

  const referrer = await db.client.findFirst({
    where: {
      id: client.referredById
    },
    select: {
      name: true
    }
  });

  return {
    ...client,
    referredBy: referrer
  };
}

/**
 * Who a Client may be recommended by: another Client of the same Stylist, never the Client
 * themselves. Null for nobody.
 */
async function checkReferrer(db: Db, stylistId: string, clientId: string | undefined, referredById: string | null) {
  if (!referredById) {
    return;
  }

  if (referredById === clientId) {
    throw new TRPCError({
      code: "BAD_REQUEST",
      message: "A Client cannot be recommended by themselves."
    });
  }

  const referrer = await db.client.findFirst({
    where: {
      id: referredById,
      stylistId
    },
    select: {
      id: true
    }
  });

  if (!referrer) {
    throw new TRPCError({
      code: "NOT_FOUND",
      message: "Client not found."
    });
  }
}

/** A detail of the kept Client, or the removed Client's when the kept one has none. */
function filled(kept: string | null, removed: string | null) {
  return (kept ?? "").trim() ? kept ?? "" : removed ?? "";
}

/** Both Client Notes, the kept Client's first. The same note twice is kept once. */
function mergedNote(kept: string | null, removed: string | null) {
  const first = (kept ?? "").trim();
  const second = (removed ?? "").trim();

  if (!second || first === second) {
    return kept ?? "";
  }

  return first ? `${first}\n\n${second}` : removed ?? "";
}

/**
 * Who recommended the kept Client after a merge: their own referrer, or the removed Client's
 * when they have none. Never one of the two, as nobody recommends themselves.
 */
function mergedReferrer(
  kept: { id: string; referredById?: string | null },
  removed: { id: string; referredById?: string | null }
) {
  const own = kept.referredById && kept.referredById !== removed.id ? kept.referredById : null;
  const taken = removed.referredById && removed.referredById !== kept.id ? removed.referredById : null;

  return own ?? taken;
}

/**
 * What changes on an Appointment whose primary Client is merged into another. A Scheduled one
 * follows the rule for any change of primary Client. One that has happened stays where it was:
 * at home at the removed Client's address, that address is kept, as one of its own.
 */
async function getMergedPrimaryChange(
  db: Db,
  appointment: Parameters<typeof getPrimaryClientChange>[1] & { status: string },
  clientId: string
) {
  if (appointment.status === "scheduled") {
    return getPrimaryClientChange(db, appointment, clientId);
  }

  if (appointment.locationType !== "atHome" || hasCustomAddress(appointment)) {
    return {
      primaryClientId: clientId
    };
  }

  return {
    primaryClientId: clientId,
    locationAddressIsCustom: (appointment.locationAddress ?? "") !== (await getClientAddress(db, clientId))
  };
}

/** Records that two Clients of a Stylist are different people, once per pair. */
async function addDistinctPair(db: Db, stylistId: string, clientId: string, otherClientId: string) {
  const [firstClientId, secondClientId] = clientId < otherClientId ? [clientId, otherClientId] : [otherClientId, clientId];
  const existing = await db.clientDistinctPair.findFirst({
    where: {
      firstClientId,
      secondClientId
    }
  });

  if (!existing) {
    await db.clientDistinctPair.create({
      data: {
        stylistId,
        firstClientId,
        secondClientId
      }
    });
  }
}

/** What the list states of a Client, also with what a save does not answer. */
const listedClient = {
  profileShares: activeProfileShare,
  referredBy: {
    select: {
      name: true
    }
  },
  ...distinctPairs
} as const;

/** The kept Client of a merge as the list states a Client. */
async function loadMergedClient(db: Db, id: string) {
  const client = await db.client.findUniqueOrThrow({
    where: {
      id
    },
    include: listedClient
  });

  return toClientOutput(client);
}

/**
 * The answer to a change of a Family: the Family [clientId] is in now (null: none), and every
 * Client the change touched, [clientId] always among them, as the list states a Client.
 */
async function familyChange(db: Db, stylistId: string, clientId: string, changedIds: string[]) {
  const ids = [...new Set([clientId, ...changedIds])];
  const clients = await db.client.findMany({
    where: {
      id: {
        in: ids
      },
      stylistId
    },
    include: listedClient
  });

  return {
    familyId: clients.find((client) => client.id === clientId)?.familyId ?? null,
    clients: clients.map(toClientOutput)
  };
}

/** A Client of the Stylist with their Family, or NOT_FOUND. */
async function findFamilyClient(db: Db, stylistId: string, id: string) {
  const client = await db.client.findFirst({
    where: {
      id,
      stylistId
    },
    select: {
      id: true,
      familyId: true,
      familyRole: true
    }
  });

  if (!client) {
    throw new TRPCError({
      code: "NOT_FOUND",
      message: "Client not found."
    });
  }

  return client;
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
      include: listedClient
    });

    return clients.map(toClientOutput);
  }),
  // The Family of a Client, every Client in it with their role and their number of visits
  // (Completed Appointments), the Client themselves too, oldest Client first. No Family: no one.
  family: stylistProcedure.input(clientFamilyInput).query(async ({ ctx, input }) => {
    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);

    if (!client.familyId) {
      return {
        familyId: null,
        members: []
      };
    }

    const members = await ctx.db.client.findMany({
      where: {
        stylistId: ctx.stylist.id,
        familyId: client.familyId
      },
      select: {
        id: true,
        name: true,
        familyRole: true
      },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }]
    });
    const visits = await ctx.db.appointmentParticipant.groupBy({
      by: ["clientId"],
      where: {
        clientId: {
          in: members.map((member) => member.id)
        },
        appointment: {
          stylistId: ctx.stylist.id,
          status: "completed"
        }
      },
      _count: {
        _all: true
      }
    });

    return {
      familyId: client.familyId,
      members: members.map((member) => ({
        id: member.id,
        name: member.name,
        role: member.familyRole ?? "",
        visits: visits.find((row) => row.clientId === member.id)?._count._all ?? 0
      }))
    };
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
      // Where it was, as on the Appointment itself: the Client Profile books the next one at the same place.
      locationType: appointment.locationType,
      locationAddress: appointment.locationAddress ?? "",
      customLocationAddress: hasCustomAddress(appointment) ? appointment.locationAddress ?? "" : "",
      participantClientIds: appointment.participants.map((participant) => participant.clientId),
      // The tip for the whole visit, also of a group Appointment, whose Services here are this Client's alone.
      tipCents: appointment.tipCents ?? null,
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

    // Not stated is not the same as none: a save that does not mention it leaves it as it is.
    const referredById = input.referredById === undefined ? undefined : input.referredById || null;

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

        return toClientOutput(await withReferrer(ctx.db, repeatedClient));
      }

      await checkReferrer(ctx.db, ctx.stylist.id, undefined, referredById ?? null);

      const client = await ctx.db.client.create({
        data: {
          ...data,
          ...(referredById ? { referredById } : {}),
          stylistId: ctx.stylist.id,
          requestKey: input.requestKey
        }
      });

      return toClientOutput(await withReferrer(ctx.db, client));
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

    const referrerChanges = referredById !== undefined && (existingClient.referredById ?? null) !== referredById;

    refuseOutdatedSave(
      existingClient,
      input.expectedUpdatedAt,
      (Object.keys(data) as (keyof typeof data)[]).some((field) => (existingClient[field] ?? "") !== data[field]) || referrerChanges
    );

    // A referrer that is already set stays valid without a look; a new one is checked.
    if (referrerChanges) {
      await checkReferrer(ctx.db, ctx.stylist.id, existingClient.id, referredById ?? null);
    }

    const client = await ctx.db.client.update({
      where: {
        id: existingClient.id
      },
      data: {
        ...data,
        ...(referredById !== undefined ? { referredById } : {})
      }
    });

    return toClientOutput(await withReferrer(ctx.db, client));
  }),
  // Under the Stylist lock, so two requests at once cannot both find no active Profile Share and
  // each create one. A request sent again gets the Profile Share the first one created, as it is
  // now: what an existing Profile Share shows is changed with updateProfileShare.
  createProfileShare: stylistTransactionProcedure.input(clientProfileShareOptionsInput).mutation(async ({ ctx, input }) => {
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      },
      include: {
        profileShares: activeProfileShare
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
      return toProfileShareOutput(existingShare);
    }

    const share = await ctx.db.profileShare.create({
      data: {
        clientId: client.id,
        token: createProfileShareToken(),
        language: client.language,
        ...resolveProfileShareOptions(input)
      }
    });

    return toProfileShareOutput(share);
  }),
  // Changes what the Client's Profile Share shows. Its link stays the same. Only the choices that
  // are stated change.
  updateProfileShare: stylistTransactionProcedure.input(clientProfileShareOptionsInput).mutation(async ({ ctx, input }) => {
    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      },
      include: {
        profileShares: activeProfileShare
      }
    });

    if (!client) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    const existingShare = client.profileShares[0];

    if (!existingShare) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Profile Share not found."
      });
    }

    const share = await ctx.db.profileShare.update({
      where: {
        id: existingShare.id
      },
      data: {
        includeColorFormulas: input.includeColorFormulas,
        includeAfterPhotos: input.includeAfterPhotos,
        includeOtherPhotos: input.includeOtherPhotos
      }
    });

    return toProfileShareOutput(share);
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
      },
      include: {
        profileShares: activeProfileShare
      }
    });

    if (!client) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    // A choice that is not stated follows the Client's Profile Share, so that the picture shows
    // what the page shows.
    const content = await loadProfileShareContent(
      ctx,
      {
        ...client,
        stylist: ctx.stylist
      },
      resolveProfileShareOptions(input, client.profileShares[0])
    );

    return {
      svg: await buildShareImage(content, input.language, ctx.readSharePhoto)
    };
  }),
  // Puts [memberId] in one Family with [clientId]: in the Family of [clientId], or else in the
  // one [memberId] is in, or else in a new one. A member who was in another Family leaves it (and
  // a Family that would keep one Client is deleted). The same call sent again changes nothing.
  addFamilyMember: stylistTransactionProcedure.input(addFamilyMemberInput).mutation(async ({ ctx, input }) => {
    if (input.clientId === input.memberId) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "A Client cannot be in a family with themselves."
      });
    }

    const stylistId = ctx.stylist.id;
    const client = await findFamilyClient(ctx.db, stylistId, input.clientId);
    const member = await findFamilyClient(ctx.db, stylistId, input.memberId);
    const role = input.role === undefined ? undefined : storedRole(input.role);
    const changed: string[] = [];

    if (client.familyId && member.familyId === client.familyId) {
      if (role !== undefined && storedRole(member.familyRole) !== role) {
        await ctx.db.client.update({
          where: {
            id: member.id
          },
          data: {
            familyRole: role
          }
        });
        changed.push(member.id);
      }
    } else if (client.familyId) {
      changed.push(...(await leaveFamily(ctx.db, stylistId, member)));
      await ctx.db.client.update({
        where: {
          id: member.id
        },
        data: {
          familyId: client.familyId,
          familyRole: role ?? null
        }
      });
      changed.push(member.id);
    } else if (member.familyId) {
      await ctx.db.client.update({
        where: {
          id: client.id
        },
        data: {
          familyId: member.familyId,
          familyRole: null
        }
      });
      changed.push(client.id);

      if (role !== undefined && storedRole(member.familyRole) !== role) {
        await ctx.db.client.update({
          where: {
            id: member.id
          },
          data: {
            familyRole: role
          }
        });
        changed.push(member.id);
      }
    } else {
      const family = await ctx.db.family.create({
        data: {
          stylistId
        }
      });

      await ctx.db.client.update({
        where: {
          id: client.id
        },
        data: {
          familyId: family.id,
          familyRole: null
        }
      });
      await ctx.db.client.update({
        where: {
          id: member.id
        },
        data: {
          familyId: family.id,
          familyRole: role ?? null
        }
      });
      changed.push(client.id, member.id);
    }

    return familyChange(ctx.db, stylistId, input.clientId, [member.id, ...changed]);
  }),
  // A Client's role in their Family ("сын", "мама"); empty for none.
  setFamilyRole: stylistTransactionProcedure.input(setFamilyRoleInput).mutation(async ({ ctx, input }) => {
    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);

    if (!client.familyId) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Family not found."
      });
    }

    const role = storedRole(input.role);

    if (storedRole(client.familyRole) !== role) {
      await ctx.db.client.update({
        where: {
          id: client.id
        },
        data: {
          familyRole: role
        }
      });
    }

    return familyChange(ctx.db, ctx.stylist.id, client.id, []);
  }),
  // Takes a Client out of their Family. When only one Client would be left in it, the Family is
  // deleted. A Client who is in no Family stays so.
  leaveFamily: stylistTransactionProcedure.input(clientFamilyInput).mutation(async ({ ctx, input }) => {
    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);

    return familyChange(ctx.db, ctx.stylist.id, client.id, await leaveFamily(ctx.db, ctx.stylist.id, client));
  }),
  // The Stylist says two Clients whose phone numbers match are different people: the app stops
  // offering to merge them. Saying it again changes nothing.
  markDistinct: stylistTransactionProcedure.input(clientDistinctInput).mutation(async ({ ctx, input }) => {
    if (input.clientId === input.otherClientId) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "A Client is the same person as themselves."
      });
    }

    const clients = await ctx.db.client.findMany({
      where: {
        id: {
          in: [input.clientId, input.otherClientId]
        },
        stylistId: ctx.stylist.id
      },
      select: {
        id: true
      }
    });

    if (clients.length !== 2) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    await addDistinctPair(ctx.db, ctx.stylist.id, input.clientId, input.otherClientId);

    return {
      clientId: input.clientId,
      otherClientId: input.otherClientId
    };
  }),
  // Makes two Client Profiles of one person one: everything of the removed Client goes to the kept
  // one, which takes the details it does not have, and the removed Client is deleted. All of it or
  // nothing, under the Stylist lock (ADR 0014). The same merge sent again answers with the kept
  // Client as it is now (ADR 0015).
  merge: stylistTransactionProcedure.input(clientMergeInput).mutation(async ({ ctx, input }) => {
    if (input.keepId === input.removeId) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "A Client cannot be merged into themselves."
      });
    }

    const kept = await ctx.db.client.findFirst({
      where: {
        id: input.keepId,
        stylistId: ctx.stylist.id
      }
    });

    if (!kept) {
      throw new TRPCError({
        code: "NOT_FOUND",
        message: "Client not found."
      });
    }

    const removed = await ctx.db.client.findFirst({
      where: {
        id: input.removeId,
        stylistId: ctx.stylist.id
      }
    });

    if (!removed) {
      const done = await ctx.db.clientMerge.findFirst({
        where: {
          stylistId: ctx.stylist.id,
          keptClientId: kept.id,
          removedClientId: input.removeId
        }
      });

      if (!done) {
        throw new TRPCError({
          code: "NOT_FOUND",
          message: "Client not found."
        });
      }

      return {
        client: await loadMergedClient(ctx.db, kept.id),
        removedId: input.removeId,
        appointmentIds: [] as string[]
      };
    }

    const note = mergedNote(kept.note, removed.note);

    if (note.length > textLimits.note) {
      throw new TRPCError({
        code: "BAD_REQUEST",
        message: "Text is too long."
      });
    }

    // The kept Client takes the details they do not have first: an Appointment at the removed
    // Client's home moves to the kept Client's address below, which is then that one.
    await ctx.db.client.update({
      where: {
        id: kept.id
      },
      data: {
        phone: filled(kept.phone, removed.phone),
        email: filled(kept.email, removed.email),
        address: filled(kept.address, removed.address),
        note,
        referredById: mergedReferrer(kept, removed),
        // The kept Client is in the removed Client's Family when they are in none, with that role.
        ...(!kept.familyId && removed.familyId ? { familyId: removed.familyId, familyRole: removed.familyRole } : {}),
        ...(kept.familyId && removed.familyId && !storedRole(kept.familyRole) && removed.familyRole
          ? { familyRole: removed.familyRole }
          : {})
      }
    });

    // One person in two Families makes them one: the removed Client's goes into the kept Client's.
    if (kept.familyId && removed.familyId && kept.familyId !== removed.familyId) {
      await ctx.db.client.updateMany({
        where: {
          stylistId: ctx.stylist.id,
          familyId: removed.familyId
        },
        data: {
          familyId: kept.familyId
        }
      });
    }

    // Everyone the removed Client recommended is now recommended by the kept Client, who
    // is never recommended by themselves. Before the delete, which would clear these links.
    await ctx.db.client.updateMany({
      where: {
        stylistId: ctx.stylist.id,
        referredById: removed.id,
        id: {
          not: kept.id
        }
      },
      data: {
        referredById: kept.id
      }
    });

    // Also Appointments that name the removed Client as primary without listing them.
    const appointments = await ctx.db.appointment.findMany({
      where: {
        stylistId: ctx.stylist.id,
        OR: [
          {
            primaryClientId: removed.id
          },
          {
            participants: {
              some: {
                clientId: removed.id
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
          select: {
            id: true,
            clientId: true
          }
        }
      }
    });

    for (const appointment of appointments) {
      const removedRow = appointment.participants.find((participant) => participant.clientId === removed.id);
      const keptRow = appointment.participants.find((participant) => participant.clientId === kept.id);

      if (removedRow && keptRow) {
        // Both in one Appointment: it has the kept Client once.
        await ctx.db.appointmentParticipant.delete({
          where: {
            id: removedRow.id
          }
        });
      } else if (removedRow) {
        // In the removed Client's place among the Clients of the Appointment.
        await ctx.db.appointmentParticipant.update({
          where: {
            id: removedRow.id
          },
          data: {
            clientId: kept.id
          }
        });
      } else if (!keptRow) {
        await ctx.db.appointmentParticipant.create({
          data: {
            appointmentId: appointment.id,
            clientId: kept.id
          }
        });
      }

      if (appointment.primaryClientId === removed.id) {
        await ctx.db.appointment.update({
          where: {
            id: appointment.id
          },
          data: await getMergedPrimaryChange(ctx.db, appointment, kept.id)
        });
      }
    }

    await ctx.db.appointmentService.updateMany({
      where: {
        clientId: removed.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      },
      data: {
        clientId: kept.id
      }
    });
    await ctx.db.appointmentPhoto.updateMany({
      where: {
        clientId: removed.id,
        appointment: {
          stylistId: ctx.stylist.id
        }
      },
      data: {
        clientId: kept.id
      }
    });

    // A Client has at most one active Profile Share: the kept Client's stays, and the removed
    // Client's link says it is no longer available. When only the removed Client had one, its link
    // goes on working and shows the kept Client. Every link that was revoked stays revoked.
    const keptShare = await ctx.db.profileShare.findFirst({
      where: {
        clientId: kept.id,
        revokedAt: null
      },
      select: {
        id: true
      }
    });

    if (keptShare) {
      await ctx.db.profileShare.updateMany({
        where: {
          clientId: removed.id,
          revokedAt: null
        },
        data: {
          revokedAt: new Date()
        }
      });
    }

    await ctx.db.profileShare.updateMany({
      where: {
        clientId: removed.id
      },
      data: {
        clientId: kept.id
      }
    });

    // Someone the removed Client is not is not the kept Client either.
    const pairs = await ctx.db.clientDistinctPair.findMany({
      where: {
        OR: [
          {
            firstClientId: removed.id
          },
          {
            secondClientId: removed.id
          }
        ]
      }
    });

    await ctx.db.clientDistinctPair.deleteMany({
      where: {
        id: {
          in: pairs.map((pair) => pair.id)
        }
      }
    });

    for (const pair of pairs) {
      const otherId = pair.firstClientId === removed.id ? pair.secondClientId : pair.firstClientId;

      if (otherId !== kept.id) {
        await addDistinctPair(ctx.db, ctx.stylist.id, kept.id, otherId);
      }
    }

    // Merges into the removed Client are merges into the kept one from now on.
    await ctx.db.clientMerge.updateMany({
      where: {
        keptClientId: removed.id
      },
      data: {
        keptClientId: kept.id
      }
    });
    await ctx.db.clientMerge.create({
      data: {
        stylistId: ctx.stylist.id,
        keptClientId: kept.id,
        removedClientId: removed.id
      }
    });
    await ctx.db.client.delete({
      where: {
        id: removed.id
      }
    });
    // A Family the removed Client leaves is never left with one Client, nor with none.
    await dissolveIfAlone(ctx.db, ctx.stylist.id, removed.familyId);

    return {
      client: await loadMergedClient(ctx.db, kept.id),
      removedId: removed.id,
      appointmentIds: appointments.map((appointment) => appointment.id)
    };
  }),
  delete: stylistTransactionProcedure.input(clientDeleteInput).mutation(async ({ ctx, input }) => {
    const deleted = await ctx.db.client.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      },
      select: {
        familyId: true
      }
    });

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
    // The Family of the deleted Client is never left with one Client.
    await dissolveIfAlone(ctx.db, ctx.stylist.id, deleted?.familyId);
    await markFilesUnused(ctx, photos);

    return {
      id: input.id
    };
  })
});
