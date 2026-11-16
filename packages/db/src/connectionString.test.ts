import { createRequire } from "node:module";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { verifiedTlsConnectionString } from "./connectionString";

// The address parser of the pg that the app's database client loads (through @prisma/adapter-pg),
// so that the tests follow it when a newer one comes in.
const fromPg = createRequire(createRequire(createRequire(import.meta.url).resolve("@prisma/adapter-pg")).resolve("pg"));
const parserPath = fromPg.resolve("pg-connection-string");

type PgConfig = { sslmode?: string; ssl?: unknown; uselibpqcompat?: string; [key: string]: unknown };
type PgParse = (address: string, options?: { useLibpqCompat?: boolean }) => PgConfig;

/**
 * A copy of pg's parser loaded afresh. pg warns about an old SSL mode once per process, at the first
 * address with one that it reads, so a test that counts warnings needs a copy of its own.
 */
function freshParse(): PgParse {
  delete fromPg.cache[parserPath];

  return (fromPg(parserPath) as { parse: PgParse }).parse;
}

const parse = freshParse();
const oldModes = ["prefer", "require", "verify-ca"];

// The shape of Neon's addresses, with made-up credentials.
const neon = "postgresql://app_owner:s3cr%40t-pass@ep-example-123456-pooler.us-east-2.aws.neon.tech/neondb";
const verified = "&sslmode=verify-full";

/** What pg makes of an address, minus the SSL mode it was asked for: the connection itself. */
function connection(address: string, pgParse: PgParse = parse) {
  const { sslmode: _sslmode, ...rest } = pgParse(address);

  return rest;
}

/** What pg makes of an address, or the error it throws. */
function outcome(address: string): { config: PgConfig } | { error: string } {
  try {
    return { config: parse(address) };
  } catch (error) {
    return { error: String(error) };
  }
}

