import { parse } from "pg-connection-string";
import { afterEach, describe, expect, it, vi } from "vitest";
import { verifiedTlsConnectionString } from "./connectionString";

// The shape of Neon's addresses, with made-up credentials.
const neon = "postgresql://app_owner:s3cr%40t-pass@ep-example-123456-pooler.us-east-2.aws.neon.tech/neondb";

/** What pg makes of an address, minus the SSL mode it was asked for: the connection itself. */
function connection(address: string) {
  const { sslmode: _sslmode, ...rest } = parse(address);

  return rest;
}

describe("verifiedTlsConnectionString", () => {
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
    // Before any parse of an old mode: pg warns once a process, at the first one.
    const warnings = vi.spyOn(process, "emitWarning").mockImplementation(() => {});
    const addresses = ["prefer", "require", "verify-ca"].flatMap((mode) => [
      `${neon}?sslmode=${mode}`,
      `${neon}?channel_binding=require&sslmode=${mode}&application_name=hcm`
    ]);
    const rewritten = addresses.map((address) => verifiedTlsConnectionString(address));

    for (const address of rewritten) {
      parse(address);
    }
    expect(warnings).not.toHaveBeenCalled();

    addresses.forEach((address, index) => {
      expect(connection(rewritten[index]!)).toEqual(connection(address));
      expect(parse(rewritten[index]!).ssl).toEqual({});
    });
    // The addresses as they were are the ones pg warns about.
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
    ["a Unix socket", "/var/run/postgresql hcm_dev"]
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

  it("reads the parameters decoded, as pg does", () => {
    expect(verifiedTlsConnectionString(`${neon}?ssl%6Dode=requir%65&channel_binding=require`)).toBe(
      `${neon}?sslmode=verify-full&channel_binding=require`
    );
  });

  it("keeps a fragment out of the parameters", () => {
    expect(verifiedTlsConnectionString(`${neon}?sslmode=require#primary`)).toBe(`${neon}?sslmode=verify-full#primary`);
    expect(verifiedTlsConnectionString(`${neon}#sslmode=require`)).toBe(`${neon}#sslmode=require`);
  });
});
