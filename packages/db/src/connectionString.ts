// The SSL modes that pg 8 treats as "verify-full" and warns about (pg-connection-string 2.x).
const verifyFullAliases = new Set(["prefer", "require", "verify-ca"]);

/**
 * The database address the app connects with: [connectionString], asking for "sslmode=verify-full"
 * where it asks for "prefer", "require" or "verify-ca".
 *
 * pg 8 already connects to those three as to "verify-full": encrypted, with the server's
 * certificate checked against its name. It says so in a SECURITY WARNING the first time a process
 * reads such an address, which on a host is every cold start, since Neon's addresses say
 * "sslmode=require". pg 9 will read them as libpq does, where "require" encrypts without checking
 * whose certificate it is. Naming "verify-full" keeps the connection as it is today on both, and
 * without the warning.
 *
 * The address is changed by adding "&sslmode=verify-full" at the end of its query: pg goes by the
 * last value of a parameter, so that one is the SSL mode, and the rest of the address stays as it
 * was. Every other address is returned as it is: one that asks for libpq's meaning
 * ("uselibpqcompat=true"), one without an SSL mode or with another one ("disable", "no-verify",
 * "verify-full"), one whose parameters pg does not read (a Unix socket) or that the URL parser
 * cannot read, and one that pg would read in another way once the parameter is added.
 */
export function verifiedTlsConnectionString(connectionString: string): string {
  const before = readAsPg(connectionString);
  const sslMode = before?.searchParams.getAll("sslmode").at(-1);
  const libpqCompat = before?.searchParams.getAll("uselibpqcompat").at(-1) === "true";

  if (!before || libpqCompat || sslMode === undefined || !verifyFullAliases.has(sslMode)) {
    return connectionString;
  }

  // The query ends where the fragment starts: at the first "#", which nothing before it can escape.
  const fragmentStart = connectionString.indexOf("#");
  const queryEnd = fragmentStart === -1 ? connectionString.length : fragmentStart;
  const verified = connectionString.slice(0, queryEnd) + "&sslmode=verify-full" + connectionString.slice(queryEnd);
  const after = readAsPg(verified);

  // Never so for a real address, but one that ends in a stray "%" or a control character is read
  // in another way once the parameter comes after that end.
  return after && isWithVerifyFull(before, after) ? verified : connectionString;
}

/**
 * [connectionString] as pg-connection-string reads it: with the URL parser, after percent-encoding
 * an address that has a space or a "%" that starts no escape, and again with a stand-in host when
 * the address has none. Null for an address whose parameters pg does not read (a Unix socket) or
 * that the URL parser cannot read.
 */
function readAsPg(connectionString: string): URL | null {
  // "/var/run/postgresql dbname"
  if (connectionString.startsWith("/")) {
    return null;
  }

  try {
    const address = / |%[^a-f0-9]|%[a-f0-9][^a-f0-9]/i.test(connectionString)
      ? encodeURI(connectionString).replace(/%25(\d\d)/g, "%$1")
      : connectionString;
    let url: URL;

    try {
      url = new URL(address, "postgres://base");
    } catch {
      url = new URL(address.replace("@/", "@___DUMMY___/"), "postgres://base");
    }

    // A "socket:" address goes by its path and ignores the SSL mode.
    return url.protocol === "socket:" ? null : url;
  } catch {
    // pg cannot read it either, and says so when it connects.
    return null;
  }
}

/** Whether pg reads [after] as the address [before] with "sslmode=verify-full", and nothing else changed. */
function isWithVerifyFull(before: URL, after: URL): boolean {
  const expected = lastValues(before.searchParams).set("sslmode", "verify-full");
  const parameters = lastValues(after.searchParams);

  return (
    after.protocol === before.protocol &&
    after.username === before.username &&
    after.password === before.password &&
    after.host === before.host &&
    after.pathname === before.pathname &&
    parameters.size === expected.size &&
    [...expected].every(([name, value]) => parameters.get(name) === value)
  );
}

/** Each parameter with its last value, the one pg goes by. */
function lastValues(parameters: URLSearchParams): Map<string, string> {
  return new Map(parameters);
}
