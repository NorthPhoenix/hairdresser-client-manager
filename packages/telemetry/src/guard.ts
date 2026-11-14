// The privacy guard. Every field that code annotates passes through here before it reaches an
// event or an exported span, so a careless annotation in a router does not put a Client's name or
// phone number into the logs. The rules are in docs/observability.md ("Privacy rules"); the guard
// enforces the ones a machine can check: names of keys, types and lengths of values, and values
// that look like an email address or a phone number.

/** What a field of an event can hold. Objects are flattened into dotted keys before this. */
export type FieldValue = string | number | boolean | string[] | number[] | boolean[];

/** Fields as code passes them to `annotate`: nested objects are allowed and flattened. */
export type Fields = Record<string, unknown>;

/** The longest string an annotated field keeps. */
export const maxStringLength = 200;
/** The most elements an annotated array keeps. */
export const maxArrayLength = 20;
/** The longest key, after it is put into snake_case. */
const maxKeyLength = 80;
/** How deep nested objects are flattened; anything deeper is dropped. */
const maxDepth = 3;

// Key words that name personal data, credentials or contents. A key that contains one of them as
// a word ("client.phone", "share_token", "notes") is refused, unless the key ends in a word that
// says the value is a measure of it (below).
const deniedWords = new Set([
  "name",
  "names",
  "firstname",
  "lastname",
  "fullname",
  "nickname",
  "phone",
  "phones",
  "email",
  "emails",
  "mail",
  "note",
  "notes",
  "comment",
  "comments",
  "text",
  "description",
  "address",
  "street",
  "birthday",
  "birthdate",
  "dob",
  "instagram",
  "telegram",
  "token",
  "tokens",
  "cookie",
  "cookies",
  "authorization",
  "password",
  "secret",
  "credential",
  "credentials",
  "key",
  "keys",
  "url",
  "urls",
  "uri",
  "href",
  "body",
  "content",
  "contents",
  "query",
  "sql",
  "header",
  "headers"
]);

// A key whose last word is one of these holds a measure of something, never the thing itself:
// `client.notes_length`, `share.token_hash`, `photo.url_count`. Its value has to be a number or a
// boolean (or, for `_hash`, a short hex string), which the guard checks.
const measureWords = new Set(["count", "length", "size", "bytes", "ms", "total", "hash", "present", "changed", "valid", "kind", "type"]);
// The same for a first word: `has_phone`, `is_new`.
const flagWords = new Set(["has", "is"]);

// Keys that the event itself writes. An annotation cannot overwrite them; see the field catalog.
const reservedKeys = new Set(["event", "schema_version", "timestamp", "trace_id", "span_id", "parent_span_id", "name", "duration_ms", "outcome", "span_count"]);
const reservedPrefixes = ["sample.", "telemetry.", "deployment.", "service.", "instance.", "runtime.", "db.", "external.", "slowest_span.", "error.", "process."];

const emailPattern = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
// A phone number as people type it: a plus or a long run of digits with the usual separators.
const phonePattern = /(?:\+\d[\d\s().-]{7,}\d)|(?:\b\d[\d\s().-]{9,}\d\b)/g;
const hexHashPattern = /^[0-9a-f]{6,64}$/;
const isoDatePattern = /^\d{4}-\d{2}-\d{2}(?:[T ][\d:.]+(?:Z|[+-]\d{2}:?\d{2})?)?$/;

/** Turns `appointmentId` and `Client-Count` into `appointment_id` and `client_count`. */
export function normalizeKey(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase()
    .replace(/[^a-z0-9_.]+/g, "_")
    .replace(/\.{2,}/g, ".")
    .replace(/^[._]+|[._]+$/g, "")
    .slice(0, maxKeyLength);
}

function wordsOf(key: string) {
  return key.split(/[._]/).filter((word) => word.length > 0);
}

/** Why a key is refused, or null when it may be written. `key` is already normalized. */
export function refusalOfKey(key: string): "empty" | "reserved" | "personal" | null {
  if (key.length === 0) {
    return "empty";
  }

  if (reservedKeys.has(key) || reservedPrefixes.some((prefix) => key.startsWith(prefix))) {
    return "reserved";
  }

  const words = wordsOf(key);

  if (!words.some((word) => deniedWords.has(word))) {
    return null;
  }

  return isMeasureKey(key) ? null : "personal";
}

