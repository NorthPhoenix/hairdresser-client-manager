import type { SupportedLocale } from "@hcm/shared";
import { addedOrder } from "./addedOrder";
import type { Db } from "./context";
import { isInPhotoStorage } from "./photoStorage";

/** What the Stylist chose to show of the last completed Appointment. */
export type ProfileShareOptions = {
  includeColorFormulas: boolean;
  includeAfterPhotos: boolean;
  /** The before and the other photos. */
  includeOtherPhotos: boolean;
};

export const defaultProfileShareOptions: ProfileShareOptions = {
  includeColorFormulas: true,
  includeAfterPhotos: true,
  includeOtherPhotos: false
};

/**
 * The choices stated by `sources`, an earlier source before a later one; what none of them states
 * is the default.
 */
export function resolveProfileShareOptions(
  ...sources: (Partial<Record<keyof ProfileShareOptions, boolean | null>> | null | undefined)[]
): ProfileShareOptions {
  const choice = (key: keyof ProfileShareOptions) =>
    sources.map((source) => source?.[key]).find((value) => typeof value === "boolean") ?? defaultProfileShareOptions[key];

  return {
    includeColorFormulas: choice("includeColorFormulas"),
    includeAfterPhotos: choice("includeAfterPhotos"),
    includeOtherPhotos: choice("includeOtherPhotos")
  };
}

export type SharedAppointment = {
  startsAt: Date;
  endsAt: Date | null;
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
};

export type SharedPhoto = {
  url: string;
  category: "before" | "after" | "other";
};

/**
 * Everything a Profile Share page and a Share Image show, and nothing else of the Client Profile:
 * both are made from this, so they cannot disagree. See CONTEXT.md, Profile Share.
 */
export type ProfileShareContent = {
  clientName: string;
  /** The name on the Stylist's account, when it has one. Never the email address. */
  stylistName: string | null;
  salonAddress: string | null;
  /** The Stylist's timezone: every time is shown in it. */
  timezone: string;
  upcomingAppointments: SharedAppointment[];
  lastCompletedAppointment:
    | (SharedAppointment & {
        services: {
          name: string;
          /** Empty when the Stylist left the Color Formulas out. */
          colorFormulas: { formula: string; placement: string | null }[];
        }[];
        /** The photos the Stylist included, after photos first. */
        photos: SharedPhoto[];
      })
    | null;
  includesColorFormulas: boolean;
};

type ProfileShareSources = {
  db: Db;
  photoOrigins: string[];
  accountName: (clerkId: string) => Promise<string | null>;
};

type SharedClient = {
  id: string;
  name: string;
  stylist: {
    id: string;
    clerkId: string;
    timezone: string;
    salonAddress: string | null;
  };
};

const photoCategoryOrder = ["after", "before", "other"] as const;

/**
 * Loads what a Profile Share or a Share Image of `client` shows: all future Appointments and the
 * last completed one, of a group Appointment only this Client's Services and photos.
 */
export async function loadProfileShareContent(
  { db, photoOrigins, accountName }: ProfileShareSources,
  client: SharedClient,
  options: ProfileShareOptions
): Promise<ProfileShareContent> {
  const ofClient = {
    stylistId: client.stylist.id,
    participants: {
      some: {
        clientId: client.id
      }
    }
  };
  const photoCategories = photoCategoryOrder.filter((category) =>
    category === "after" ? options.includeAfterPhotos : options.includeOtherPhotos
  );
  const [stylistName, upcomingAppointments, lastCompletedAppointment] = await Promise.all([
    // The account's name comes from the sign-in provider. A share is shown without it rather than
    // not at all.
    accountName(client.stylist.clerkId).catch(() => null),
    db.appointment.findMany({
      where: {
        ...ofClient,
        status: "scheduled",
        startsAt: {
          gte: new Date()
        }
      },
      orderBy: {
        startsAt: "asc"
      }
    }),
    db.appointment.findFirst({
      where: {
        ...ofClient,
        status: "completed"
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
        },
        photos: {
          where: {
            clientId: client.id,
            status: "stored",
            category: {
              in: [...photoCategories]
            }
          },
          orderBy: addedOrder
        }
      },
      orderBy: {
        startsAt: "desc"
      }
    })
  ]);

  return {
    clientName: client.name,
    stylistName: stylistName?.replace(/\s+/g, " ").trim() || null,
    salonAddress: client.stylist.salonAddress?.trim() || null,
    timezone: client.stylist.timezone,
    upcomingAppointments: upcomingAppointments.map(toSharedAppointment),
    lastCompletedAppointment: lastCompletedAppointment && {
      ...toSharedAppointment(lastCompletedAppointment),
      services: lastCompletedAppointment.services.map((service) => ({
        name: service.name,
        colorFormulas: options.includeColorFormulas
          ? service.colorFormulas.map(({ formula, placement }) => ({ formula, placement }))
          : []
      })),
      // A photo is shown from this server's photo storage and from nowhere else: the page gives
      // its address to the Client's browser, and the Share Image makes this server fetch it.
      photos: photoCategories.flatMap((category) =>
        lastCompletedAppointment.photos.flatMap((photo) =>
          photo.category === category && photo.url && isInPhotoStorage(photo.url, photoOrigins)
            ? [{ url: photo.url, category }]
            : []
        )
      )
    },
    includesColorFormulas: options.includeColorFormulas
  };
}

function toSharedAppointment({ startsAt, endsAt, locationType, locationAddress }: SharedAppointment): SharedAppointment {
  return { startsAt, endsAt, locationType, locationAddress };
}

/**
 * The date and time of an Appointment as the Stylist entered them: in the Stylist's timezone, not
 * in the timezone of the server, and written the way of the language.
 */
export function formatAppointmentTime(locale: SupportedLocale, timezone: string, appointment: SharedAppointment): string {
  const dateLocale = locale === "ru" ? "ru-RU" : "en-US";
  const format = (timeZone: string) =>
    [
      appointment.startsAt.toLocaleString(dateLocale, {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone
      }),
      appointment.endsAt?.toLocaleTimeString(dateLocale, {
        timeStyle: "short",
        timeZone
      })
    ]
      .filter(Boolean)
      .join(" - ");

  try {
    return format(timezone);
  } catch {
    // A timezone stored before timezones were checked can be one this server does not know.
    return format("UTC");
  }
}