describe("verifiedTlsConnectionString", () => {
  beforeEach(() => {
    // pg's SECURITY WARNING, which these tests trigger on purpose.
    vi.spyOn(process, "emitWarning").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks for verify-full after the require of Neon's address, and changes nothing else", () => {
    const address = `${neon}?channel_binding=require&sslmode=require`;

    expect(verifiedTlsConnectionString(address)).toBe(address + verified);
  });

  it.each(oldModes)("asks for verify-full instead of %s", (mode) => {
    expect(parse(verifiedTlsConnectionString(`${neon}?sslmode=${mode}`)).sslmode).toBe("verify-full");
  });

  it("keeps the connection pg makes today, without its SECURITY WARNING", () => {
    const pgParse = freshParse();
    const warnings = vi.mocked(process.emitWarning);
    const addresses = oldModes.flatMap((mode) => [
      `${neon}?sslmode=${mode}`,
      `${neon}?channel_binding=require&sslmode=${mode}&application_name=hcm`,
      // A line break left at the end of an environment variable, which pg does not read.
      `${neon}?channel_binding=require&sslmode=${mode}\n`,
      // A space and an escape in the password: pg percent-encodes the address before reading it.
      `postgresql://app_owner:two words%41@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=${mode}`
    ]);
    const rewritten = addresses.map((address) => verifiedTlsConnectionString(address));

    for (const address of rewritten) {
      const config = pgParse(address);

      expect(config.sslmode).toBe("verify-full");
      // TLS with the certificate checked: nothing in it turns the check off.
      expect(config.ssl).toEqual({});
    }
    expect(warnings).not.toHaveBeenCalled();

    // The addresses as they were are the ones pg warns about, once.
    addresses.forEach((address, index) => expect(connection(rewritten[index]!, pgParse)).toEqual(connection(address, pgParse)));
    expect(warnings).toHaveBeenCalledTimes(1);
    expect(String(warnings.mock.calls[0]?.[0])).toContain("SECURITY WARNING");
  });

  it("keeps the certificate checked under libpq's meaning of the modes, which pg 9 takes", () => {
    const required = `${neon}?channel_binding=require&sslmode=require`;
    // What uselibpqcompat=true does in pg 8 is what pg 9 will do by default.
    const asPg9 = (address: string) => parse(address, { useLibpqCompat: true }).ssl;

    expect(asPg9(required)).toEqual({ rejectUnauthorized: false });
    expect(asPg9(verifiedTlsConnectionString(required))).toEqual({});
  });

  it.each([
    ["no SSL mode", `${neon}?channel_binding=require`],
    ["no parameters", neon],
    ["verify-full", `${neon}?sslmode=verify-full`],
    ["disable", "postgresql://postgres:postgres@127.0.0.1:54329/hcm_dev?sslmode=disable"],
    ["no-verify", `${neon}?sslmode=no-verify`],
    ["libpq's meaning asked for", `${neon}?uselibpqcompat=true&sslmode=require`],
    ["a local address", "postgresql://postgres:postgres@127.0.0.1:54329/hcm_dev"],
    ["a Unix socket", "/var/run/postgresql hcm_dev"],
    ["a socket: address", "socket:/var/run/postgresql?db=hcm_dev&sslmode=require"],
    ["the SSL mode in the fragment", `${neon}#sslmode=require`],
    ["a \"?\" in the fragment", `${neon}#primary?sslmode=require`],
    // The URL parser takes the second "?" for part of the name: "?sslmode".
    ["a second \"?\"", `${neon}??sslmode=require`],
    // pg percent-encodes the stray "%" first, so it reads "ssl%6dode", not "sslmode".
    ["an escaped name that pg does not decode", "postgresql://app_owner:p%zz@ep-example-123456.us-east-2.aws.neon.tech/neondb?ssl%6dode=require"],
    // pg cannot percent-encode a lone surrogate, and says so when it connects.
    ["text that pg cannot read", "postgresql://app_owner:two words\uD800@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require"]
  ])("leaves an address with %s as it is", (_case, address) => {
    expect(verifiedTlsConnectionString(address)).toBe(address);
  });

  it("goes by the last SSL mode, as pg does", () => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=disable&sslmode=require`)).toBe(`${neon}?sslmode=disable&sslmode=require${verified}`);
    expect(verifiedTlsConnectionString(`${neon}?sslmode=require&sslmode=disable`)).toBe(`${neon}?sslmode=require&sslmode=disable`);
    expect(verifiedTlsConnectionString(`${neon}?uselibpqcompat=true&sslmode=require&uselibpqcompat=false`)).toBe(
      `${neon}?uselibpqcompat=true&sslmode=require&uselibpqcompat=false${verified}`
    );
  });

  it("reads the address as pg does: decoded, and without tabs and line breaks", () => {
    for (const address of [
      `${neon}?ssl%6Dode=requir%65&channel_binding=require`,
      `${neon}?channel_binding=require&sslmode=require\r\n`,
      `${neon}?ssl\tmode=require`,
      // A control character before an address without a scheme, which pg drops.
      "\n//ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require"
    ]) {
      const rewritten = verifiedTlsConnectionString(address);

      expect(parse(rewritten).sslmode).toBe("verify-full");
      expect(connection(rewritten)).toEqual(connection(address));
    }
  });

  it("adds the SSL mode before a fragment", () => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=require#primary`)).toBe(`${neon}?sslmode=require${verified}#primary`);
  });

  it("agrees with pg on every address made of the pieces pg reads in special ways", () => {
    const pieces = [
      "postgresql://", "postgres://", "socket:", "/", "//", "u", ":", "p", "@", "@/", "h", ":5432", "/db",
      "?", "??", "&", "=", "#", "+", " ", "%", "%zz", "%41", "%6d", "\t", "\n", "\r", "\x00", "\uD800",
      "sslmode", "ssl%6Dode", "ssl\tmode", "uselibpqcompat", "true",
      "prefer", "require", "verify-ca", "verify-full", "disable", "no-verify",
      "sslmode=require", "sslmode=prefer", "sslmode=verify-ca", "sslmode=disable", "uselibpqcompat=true"
    ];
    // A fixed sequence of pseudo-random numbers (mulberry32), so that a failure can be run again.
    let seed = 20261115;
    const random = () => {
      seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;

      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const pick = () => pieces[Math.floor(random() * pieces.length)]!;

    for (let index = 0; index < 4000; index += 1) {
      const head = random() < 0.7 ? "postgresql://u:p@h/db?" : "";
      const address = head + Array.from({ length: 1 + Math.floor(random() * 8) }, pick).join("");
      const before = outcome(address);
      const rewritten = verifiedTlsConnectionString(address);

      if ("error" in before) {
        expect(rewritten, JSON.stringify(address)).toBe(address);
        continue;
      }

      const asked = before.config.uselibpqcompat !== "true" && oldModes.includes(before.config.sslmode ?? "");

      if (!asked) {
        expect(rewritten, JSON.stringify(address)).toBe(address);
        continue;
      }

      const after = parse(rewritten);
      const { sslmode: _before, ...connectionBefore } = before.config;
      const { sslmode: afterMode, ...connectionAfter } = after;

      expect(afterMode, JSON.stringify(address)).toBe("verify-full");
      expect(connectionAfter, JSON.stringify(address)).toEqual(connectionBefore);
    }
  });
});
