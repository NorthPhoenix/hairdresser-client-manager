// What every span of this process may say once it is exported. Annotated fields pass the privacy
// guard, but the frameworks write attributes of their own that the guard never sees: Next.js the
// path of the request (`http.target`, with a Profile Share's token in it), @vercel/otel the
// `Referer`, and the fetch instrumentations the whole address of an outgoing call (a link to a
// Client's photo in storage) in the span's name and attributes. The wide event processor cleans
// every span here in `onEnding`, while the span can still be changed and before any exporter
// reads it (the exporters batch spans in `onEnd`, which comes after).
import { SpanKind, type Attributes } from "@opentelemetry/api";
import { cleanErrorText } from "./errors";
import { cleanString } from "./guard";

/** What the sanitizer needs of a span that is ending (the SDK's Span). */
export type EndingSpan = {
  name: string;
  kind: SpanKind;
  attributes: Attributes;
  events?: { name: string; attributes?: Attributes }[];
  status?: { code: number; message?: string };
  updateName(name: string): unknown;
  setAttribute(key: string, value: string | number | boolean): unknown;
  setStatus(status: { code: number; message?: string }): unknown;
};

// Attributes that hold the path of the request, as the server saw it. Only the route pattern may
// stand there.
const pathAttributes = ["http.target", "url.path", "vercel.matched_path"];
// Attributes that hold the query of the address. Nothing of it is kept.
const queryAttributes = ["url.query"];
// Attributes that hold a whole address. Only its scheme and host are kept.
const addressAttributes = ["http.url", "url.full", "http.referer", "resource.name"];

const absoluteAddress = /^[a-z][a-z0-9+.-]*:\/\//i;
const addressInText = /\b[a-z][a-z0-9+.-]*:\/\/[^\s"'<>]+/gi;

function stringAttribute(attributes: Attributes, ...names: string[]) {
  for (const name of names) {
    const value = attributes[name];

    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }

  return undefined;
}

/** The scheme and host of an address (`https://webfixture.ufs.sh`), or undefined when it is not one. */
export function originOf(address: string): string | undefined {
  try {
    const url = new URL(address);

    return url.host ? `${url.protocol}//${url.host}` : undefined;
  } catch {
    return undefined;
  }
}

/** The host of an outgoing call, never its path or query, which can carry file keys. */
export function hostOf(attributes: Attributes) {
  const host = stringAttribute(attributes, "server.address", "net.peer.name", "http.host");

  if (host) {
    return host.split(":")[0];
  }

  const url = stringAttribute(attributes, "url.full", "http.url");

  try {
    return url ? new URL(url).hostname : undefined;
  } catch {
    return undefined;
  }
}

/** The method of an outgoing HTTP call, or undefined for a span that is not one. */
function methodOf(attributes: Attributes) {
  return stringAttribute(attributes, "http.request.method", "http.method");
}

/**
 * The name of a span of an outgoing HTTP call: `fetch GET webfixture.ufs.sh`, never the address
 * that Next.js and @vercel/otel put there (`fetch GET https://…/f/<file key>`). Any other span
 * keeps its name, with whatever address it holds cut to scheme and host.
 */
export function safeSpanName(name: string, kind: SpanKind, attributes: Attributes): string {
  const method = methodOf(attributes);

  if (kind === SpanKind.CLIENT && method) {
    const first = name.split(" ")[0] ?? "";
    const prefix = first === "http" || first === "https" ? first : "fetch";
    const host = hostOf(attributes);

    return cleanString([prefix, method.toUpperCase(), host].filter(Boolean).join(" "), 120);
  }

  return name.replace(addressInText, (address) => originOf(address) ?? "[address]");
}

function remove(attributes: Attributes, key: string) {
  delete (attributes as Record<string, unknown>)[key];
}

/**
 * Cleans a span that is ending, in place: paths become the route pattern (or go), addresses keep
 * their scheme and host, an outgoing call is named by its host, and exception messages and the
 * status message go through the same cleaning as the event's `error.*`. Never throws.
 */
export function sanitizeSpan(span: EndingSpan): void {
  const attributes = span.attributes;

  if (!attributes || typeof attributes !== "object") {
    return;
  }

  const route = stringAttribute(attributes, "http.route", "next.route");

  for (const key of pathAttributes) {
    const value = attributes[key];

    if (value === undefined) {
      continue;
    }

    if (route !== undefined && typeof value === "string") {
      if (value !== route) {
        span.setAttribute(key, route);
      }
    } else {
      remove(attributes, key);
    }
  }

  for (const key of queryAttributes) {
    remove(attributes, key);
  }

  for (const [key, value] of Object.entries(attributes)) {
    // The named address attributes, and any other value that is an address.
    if (typeof value !== "string" || !(addressAttributes.includes(key) ? value.includes("://") || key === "http.referer" : absoluteAddress.test(value))) {
      continue;
    }

    const origin = originOf(value);

    if (origin === undefined) {
      remove(attributes, key);
    } else if (origin !== value) {
      span.setAttribute(key, origin);
    }
  }

  const name = safeSpanName(span.name, span.kind, attributes);

  if (name !== span.name) {
    span.updateName(name);
  }

  for (const event of span.events ?? []) {
    if (event.name !== "exception" || !event.attributes) {
      continue;
    }

    const exception = event.attributes as Record<string, unknown>;

    if (typeof exception["exception.message"] === "string") {
      exception["exception.message"] = cleanErrorText(exception["exception.message"], 1000);
    }

    if (typeof exception["exception.stacktrace"] === "string") {
      exception["exception.stacktrace"] = cleanErrorText(exception["exception.stacktrace"], 6000);
    }
  }

  const status = span.status;

  if (status && typeof status.message === "string" && status.message.length > 0) {
    const message = cleanErrorText(status.message, 1000);

    if (message !== status.message) {
      span.setStatus({ code: status.code, message });
    }
  }
}
