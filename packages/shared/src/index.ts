export type SupportedLocale = "ru" | "en";

export const defaultLocale: SupportedLocale = "ru";
export const supportedLocales: SupportedLocale[] = ["ru", "en"];

export const themeTokens = {
  colors: {
    ink: "#17130f",
    muted: "#6d6259",
    paper: "#f7f1e8",
    surface: "#fffaf3",
    accent: "#7d2f1b"
  },
  spacing: {
    field: "12px",
    panel: "24px"
  },
  borderRadius: {
    control: "6px",
    panel: "8px"
  },
  fontFamily: {
    body: ['"Inter"', "Arial", "sans-serif"],
    display: ["Georgia", '"Times New Roman"', "serif"]
  }
} as const;

export function normalizeLocale(locale: string | undefined): SupportedLocale {
  if (locale?.toLowerCase().startsWith("en")) {
    return "en";
  }

  return defaultLocale;
}

// Copy for the client-facing web surfaces: Profile Share pages and Share Images, which are
// written in the Client's language. The Android app keeps its own copy in Android string
// resources (apps/android/app/src/main/res/values and values-ru). A `{name}` in a text is filled
// in by `t`.
const messages = {
  ru: {
    appointmentInSalon: "В салоне",
    appointmentAtHome: "На дому",
    profileShareTitle: "Профиль клиента",
    profileShareStylist: "Мастер: {name}",
    profileShareSalon: "Салон: {address}",
    profileSharePlaceholder: "Здесь открываются профили клиентов по ссылке от мастера.",
    profileShareUpcomingTitle: "Будущие записи",
    profileShareLastCompletedTitle: "Последняя завершённая запись",
    profileShareNoUpcoming: "Будущих записей нет.",
    profileShareNoCompleted: "Завершённых записей пока нет.",
    profileShareServicesTitle: "Услуги и формулы",
    profileShareServicesOnlyTitle: "Услуги",
    profileShareNoServices: "Для этой записи услуги не указаны.",
    profileSharePhotosTitle: "Фото",
    profileSharePhotoBefore: "До",
    profileSharePhotoAfter: "После",
    // Share Image only: what a list or the photos of the picture leave out.
    profileShareMore: "и ещё {count}",
    profileSharePhotosNotShown: "Фото, которых нет на этой картинке: {count}",
    switchToRussian: "Русский",
    switchToEnglish: "English"
  },
  en: {
    appointmentInSalon: "In salon",
    appointmentAtHome: "At home",
    profileShareTitle: "Client Profile",
    profileShareStylist: "Stylist: {name}",
    profileShareSalon: "Salon: {address}",
    profileSharePlaceholder: "Client pages open here from a link shared by the Stylist.",
    profileShareUpcomingTitle: "Upcoming Appointments",
    profileShareLastCompletedTitle: "Last completed Appointment",
    profileShareNoUpcoming: "No upcoming Appointments.",
    profileShareNoCompleted: "No completed Appointments yet.",
    profileShareServicesTitle: "Services and formulas",
    profileShareServicesOnlyTitle: "Services",
    profileShareNoServices: "No Services were recorded for this Appointment.",
    profileSharePhotosTitle: "Photos",
    profileSharePhotoBefore: "Before",
    profileSharePhotoAfter: "After",
    profileShareMore: "and {count} more",
    profileSharePhotosNotShown: "Photos not shown in this picture: {count}",
    switchToRussian: "Русский",
    switchToEnglish: "English"
  }
} as const;

export type MessageKey = keyof (typeof messages)["ru"];

export const messageKeys = Object.keys(messages.ru) as MessageKey[];

export function t(locale: SupportedLocale, key: MessageKey, values: Record<string, string | number> = {}): string {
  return Object.entries(values).reduce<string>(
    // A function, so that a `$` in a value stays a `$`.
    (text, [name, value]) => text.replaceAll(`{${name}}`, () => String(value)),
    messages[locale][key]
  );
}

export function getMissingLocalizationKeys(): Record<SupportedLocale, MessageKey[]> {
  return supportedLocales.reduce(
    (missingKeys, locale) => ({
      ...missingKeys,
      [locale]: messageKeys.filter((key) => !messages[locale][key]?.trim())
    }),
    {
      ru: [],
      en: []
    } as Record<SupportedLocale, MessageKey[]>
  );
}

export type ClientReminderMessageInput = {
  locale: SupportedLocale;
  appointmentTime: string;
  location: string;
};

export function buildClientReminderMessage(input: ClientReminderMessageInput): string {
  if (input.locale === "en") {
    return `Reminder: your appointment is scheduled for ${input.appointmentTime}. Location: ${input.location}.`;
  }

  return `Напоминание: ваша запись назначена на ${input.appointmentTime}. Адрес: ${input.location}.`;
}

export function buildSmsComposerUrl(phone: string, body: string): string {
  const recipient = phone.trim().replace(/[^\d+]/g, "");

  return `sms:${recipient}?body=${encodeURIComponent(body)}`;
}

export type ImportedContactFields = {
  name?: string;
  phone?: string;
  email?: string;
  address?: string;
};

export type ImportedDeviceContact = {
  fullName?: string | null;
  givenName?: string | null;
  familyName?: string | null;
  phones?: { number?: string | null }[] | null;
  emails?: { address?: string | null; email?: string | null }[] | null;
  addresses?: {
    street?: string | null;
    city?: string | null;
    state?: string | null;
    postcode?: string | null;
    region?: string | null;
    country?: string | null;
  }[] | null;
};

type ImportableClientFields = {
  name: string;
  phone: string;
  email: string;
  address: string;
};

function cleanImportedValue(value: string | null | undefined): string | undefined {
  const trimmedValue = value?.trim();
  return trimmedValue ? trimmedValue : undefined;
}

export function normalizeImportedContact(contact: ImportedDeviceContact): ImportedContactFields {
  const name =
    cleanImportedValue(contact.fullName) ??
    cleanImportedValue([contact.givenName, contact.familyName].map((part) => part?.trim()).filter(Boolean).join(" "));
  const phone = contact.phones?.map((phoneNumber) => cleanImportedValue(phoneNumber.number)).find(Boolean);
  const email = contact.emails
    ?.map((emailAddress) => cleanImportedValue(emailAddress.address ?? emailAddress.email))
    .find(Boolean);
  const address = contact.addresses
    ?.map((contactAddress) =>
      [
        contactAddress.street,
        contactAddress.city,
        contactAddress.state ?? contactAddress.region,
        contactAddress.postcode,
        contactAddress.country
      ]
        .map(cleanImportedValue)
        .filter(Boolean)
        .join(", ")
    )
    .map(cleanImportedValue)
    .find(Boolean);

  return {
    ...(name ? { name } : {}),
    ...(phone ? { phone } : {}),
    ...(email ? { email } : {}),
    ...(address ? { address } : {})
  };
}

export function hasImportedContactFields(contact: ImportedContactFields): boolean {
  return Boolean(contact.name || contact.phone || contact.email || contact.address);
}

export function mergeImportedContactIntoClient<TClient extends ImportableClientFields>(
  client: TClient,
  importedContact: ImportedContactFields
): TClient {
  return {
    ...client,
    ...(importedContact.name ? { name: importedContact.name } : {}),
    ...(importedContact.phone ? { phone: importedContact.phone } : {}),
    ...(importedContact.email ? { email: importedContact.email } : {}),
    ...(importedContact.address ? { address: importedContact.address } : {})
  };
}
