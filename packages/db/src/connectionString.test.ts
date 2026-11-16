import { createRequire } from "node:module";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { verifiedTlsConnectionString } from "./connectionString";

// The address parser of the pg that the app's database client loads (through @prisma/adapter-pg),
// so that the tests follow it when a newer one comes in.
const fromPg = createRequire(createRequire(createRequire(import.meta.url).resolve("@prisma/adapter-pg")).resolve("pg"));
const parserPath = fromPg.resolve("pg-connection-string");

type PgConfig = { sslmode?: string; ssl?: unknown; [key: string]: unknown };
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

// The shape of Neon's addresses, with made-up credentials.
const neon = "postgresql://app_owner:s3cr%40t-pass@ep-example-123456-pooler.us-east-2.aws.neon.tech/neondb";

/** What pg makes of an address, minus the SSL mode it was asked for: the connection itself. */
function connection(address: string, pgParse: PgParse = parse) {
  const { sslmode: _sslmode, ...rest } = pgParse(address);

  return rest;
}

describe("verifiedTlsConnectionString", () => {
  beforeEach(() => {
    // pg's SECURITY WARNING, which these tests trigger on purpose.
    vi.spyOn(process, "emitWarning").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks for verify-full where Neon's address says require, and changes nothing else", () => {
    const address = `${neon}?channel_binding=require&sslmode=require`;

    expect(verifiedTlsConnectionString(address)).toBe(`${neon}?channel_binding=require&sslmode=verify-full`);
  });

  it.each(["prefer", "require", "verify-ca"])("asks for verify-full instead of %s", (mode) => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=${mode}`)).toBe(`${neon}?sslmode=verify-full`);
  });

  it("keeps the connection pg makes today, without its SECURITY WARNING", () => {
    const pgParse = freshParse();
    const warnings = vi.mocked(process.emitWarning);
    const addresses = ["prefer", "require", "verify-ca"].flatMap((mode) => [
      `${neon}?sslmode=${mode}`,
      `${neon}?channel_binding=require&sslmode=${mode}&application_name=hcm`,
      // A line break left at the end of an environment variable, which pg does not read.
      `${neon}?channel_binding=require&sslmode=${mode}\n`,
      // A space in the password, which pg percent-encodes before reading the address.
      `postgresql://app_owner:two words@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=${mode}`
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
    ["the SSL mode in the fragment", `${neon}#sslmode=require`],
    ["a \"?\" in the fragment", `${neon}#primary?sslmode=require`],
    // pg percent-encodes the stray "%" first, so it reads "ssl%6dode", not "sslmode".
    ["an escaped name that pg does not decode", "postgresql://app_owner:p%zz@ep-example-123456.us-east-2.aws.neon.tech/neondb?ssl%6dode=require"],
    // pg cannot percent-encode a lone surrogate, and says so when it connects.
    ["text that pg cannot read", "postgresql://app_owner:two words\uD800@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require"]
  ])("leaves an address with %s as it is", (_case, address) => {
    expect(verifiedTlsConnectionString(address)).toBe(address);
  });

  it("goes by the last SSL mode, as pg does", () => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=disable&sslmode=require`)).toBe(
      `${neon}?sslmode=verify-full&sslmode=verify-full`
    );
    expect(verifiedTlsConnectionString(`${neon}?sslmode=require&sslmode=disable`)).toBe(`${neon}?sslmode=require&sslmode=disable`);
    expect(verifiedTlsConnectionString(`${neon}?uselibpqcompat=true&sslmode=require&uselibpqcompat=false`)).toBe(
      `${neon}?uselibpqcompat=true&sslmode=verify-full&uselibpqcompat=false`
    );
  });

  it("reads the address as pg does: decoded, and without tabs and line breaks", () => {
    expect(verifiedTlsConnectionString(`${neon}?ssl%6Dode=requir%65&channel_binding=require`)).toBe(
      `${neon}?sslmode=verify-full&channel_binding=require`
    );
    expect(verifiedTlsConnectionString(`${neon}?channel_binding=require&sslmode=require\r\n`)).toBe(
      `${neon}?channel_binding=require&sslmode=verify-full`
    );
    expect(verifiedTlsConnectionString(`${neon}?ssl\tmode=require`)).toBe(`${neon}?sslmode=verify-full`);
  });

  it("keeps a fragment after the parameters", () => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=require#primary`)).toBe(`${neon}?sslmode=verify-full#primary`);
  });
});
