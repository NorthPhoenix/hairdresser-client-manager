import { annotate as annotateEvent, increment, recordError, traced, type Fields } from "@hcm/telemetry";
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

// What the procedures below tell the request's wide event (docs/observability.md). A Client's record
// is the most personal data of the app, so the event has only ids, counts, lengths, whether a detail
// is there, and the branch a request took: never a name, a phone number, an email, an address, a
// Client Note, a Family role or a formula.

/**
 * Adds the fields `describe` works out to the request's wide event. They are worked out inside,
 * so that a row without a field they read leaves them out of the event instead of failing the
 * request: telemetry never fails a request.
 */
function annotate(describe: () => Fields) {
  try {
    annotateEvent(describe());
  } catch {
    // Left out of the event.
  }
}

/**
 * A refusal on purpose, recorded on the request's wide event with the domain's reason for it, so
 * a search can count each reason. The caller throws it; the reply is the same as before.
 */
function refusal(code: TRPCError["code"], message: string, reason: string) {
  const error = new TRPCError({ code, message });

  recordError(error, { expected: true, reason });
  return error;
}

/** How many rows an `updateMany` or `deleteMany` changed, as the event counts them. */
function countOf(result: unknown) {
  const count = (result as { count?: unknown } | null | undefined)?.count;

  return typeof count === "number" ? count : undefined;
}

/** The details of a Client a save writes, as the event tells them: whether each is there, and lengths. */
function annotateClientDetails(client: {
  name: string;
  language: string;
  phone: string;
  email: string;
  address: string;
  note: string;
}) {
  annotate(() => ({
    "client.language": client.language,
    "client.name_length": client.name.length,
    "client.has_phone": client.phone.trim() !== "",
    "client.has_email": client.email.trim() !== "",
    "client.has_address": client.address.trim() !== "",
    "client.note_length": client.note.length
  }));
}

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

/** How many of the choices of what a Profile Share shows a request states; the rest are kept or default. */
function statedOptionCount(input: Partial<Record<"includeColorFormulas" | "includeAfterPhotos" | "includeOtherPhotos", boolean>>) {
  return [input.includeColorFormulas, input.includeAfterPhotos, input.includeOtherPhotos].filter((value) => typeof value === "boolean").length;
}

/** A choice of what a Profile Share shows, as the event names it: "includeAfterPhotos" is "include_after_photos". */
function optionField(option: string) {
  return option.replace(/([a-z])([A-Z])/g, "$1_$2").toLowerCase();
}

/** What a Profile Share shows after the request, so a search can count each choice. */
function annotateShareOptions(share: ProfileShareRow) {
  annotate(() => {
    const options = resolveProfileShareOptions(share);

    return {
      "profile_share.include_color_formulas": options.includeColorFormulas,
      "profile_share.include_after_photos": options.includeAfterPhotos,
      "profile_share.include_other_photos": options.includeOtherPhotos
    };
  });
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
    throw refusal("BAD_REQUEST", "A Client cannot be recommended by themselves.", "client_referred_by_self");
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
    throw refusal("NOT_FOUND", "Client not found.", "referrer_not_found");
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
  // Added up over the Appointments of one merge: how often each rule applied.
  if (appointment.status === "scheduled") {
    increment("client_merge.primary_scheduled_count");
    return getPrimaryClientChange(db, appointment, clientId);
  }

  if (appointment.locationType !== "atHome" || hasCustomAddress(appointment)) {
    increment("client_merge.primary_past_kept_place_count");
    return {
      primaryClientId: clientId
    };
  }

  increment("client_merge.primary_past_at_home_count");
  return {
    primaryClientId: clientId,
    locationAddressIsCustom: (appointment.locationAddress ?? "") !== (await getClientAddress(db, clientId))
  };
}

/**
 * Records that two Clients of a Stylist are different people, once per pair. Answers whether the
 * pair is new, so that the event can tell a pair recorded from one that was there already.
 */
