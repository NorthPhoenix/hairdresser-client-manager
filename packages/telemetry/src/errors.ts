// How an error is described in an event: what it is, what it says, where it came from and
// whether trying again could help. Messages and stacks pass the privacy guard like any string.
import { cleanString, normalizeKey } from "./guard";
import type { ErrorInfo } from "./state";

export type ErrorDetails = {
  /** A refusal on purpose (bad input, not found, conflict) rather than a failure. Default false. */
  expected?: boolean;
  /** A code for the error: a tRPC code ("BAD_REQUEST"), a Prisma code ("P2002"). Read from the error when left out. */
  code?: string;
  /** Why, in the domain's words: "appointment_overlaps", "client_has_appointments". snake_case. */
  reason?: string;
  /** Whether sending the same request again could succeed. Guessed for known codes when left out. */
  retriable?: boolean;
  /**
   * A failure that the code caught and got over, such as photo storage that did not delete a
   * file after the records were gone: it is recorded and keeps the event, but the request's
   * `outcome` stays what it was. Default false.
   */
  handled?: boolean;
};

// Codes of failures that can pass when the request is sent again: connection and timeout
// failures of Prisma and Node, and Prisma's transaction conflict.
const retriableCodes = new Set(["P1001", "P1002", "P1008", "P1017", "P2024", "P2028", "P2034", "ECONNRESET", "ECONNREFUSED", "ETIMEDOUT", "EPIPE", "EAI_AGAIN", "TIMEOUT"]);

// Prisma describes a call it refused ("Invalid `prisma.client.create()` invocation:") by printing
// the call's arguments, the Client's name, phone and notes included, between the line that names
// the call and the explanation at the end. Only those two are kept.
const prismaInvocation = /Invalid `[^`\n]*` invocation[\s\S]*?(?=\n {4}at |$)/g;

/**
 * Removes the arguments Prisma prints in the message of a call it refused, in a message or in a
 * stack (which starts with the message). The line that names the call (and the file it was made
 * in, when Prisma knows it) and the last paragraph, which says what was wrong, are kept.
 */
export function stripPrismaArguments(text: string): string {
  if (!text.includes("` invocation")) {
    return text;
  }

  // Up to the first line of the stack's frames, or to the end of a message.
  return text.replace(prismaInvocation, (block) => {
    const lines = block.split("\n");
    let head = lines[0] ?? "";
    let rest = lines.slice(1);

    // "Invalid `prisma.client.create()` invocation in\n/path/to/file.ts:12:3".
    if (/ in\s*$/.test(head) && rest.length > 0) {
      head = `${head}\n${rest[0]}`;
      rest = rest.slice(1);
    }

    const paragraphs = rest
      .join("\n")
      .split(/\n[ \t]*\n/)
      .map((paragraph) => paragraph.trim())
      .filter((paragraph) => paragraph.length > 0);
    const last = paragraphs.at(-1);
    // A single paragraph is the explanation only when it is not the arguments themselves.
    const explanation = last !== undefined && (paragraphs.length > 1 || !/^[{}\[\]→?~\s\d]|\n\s/.test(last)) ? last : undefined;
    const removed = paragraphs.length > (explanation === undefined ? 0 : 1);

    return [head, removed ? "[arguments removed]" : undefined, explanation].filter((part) => part !== undefined).join("\n\n");
  });
}

/** An error's message or stack as an event and an exported span may show it. */
export function cleanErrorText(text: string, max: number): string {
  return cleanString(stripPrismaArguments(text), max);
}

// Whether a class name survived minification: the production build of the web app renames the
// classes of its dependencies to one or two letters (`a`, `e`, `cv`).
function isMeaningfulName(name: unknown): name is string {
  return typeof name === "string" && name !== "Error" && /^[A-Za-z_$][\w$]*$/.test(name) && (name.length >= 4 || /(Error|Exception)$/.test(name));
}

/**
 * The error's type: its `name` when that says more than "Error", else what its class calls itself
 * (Clerk's errors keep their name in a static `kind`, which minification leaves alone), else the
 * class's name when it was not minified, else "Error".
 */