/** Whether a key names a measure (`_count`, `has_`) and so may only hold a number or a boolean. */
export function isMeasureKey(key: string) {
  const words = wordsOf(key);
  const last = words.at(-1) ?? "";
  // The last dotted segment decides: `client.has_phone` is a flag, `has.client_phone` is not.
  const firstOfLastSegment = wordsOf(key.split(".").at(-1) ?? "")[0] ?? "";

  return measureWords.has(last) || flagWords.has(firstOfLastSegment);
}

/**
 * Caps a string and blanks what looks like an email address or a phone number. Used for every
 * string that ends up in an event, also error messages and stacks, with a larger cap there.
 */
export function cleanString(value: string, max = maxStringLength): string {
  // ISO dates and times look like long runs of digits; they are not phone numbers.
  if (isoDatePattern.test(value)) {
    return value.slice(0, max);
  }

  const cleaned = value.replace(emailPattern, "[email]").replace(phonePattern, (match) => (isoDatePattern.test(match.trim()) ? match : "[phone]"));

  return cleaned.length > max ? `${cleaned.slice(0, max - 1)}…` : cleaned;
}

function cleanScalar(value: unknown, key: string): string | number | boolean | undefined {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : undefined;
  }

  if (typeof value === "boolean") {
    return value;
  }

  if (typeof value === "bigint") {
    return Number.isSafeInteger(Number(value)) ? Number(value) : value.toString();
  }

  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? undefined : value.toISOString();
  }

  if (typeof value === "string") {
    // A measure key holds a number or a boolean; a hash key a short hex string.
    if (isMeasureKey(key)) {
      return key.endsWith("hash") && hexHashPattern.test(value) ? value : key.endsWith("kind") || key.endsWith("type") ? cleanString(value, 64) : undefined;
    }

    return cleanString(value);
  }

  return undefined;
}

function cleanArray(values: unknown[], key: string): FieldValue | undefined {
  const cleaned = values.slice(0, maxArrayLength).map((value) => cleanScalar(value, key));
  const kinds = new Set(cleaned.map((value) => typeof value));

  // An exported span only takes arrays of one type, and an element that was refused makes the
  // whole array suspicious.
  if (kinds.size !== 1 || kinds.has("undefined")) {
    return cleaned.length === 0 ? [] : undefined;
  }

  return cleaned as FieldValue;
}

export type GuardResult = {
  fields: Record<string, FieldValue>;
  /** Keys that were refused, with why: shown in the event as `telemetry.blocked_keys`. */
  blocked: string[];
};

/**
 * Flattens, checks and cleans fields for an event. Never throws: a field it cannot read is left
 * out and named in `blocked`.
 */
export function guardFields(input: Fields, options: { allowReserved?: boolean } = {}): GuardResult {
  const result: GuardResult = { fields: {}, blocked: [] };

  function visit(value: unknown, key: string, depth: number) {
    try {
      if (value === undefined || value === null) {
        return;
      }

      if (typeof value === "object" && !Array.isArray(value) && !(value instanceof Date)) {
        if (depth >= maxDepth) {
          result.blocked.push(`${key}:too_deep`);
          return;
        }

        for (const [childKey, childValue] of Object.entries(value as Record<string, unknown>)) {
          visit(childValue, key ? `${key}.${normalizeKey(childKey)}` : normalizeKey(childKey), depth + 1);
        }

        return;
      }

      const normalized = normalizeKey(key);
      const refusal = refusalOfKey(normalized);

      if (refusal && !(refusal === "reserved" && options.allowReserved)) {
        result.blocked.push(`${normalized || key}:${refusal}`);
        return;
      }

      const cleaned = Array.isArray(value) ? cleanArray(value, normalized) : cleanScalar(value, normalized);

      if (cleaned === undefined) {
        result.blocked.push(`${normalized}:value`);
        return;
      }

      result.fields[normalized] = cleaned;
    } catch {
      result.blocked.push(`${key}:unreadable`);
    }
  }

  try {
    for (const [key, value] of Object.entries(input ?? {})) {
      visit(value, normalizeKey(key), 0);
    }
  } catch {
    result.blocked.push("fields:unreadable");
  }

  return result;
}
