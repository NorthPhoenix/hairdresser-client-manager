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

export function describeError(error: unknown, details: ErrorDetails = {}): ErrorInfo {
  const value = (error ?? {}) as { name?: unknown; message?: unknown; stack?: unknown; code?: unknown; cause?: unknown; constructor?: { name?: unknown } };
  const isError = error instanceof Error;
  const type = typeof value.name === "string" && value.name !== "Error" ? value.name : isError && typeof value.constructor?.name === "string" ? value.constructor.name : typeof error;
  const ownCode = typeof value.code === "string" ? value.code : undefined;
  const cause = (value.cause ?? undefined) as { name?: unknown; message?: unknown; stack?: unknown; code?: unknown } | undefined;
  const causeCode = typeof cause?.code === "string" ? cause.code : undefined;
  const code = details.code ?? ownCode ?? causeCode;
  const message = typeof value.message === "string" ? value.message : isError ? "" : String(error);
  const ownStack = typeof value.stack === "string" ? value.stack : undefined;
  const causeStack = cause && typeof cause.stack === "string" ? cause.stack : undefined;
  const stack = [ownStack, causeStack && `Caused by: ${causeStack}`].filter(Boolean).join("\n");
  const retriable = details.retriable ?? ([code, causeCode].some((candidate) => candidate !== undefined && retriableCodes.has(candidate)) ? true : undefined);

  return {
    type: cleanString(String(type), 100),
    message: cleanString(message.trim(), 1000),
    // A refusal on purpose is explained by its code and reason; its stack would only make the line long.
    stack: stack && details.expected !== true ? cleanString(stack, 6000) : undefined,
    code: code ? cleanString(code, 64) : undefined,
    reason: details.reason ? cleanString(normalizeKey(details.reason), 100) : undefined,
    retriable,
    expected: details.expected === true,
    handled: details.handled === true,
    source: error,
    causeType: cause && typeof cause === "object" && typeof cause.name === "string" ? cleanString(cause.name, 100) : undefined,
    // A TRPCError made from a ZodError repeats its message; it is said once.
    causeMessage: cause && typeof cause.message === "string" && cause.message !== message ? cleanString(cause.message, 500) : undefined
  };
}
