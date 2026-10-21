import { TRPCError } from "@trpc/server";
import { z } from "zod";

// Building blocks for procedure inputs. A refused value is a BAD_REQUEST whose first issue carries
// one of the fixed messages below, which the app recognises by their exact text.

/**
 * The largest amount of money the API stores (9,999,999.99): a Service price, a Service Menu Item
 * price, a final total override, and the Services total of one Appointment. Amounts are 32-bit
 * integers in the database and in the app, so every amount and every sum of them stays far below
 * that limit.
 */
export const maxAmountCents = 999_999_999;

/** Longest text of each kind, counted like `String.length` after the text is trimmed. */
export const textLimits = {
  // Client name, Service name, Service Menu Item name.
  name: 100,
  phone: 40,
  email: 254,
  // Client address, salon address, custom Appointment address.
  address: 300,
  // Client Note, Appointment note, Service note.
  note: 5000,
  formula: 2000,
  placement: 200,
  // A Client's role in their Family, such as "сын" or "бабушка".
  familyRole: 40,
  // No IANA timezone name comes near this.
  timezone: 64
} as const;

const tooLong = "Text is too long.";
// The most that is cleaned before it is counted: far more than a pasted name with its line breaks.
const maxUncleanedLength = 10_000;

// Written with escapes: some of these characters are invisible in an editor.
const lineBreaks = new RegExp("[\\t\\n\\v\\f\\r\\u0085\\u2028\\u2029]+");
const controlCharacter = /\p{Cc}/gu;
const visibleCharacter = /[^\s\p{Cc}\p{Cf}\p{Z}]/u;
const emailPattern = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;
// Intl also takes UTC offsets such as "+03:00", which are not timezone names.
const timezoneName = /^[A-Za-z][A-Za-z0-9_+-]*(\/[A-Za-z0-9_+-]+)*$/;

// A pasted line break or tab becomes one space, together with the spaces around it; other control
// characters are invisible and go. Every step reads the value once: a pattern that looks for the
// spaces before a line break takes minutes on a megabyte of spaces.
export function toOneLine(value: string) {
  return value
    .split(lineBreaks)
    .map((line) => line.trim())
    .filter(Boolean)
    .join(" ")
    .replace(controlCharacter, "")
    .trim();
}

function isTimezone(value: string) {
  if (!timezoneName.test(value)) {
    return false;
  }

  try {
    const known = new Intl.DateTimeFormat("en-US", { timeZone: value }).resolvedOptions().timeZone;

    // Intl ignores the case of a name; the app's time library does not.
    return known === value || known.toLowerCase() !== value.toLowerCase();
  } catch {
    return false;
  }
}

/** Text that may have several lines. */
export function text(maxLength: number) {
  return z.string().trim().max(maxLength, tooLong);
}

/**
 * Text that is shown on one line, cleaned of line breaks and control characters. Its length is
 * counted after the cleaning, and a value far over every limit is refused without being cleaned.
 */
export function oneLineText(maxLength: number) {
  return z
    .string()
    .max(maxUncleanedLength, { error: tooLong, abort: true })
    .overwrite(toOneLine)
    .max(maxLength, tooLong);
}

/** A display name: one line with at least one visible character. */
export const nameText = oneLineText(textLimits.name)
  .min(1)
  .refine((value) => visibleCharacter.test(value), "Name is required.");

/** Empty, or one address with a domain: `name@example.com`. */
export const emailText = text(textLimits.email).refine(
  (value) => value === "" || emailPattern.test(value),
  "Email is not valid."
);

export const amountCents = z.number().int().min(0).max(maxAmountCents, "Amount is too large.");

/** The name of an IANA timezone this server knows, such as `America/Chicago`. */
export const timezoneText = text(textLimits.timezone).min(1).refine(isTimezone, "Timezone is not valid.");

/**
 * Names one creation, chosen by the caller (a UUID does). A creating mutation that gets a key it
 * has already carried out creates nothing and answers with what the first call created, so a
 * request can be sent again when its reply was lost. See docs/adr/0015.
 */
export const requestKey = z.string().regex(/^[A-Za-z0-9_-]{16,64}$/, "Request key is not valid.");

// A Date counts as its moment, and a value that is not there as an empty text.
const comparable = (value: unknown) => (value instanceof Date ? value.getTime() : (value ?? ""));

/**
 * Refuses a request whose key was already carried out with other values: the form was corrected
 * after a reply was lost, and answering like the first call would drop the correction without a
 * word. `sent` holds what this request states (undefined: not stated) and `stored` the record the
 * key is on, as it is now.
 */
export function refuseChangedRepeat<Sent extends Record<string, unknown>>(
  stored: { [Field in keyof Sent]: unknown },
  sent: Sent
) {
  if (Object.keys(sent).some((field) => sent[field] !== undefined && comparable(sent[field]) !== comparable(stored[field]))) {
    throw new TRPCError({
      code: "CONFLICT",
      message: "This request was already carried out with other values."
    });
  }
}

/** The `updatedAt` of the record a form was loaded from. */
export const expectedUpdatedAt = z.string().datetime();

/**
 * Refuses a save from a form that was loaded before someone else saved the record, so it cannot
 * silently put old values back. A save that would change nothing passes: that is the same save
 * sent again after its reply was lost.
 */
export function refuseOutdatedSave(
  record: { updatedAt: Date },
  loadedUpdatedAt: string | undefined,
  changesRecord: boolean
) {
  if (loadedUpdatedAt && changesRecord && new Date(loadedUpdatedAt).getTime() !== record.updatedAt.getTime()) {
    throw new TRPCError({
      code: "CONFLICT",
      message: "This record was changed since it was loaded."
    });
  }
}