async function addDistinctPair(db: Db, stylistId: string, clientId: string, otherClientId: string) {
  const [firstClientId, secondClientId] = clientId < otherClientId ? [clientId, otherClientId] : [otherClientId, clientId];
  const existing = await db.clientDistinctPair.findFirst({
    where: {
      firstClientId,
      secondClientId
    }
  });

  if (existing) {
    return false;
  }

  await db.clientDistinctPair.create({
    data: {
      stylistId,
      firstClientId,
      secondClientId
    }
  });

  return true;
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

  annotate(() => ({ "family.reply_client_count": clients.length }));

  return {
    familyId: clients.find((client) => client.id === clientId)?.familyId ?? null,
    clients: clients.map(toClientOutput)
  };
}

/**
 * A Client of the Stylist with their Family, or NOT_FOUND. [reason] tells the event which of the
 * Clients of a request was not found.
 */
async function findFamilyClient(db: Db, stylistId: string, id: string, reason = "client_not_found") {
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
    throw refusal("NOT_FOUND", "Client not found.", reason);
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

    // The size of the Clients View, and how many Clients have what the list shows of them.
    annotate(() => ({
      "client.has_search": Boolean(search),
      "client.search_length": search ? search.length : undefined,
      "client.count": clients.length,
      "client.with_profile_share_count": clients.filter((client) => client.profileShares.length > 0).length,
      "client.with_family_count": clients.filter((client) => client.familyId).length,
      "client.with_referrer_count": clients.filter((client) => client.referredById).length,
      "client.with_distinct_pairs_count": clients.filter(
        (client) => client.distinctPairsAsFirst.length + client.distinctPairsAsSecond.length > 0
      ).length
    }));

    return clients.map(toClientOutput);
  }),
  // The Family of a Client, every Client in it with their role and their number of visits
  // (Completed Appointments), the Client themselves too, oldest Client first. No Family: no one.
  family: stylistProcedure.input(clientFamilyInput).query(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId }));

    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);

    annotate(() => ({ "client.has_family": Boolean(client.familyId), "family.id": client.familyId ?? undefined }));

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

    annotate(() => ({
      "family.member_count": members.length,
      "family.with_role_count": members.filter((member) => storedRole(member.familyRole)).length,
      "family.visit_count": visits.reduce((sum, row) => sum + row._count._all, 0)
    }));

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
  // What the Clients View says of each Client's visits: the start of their last visit (Completed
  // Appointment) and of their next Scheduled Appointment that has not started yet, as the Client
  // Profile counts them. Every Appointment the Client is in counts, as primary Client or not. A
  // Client with neither is left out: they have had no visit and have nothing ahead.
  visitDates: stylistProcedure.query(async ({ ctx }) => {
    const now = new Date();
    const rows = await ctx.db.appointmentParticipant.findMany({
      where: {
        appointment: {
          stylistId: ctx.stylist.id,
          OR: [{ status: "completed" }, { status: "scheduled", startsAt: { gt: now } }]
        }
      },
      select: {
        clientId: true,
        appointment: {
          select: {
            status: true,
            startsAt: true
          }
        }
      }
    });
    const byClient = new Map<string, { last: Date | null; next: Date | null }>();

    for (const { clientId, appointment } of rows) {
      const dates = byClient.get(clientId) ?? { last: null, next: null };

      if (appointment.status === "completed" && (!dates.last || appointment.startsAt > dates.last)) {
        dates.last = appointment.startsAt;
      }
      if (appointment.status === "scheduled" && appointment.startsAt > now && (!dates.next || appointment.startsAt < dates.next)) {
        dates.next = appointment.startsAt;
      }
      byClient.set(clientId, dates);
    }

    // Every row of every visit is read to answer one date per Client: the row count is the cost.
    annotate(() => {
      const dates = [...byClient.values()];

      return {
        "client.visit_row_count": rows.length,
        "client.count": byClient.size,
        "client.with_last_visit_count": dates.filter((date) => date.last).length,
        "client.with_next_appointment_count": dates.filter((date) => date.next).length
      };
    });

    return [...byClient].map(([clientId, dates]) => ({
      clientId,
      lastVisitAt: dates.last?.toISOString() ?? null,
      nextAppointmentAt: dates.next?.toISOString() ?? null
    }));
  }),
  appointmentHistory: stylistProcedure.input(clientAppointmentHistoryInput).query(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.id }));

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
        // and the stored Appointment Photos attached to this Client. Every Service is read, so the
        // sum of the whole visit is known; only this Client's are sent (see below).
        services: {
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

    // The size of a Client Profile's history: what was read, and how much of it is this Client's.
    annotate(() => {
      const ownServices = appointments.flatMap((appointment) => appointment.services.filter((service) => service.clientId === input.id));

      return {
        "client.history_appointment_count": appointments.length,
        "client.history_completed_count": appointments.filter((appointment) => appointment.status === "completed").length,
        "client.history_scheduled_count": appointments.filter((appointment) => appointment.status === "scheduled").length,
        "client.history_group_count": appointments.filter((appointment) => appointment.participants.length > 1).length,
        "client.history_service_read_count": appointments.reduce((sum, appointment) => sum + appointment.services.length, 0),
        "client.history_service_count": ownServices.length,
        "client.history_color_formula_count": ownServices.reduce((sum, service) => sum + service.colorFormulas.length, 0),
        "client.history_photo_count": appointments.reduce((sum, appointment) => sum + appointment.photos.length, 0)
      };
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
      // What the visit's Services come to: the sum of their prices for the whole visit, also of a group
      // Appointment, whose Services here are this Client's alone, and the custom total that replaces it
      // when the Stylist set one (null when none). The history leads with what the visit was charged.
      serviceTotalCents: appointment.services.reduce((sum, service) => sum + service.priceCents, 0),
      finalTotalCentsOverride: appointment.finalTotalCentsOverride ?? null,
      // The tip for the whole visit, also of a group Appointment, whose Services here are this Client's alone.
      tipCents: appointment.tipCents ?? null,
      // The note of the whole visit, so a detail written on it (a guard size, a shade) is there at the next one.
      note: appointment.note ?? "",
      services: appointment.services
        .filter((service) => service.clientId === input.id)
        .map((service) => ({
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

    annotate(() => ({
      "client.id": input.id,
      "client.save_kind": input.id ? "update" : "create",
      "client.language_source": input.language ? "sent" : "stylist",
      "client.has_request_key": Boolean(input.requestKey),
      "client.has_expected_updated_at": Boolean(input.expectedUpdatedAt),
      "client.referred_by_id": referredById ?? undefined
    }));
    annotateClientDetails(data);

    if (!input.id) {
      const repeatedClient = input.requestKey
        ? await ctx.db.client.findFirst({
            where: {
              stylistId: ctx.stylist.id,
              requestKey: input.requestKey
            }
          })
        : null;

      annotate(() => ({ "client.is_repeat": input.requestKey ? Boolean(repeatedClient) : undefined }));

      if (repeatedClient) {
        annotate(() => ({ "client.id": repeatedClient.id, "client.save_result": "repeat" }));
        refuseChangedRepeat(
          repeatedClient,
          {
            ...data,
            language: input.language
          },
          "client"
        );

        return toClientOutput(await withReferrer(ctx.db, repeatedClient));
      }

      annotate(() => ({ "client.referrer_change": referredById ? "set" : "none" }));
      await checkReferrer(ctx.db, ctx.stylist.id, undefined, referredById ?? null);

      const client = await ctx.db.client.create({
        data: {
          ...data,
          ...(referredById ? { referredById } : {}),
          stylistId: ctx.stylist.id,
          requestKey: input.requestKey
        }
      });

      annotate(() => ({ "client.id": client.id, "client.save_result": "created" }));

      return toClientOutput(await withReferrer(ctx.db, client));
    }

    const existingClient = await ctx.db.client.findFirst({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    if (!existingClient) {
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    const referrerChanges = referredById !== undefined && (existingClient.referredById ?? null) !== referredById;
    const changedFields = (Object.keys(data) as (keyof typeof data)[]).filter((field) => (existingClient[field] ?? "") !== data[field]);

    // Which details the save changes, by name only: a no-op save is the same form saved again.
    annotate(() => ({
      "client.changed_fields": [...changedFields, ...(referrerChanges ? ["referrer"] : [])],
      "client.changed_field_count": changedFields.length + (referrerChanges ? 1 : 0),
      "client.referrer_change":
        referredById === undefined
          ? "not_stated"
          : !referrerChanges
            ? "unchanged"
            : referredById === null
              ? "cleared"
              : existingClient.referredById
                ? "changed"
                : "set",
      "client.save_result": changedFields.length > 0 || referrerChanges ? "updated" : "noop"
    }));

    refuseOutdatedSave(existingClient, input.expectedUpdatedAt, changedFields.length > 0 || referrerChanges);

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
    annotate(() => ({ "client.id": input.clientId }));

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
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    const existingShare = client.profileShares[0];

    if (existingShare) {
      // The request sent again, or the app did not know of the active Profile Share: answered with it as it is.
      annotate(() => ({ "profile_share.id": existingShare.id, "profile_share.create_result": "existing" }));
      annotateShareOptions(existingShare);
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

    annotate(() => ({
      "profile_share.id": share.id,
      "profile_share.create_result": "created",
      "profile_share.language": share.language,
      "profile_share.stated_option_count": statedOptionCount(input)
    }));
    annotateShareOptions(share);

    return toProfileShareOutput(share);
  }),
  // Changes what the Client's Profile Share shows. Its link stays the same. Only the choices that
  // are stated change.
  updateProfileShare: stylistTransactionProcedure.input(clientProfileShareOptionsInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId }));

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
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    const existingShare = client.profileShares[0];

    if (!existingShare) {
      throw refusal("NOT_FOUND", "Profile Share not found.", "profile_share_not_found");
    }

    // Which choices the update changes, by name: none is the same choice sent again.
    annotate(() => {
      const before = resolveProfileShareOptions(existingShare);
      const changed = (Object.keys(before) as (keyof typeof before)[]).filter(
        (option) => typeof input[option] === "boolean" && input[option] !== before[option]
      );

      return {
        "profile_share.id": existingShare.id,
        "profile_share.stated_option_count": statedOptionCount(input),
        "profile_share.changed_options": changed.map(optionField),
        "profile_share.changed_option_count": changed.length
      };
    });

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

    annotateShareOptions(share);

    return toProfileShareOutput(share);
  }),
  revokeProfileShare: stylistProcedure.input(clientProfileShareInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId }));

    const client = await ctx.db.client.findFirst({
      where: {
        id: input.clientId,
        stylistId: ctx.stylist.id
      }
    });

    if (!client) {
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    const revoked = await ctx.db.profileShare.updateMany({
      where: {
        clientId: client.id,
        revokedAt: null
      },
      data: {
        revokedAt: new Date()
      }
    });

    // None revoked: the link was revoked already, such as a revoke sent again.
    annotate(() => ({ "profile_share.revoked_count": countOf(revoked) }));

    return {
      clientId: client.id
    };
  }),
  buildProfileShareImage: stylistProcedure.input(clientProfileShareImageInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId, "profile_share.stated_option_count": statedOptionCount(input) }));

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
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    // A Share Image is also made for a Client without an active Profile Share; its choices are then the defaults.
    annotate(() => ({
      "profile_share.id": client.profileShares[0]?.id,
      "share_image.has_profile_share": client.profileShares.length > 0
    }));

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
    annotate(() => ({
      "client.id": input.clientId,
      "family.added_client_id": input.memberId,
      "family.is_role_stated": input.role !== undefined
    }));

    if (input.clientId === input.memberId) {
      throw refusal("BAD_REQUEST", "A Client cannot be in a family with themselves.", "family_with_self");
    }

    const stylistId = ctx.stylist.id;
    const client = await findFamilyClient(ctx.db, stylistId, input.clientId);
    const member = await findFamilyClient(ctx.db, stylistId, input.memberId, "family_member_not_found");
    const role = input.role === undefined ? undefined : storedRole(input.role);
    const changed: string[] = [];

    // Which Family the two end up in: the Client's, the member's, a new one, or the one they share already.
    annotate(() => ({
      "family.add_path":
        client.familyId && member.familyId === client.familyId
          ? "same_family"
          : client.familyId
            ? "joined_client_family"
            : member.familyId
              ? "joined_member_family"
              : "new_family",
      // The member leaves another Family to join the Client's (and that Family may be deleted).
      "family.member_previous_family_id":
        client.familyId && member.familyId && member.familyId !== client.familyId ? member.familyId : undefined,
      "family.is_role_changed": role !== undefined && storedRole(member.familyRole) !== role
    }));

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

    const reply = await familyChange(ctx.db, stylistId, input.clientId, [member.id, ...changed]);

    // After leaveFamily, which names the Family the member left: this is the one they are in now.
    annotate(() => ({
      "family.id": reply.familyId ?? undefined,
      "family.changed_client_count": new Set(changed).size,
      "family.is_noop": changed.length === 0
    }));

    return reply;
  }),
  // A Client's role in their Family ("сын", "мама"); empty for none.
  setFamilyRole: stylistTransactionProcedure.input(setFamilyRoleInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId }));

    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);

    if (!client.familyId) {
      throw refusal("NOT_FOUND", "Family not found.", "family_not_found");
    }

    const role = storedRole(input.role);
    const previousRole = storedRole(client.familyRole);

    // What happened to the role, never the role itself: it is typed by the Stylist.
    annotate(() => ({
      "family.id": client.familyId ?? undefined,
      "family.role_change": previousRole === role ? "unchanged" : role === null ? "cleared" : previousRole === null ? "set" : "changed",
      "family.role_length": role?.length ?? 0
    }));

    if (previousRole !== role) {
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
    annotate(() => ({ "client.id": input.clientId }));

    const client = await findFamilyClient(ctx.db, ctx.stylist.id, input.clientId);
    const changed = await leaveFamily(ctx.db, ctx.stylist.id, client);

    // leaveFamily names the Family and whether it was deleted; this is whether there was one to leave.
    annotate(() => ({
      "family.leave_result": client.familyId ? "left" : "not_in_family",
      "family.changed_client_count": changed.length
    }));

    return familyChange(ctx.db, ctx.stylist.id, client.id, changed);
  }),
  // The Stylist says two Clients whose phone numbers match are different people: the app stops
  // offering to merge them. Saying it again changes nothing.
  markDistinct: stylistTransactionProcedure.input(clientDistinctInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.clientId, "client.other_client_id": input.otherClientId }));

    if (input.clientId === input.otherClientId) {
      throw refusal("BAD_REQUEST", "A Client is the same person as themselves.", "distinct_from_self");
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
      annotate(() => ({ "client.found_count": clients.length }));
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
    }

    const created = await addDistinctPair(ctx.db, ctx.stylist.id, input.clientId, input.otherClientId);

    // "existing": said again, which changes nothing.
    annotate(() => ({ "client.distinct_pair_result": created ? "created" : "existing" }));

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
    annotate(() => ({ "client.id": input.keepId, "client_merge.removed_client_id": input.removeId }));

    if (input.keepId === input.removeId) {
      throw refusal("BAD_REQUEST", "A Client cannot be merged into themselves.", "merge_into_self");
    }

    const kept = await ctx.db.client.findFirst({
      where: {
        id: input.keepId,
        stylistId: ctx.stylist.id
      }
    });

    if (!kept) {
      throw refusal("NOT_FOUND", "Client not found.", "client_not_found");
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
        throw refusal("NOT_FOUND", "Client not found.", "removed_client_not_found");
      }

      // The same merge sent again (ADR 0015): answered with the kept Client as it is now.
      annotate(() => ({ "client_merge.result": "repeat" }));

      return {
        client: await loadMergedClient(ctx.db, kept.id),
        removedId: input.removeId,
        appointmentIds: [] as string[]
      };
    }

    const note = mergedNote(kept.note, removed.note);

    // What the kept Client takes of the removed one, by name of the detail only.
    annotate(() => {
      const taken = (["phone", "email", "address"] as const).filter(
        (field) => !(kept[field] ?? "").trim() && (removed[field] ?? "").trim()
      );
      const referrer = mergedReferrer(kept, removed);

      return {
        "client_merge.taken_fields": taken,
        "client_merge.is_note_combined": (kept.note ?? "").trim() !== "" && note !== (kept.note ?? ""),
        "client_merge.is_note_taken": !(kept.note ?? "").trim() && note !== (kept.note ?? ""),
        "client.note_length": note.length,
        "client_merge.referrer_source":
          referrer === null ? "none" : referrer === kept.referredById ? "kept" : "removed",
        "client_merge.family_merge":
          !kept.familyId && !removed.familyId
            ? "none"
            : !removed.familyId
              ? "kept_family"
              : !kept.familyId
                ? "joined_removed_family"
                : kept.familyId === removed.familyId
                  ? "same_family"
                  : "families_combined",
        "client_merge.kept_family_id": kept.familyId ?? undefined,
        "client_merge.removed_family_id": removed.familyId ?? undefined
      };
    });

    if (note.length > textLimits.note) {
      throw refusal("BAD_REQUEST", "Text is too long.", "merged_note_too_long");
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
      const moved = await ctx.db.client.updateMany({
        where: {
          stylistId: ctx.stylist.id,
          familyId: removed.familyId
        },
        data: {
          familyId: kept.familyId
        }
      });

      annotate(() => ({ "client_merge.family_moved_client_count": countOf(moved) }));
    }

    // Everyone the removed Client recommended is now recommended by the kept Client, who
    // is never recommended by themselves. Before the delete, which would clear these links.
    const referrals = await ctx.db.client.updateMany({
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

    annotate(() => ({ "client_merge.referral_moved_count": countOf(referrals) }));

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

    annotate(() => ({
      "client_merge.appointment_count": appointments.length,
      "client_merge.appointment_ids": appointments.slice(0, 20).map((appointment) => appointment.id)
    }));

    // One Appointment at a time: its own queries for each, so the time grows with the removed Client's visits.
    await traced("client_merge.move_appointments", async () => {
      const participants = { removed: 0, moved: 0, added: 0 };

      for (const appointment of appointments) {
        const removedRow = appointment.participants.find((participant) => participant.clientId === removed.id);
        const keptRow = appointment.participants.find((participant) => participant.clientId === kept.id);

        if (removedRow && keptRow) {
          // Both in one Appointment: it has the kept Client once.
          participants.removed += 1;
          await ctx.db.appointmentParticipant.delete({
            where: {
              id: removedRow.id
            }
          });
        } else if (removedRow) {
          // In the removed Client's place among the Clients of the Appointment.
          participants.moved += 1;
          await ctx.db.appointmentParticipant.update({
            where: {
              id: removedRow.id
            },
            data: {
              clientId: kept.id
            }
          });
        } else if (!keptRow) {
          participants.added += 1;
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

      annotate(() => ({
        "client_merge.shared_appointment_count": participants.removed,
        "client_merge.participant_moved_count": participants.moved,
        "client_merge.participant_added_count": participants.added,
        "client_merge.primary_changed_count": appointments.filter((appointment) => appointment.primaryClientId === removed.id).length
      }));
    });

    const services = await ctx.db.appointmentService.updateMany({
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
    const photos = await ctx.db.appointmentPhoto.updateMany({
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

    annotate(() => ({
      "client_merge.service_moved_count": countOf(services),
      "client_merge.photo_moved_count": countOf(photos)
    }));

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

    const revokedShares = keptShare
      ? await ctx.db.profileShare.updateMany({
          where: {
            clientId: removed.id,
            revokedAt: null
          },
          data: {
            revokedAt: new Date()
          }
        })
      : null;

    const movedShares = await ctx.db.profileShare.updateMany({
      where: {
        clientId: removed.id
      },
      data: {
        clientId: kept.id
      }
    });

    annotate(() => ({
      "client_merge.kept_has_profile_share": Boolean(keptShare),
      "client_merge.profile_share_revoked_count": revokedShares ? countOf(revokedShares) : 0,
      "client_merge.profile_share_moved_count": countOf(movedShares)
    }));

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

    let pairsCreated = 0;

    for (const pair of pairs) {
      const otherId = pair.firstClientId === removed.id ? pair.secondClientId : pair.firstClientId;

      if (otherId !== kept.id && (await addDistinctPair(ctx.db, ctx.stylist.id, kept.id, otherId))) {
        pairsCreated += 1;
      }
    }

    // A pair of the two merged Clients is dropped; one the kept Client had already is not made twice.
    annotate(() => ({
      "client_merge.distinct_pair_count": pairs.length,
      "client_merge.distinct_pair_moved_count": pairsCreated
    }));

    // Merges into the removed Client are merges into the kept one from now on.
    const earlierMerges = await ctx.db.clientMerge.updateMany({
      where: {
        keptClientId: removed.id
      },
      data: {
        keptClientId: kept.id
      }
    });

    annotate(() => ({ "client_merge.earlier_merge_count": countOf(earlierMerges) }));

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

    annotate(() => ({ "client_merge.result": "merged" }));

    return {
      client: await loadMergedClient(ctx.db, kept.id),
      removedId: removed.id,
      appointmentIds: appointments.map((appointment) => appointment.id)
    };
  }),
  delete: stylistTransactionProcedure.input(clientDeleteInput).mutation(async ({ ctx, input }) => {
    annotate(() => ({ "client.id": input.id }));

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

    annotate(() => ({ "client.appointment_count": appointments.length }));

    // One Appointment at a time: its own queries for each, so the time grows with the Client's visits.
    await traced("client.delete_appointments", async () => {
      const outcomes = { deleted: 0, left: 0, primaryChanged: 0 };

      for (const appointment of appointments) {
        const remainingParticipants = appointment.participants.filter((participant) => participant.clientId !== input.id);
        const nextPrimaryParticipant = remainingParticipants[0];

        if (!nextPrimaryParticipant) {
          outcomes.deleted += 1;
          await ctx.db.appointment.delete({
            where: {
              id: appointment.id
            }
          });
          continue;
        }

        outcomes.left += 1;

        if (appointment.primaryClientId === input.id) {
          outcomes.primaryChanged += 1;
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

      // What became of the Client's Appointments: deleted along with the Client when they were its
      // only Client, else kept for the others, with another primary Client where they were primary.
      annotate(() => ({
        "client.appointment_deleted_count": outcomes.deleted,
        "client.appointment_kept_count": outcomes.left,
        "client.appointment_primary_changed_count": outcomes.primaryChanged
      }));
    });

    const removed = await ctx.db.client.deleteMany({
      where: {
        id: input.id,
        stylistId: ctx.stylist.id
      }
    });

    // "not_found": already deleted (a delete sent again) or not the Stylist's; the reply is the same.
    annotate(() => ({
      "client.delete_result": deleted ? "deleted" : "not_found",
      "client.deleted_count": countOf(removed),
      "client.photo_count": photos.length,
      "client.has_family": Boolean(deleted?.familyId)
    }));

    // The Family of the deleted Client is never left with one Client.
    await dissolveIfAlone(ctx.db, ctx.stylist.id, deleted?.familyId);
    await markFilesUnused(ctx, photos);

    return {
      id: input.id
    };
  })
});