function typeOf(error: unknown): string {
  if (!(error instanceof Error) && (typeof error !== "object" || error === null)) {
    return typeof error;
  }

  const value = error as { name?: unknown; constructor?: { name?: unknown; kind?: unknown } };
  const candidates = [value.name, value.constructor?.kind, value.constructor?.name];

  return candidates.find(isMeaningfulName) ?? (typeof value.name === "string" && value.name.length > 2 ? value.name : "Error");
}

type ErrorLike = {
  name?: unknown;
  message?: unknown;
  stack?: unknown;
  code?: unknown;
  errorCode?: unknown;
  status?: unknown;
  errors?: unknown;
  reason?: unknown;
  action?: unknown;
  cause?: unknown;
};

/**
 * The error's own code: tRPC's and Clerk's `code` ("UNAUTHORIZED", "api_response_error"), Prisma's
 * (`code` "P2002", or `errorCode` of a failure to connect), Node's ("ECONNRESET"), or the reason
 * of Clerk's token verification ("token-expired").
 */
function codeOf(value: ErrorLike | undefined): string | undefined {
  if (!value) {
    return undefined;
  }

  if (typeof value.code === "string" && value.code.length > 0) {
    return value.code;
  }

  if (typeof value.errorCode === "string" && value.errorCode.length > 0) {
    return value.errorCode;
  }

  // Clerk's TokenVerificationError: a kebab-case `reason` next to an `action` to take.
  if (typeof value.reason === "string" && typeof value.action === "string" && /^[a-z]+(?:-[a-z]+)+$/.test(value.reason)) {
    return value.reason;
  }

  return undefined;
}

/** The code of the first error in an API's answer, as Clerk's errors carry them (`errors[0].code`). */
function apiCodeOf(value: ErrorLike): string | undefined {
  const first = Array.isArray(value.errors) ? (value.errors[0] as { code?: unknown } | undefined) : undefined;

  return typeof first?.code === "string" && first.code.length > 0 ? first.code : undefined;
}

/** The HTTP status of the answer an error reports, as Clerk's and UploadThing's errors carry it. */
function httpStatusOf(value: ErrorLike): number | undefined {
  return typeof value.status === "number" && Number.isInteger(value.status) && value.status >= 100 && value.status <= 599 ? value.status : undefined;
}

export function describeError(error: unknown, details: ErrorDetails = {}): ErrorInfo {
  const value = (error ?? {}) as ErrorLike;
  const isError = error instanceof Error;
  const type = typeOf(error);
  const ownCode = codeOf(value);
  const cause = (value.cause ?? undefined) as ErrorLike | undefined;
  const causeCode = codeOf(cause);
  const code = details.code ?? ownCode ?? causeCode;
  const message = typeof value.message === "string" ? value.message : isError ? "" : String(error);
  const ownStack = typeof value.stack === "string" ? value.stack : undefined;
  const causeStack = cause && typeof cause.stack === "string" ? cause.stack : undefined;
  const stack = [ownStack, causeStack && `Caused by: ${causeStack}`].filter(Boolean).join("\n");
  const retriable = details.retriable ?? ([code, causeCode].some((candidate) => candidate !== undefined && retriableCodes.has(candidate)) ? true : undefined);
  const apiCode = apiCodeOf(value);
  const httpStatus = httpStatusOf(value);

  return {
    type: cleanString(type, 100),
    message: cleanErrorText(message.trim(), 1000),
    // A refusal on purpose is explained by its code and reason; its stack would only make the line long.
    stack: stack && details.expected !== true ? cleanErrorText(stack, 6000) : undefined,
    code: code ? cleanString(code, 64) : undefined,
    apiCode: apiCode ? cleanString(apiCode, 64) : undefined,
    httpStatus,
    reason: details.reason ? cleanString(normalizeKey(details.reason), 100) : undefined,
    retriable,
    expected: details.expected === true,
    handled: details.handled === true,
    source: error,
    causeType: cause && typeof cause === "object" && typeof cause.name === "string" ? cleanString(typeOf(cause), 100) : undefined,
    // A TRPCError made from a ZodError repeats its message; it is said once.
    causeMessage: cause && typeof cause.message === "string" && cause.message !== message ? cleanErrorText(cause.message.trim(), 500) : undefined
  };
}
