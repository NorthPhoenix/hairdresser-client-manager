// The SSL modes that pg 8 treats as "verify-full" and warns about (pg-connection-string 2.x).
const verifyFullAliases = new Set(["prefer", "require", "verify-ca"]);

/**
 * The database address (connection string) the app connects with: [connectionString], asking for
 * "sslmode=verify-full" where it asks for "prefer", "require" or "verify-ca".
 *
 * pg 8 already connects to those three as to "verify-full": encrypted, with the server's
 * certificate checked against its name. It says so in a SECURITY WARNING the first time a process
 * reads such a database address, which on Vercel is every cold start, since Neon's database
 * addresses say "sslmode=require". pg 9 will read them as libpq does, where "require" encrypts
 * without checking whose certificate it is. Naming "verify-full" keeps the connection as it is
 * today on both, and without the warning.
 *
 * Only the SSL mode of the database address changes. Where it has one SSL mode, written as
 * "sslmode=require" (or "prefer", "verify-ca"), that becomes "sslmode=verify-full" where it stands;
 * otherwise a last "&sslmode=verify-full" is added to its query, since pg goes by the last value of
 * a parameter. The rest stays as it was.
 *
 * Every other one is returned as it is: one that asks for libpq's meaning ("uselibpqcompat=true"),
 * one without an SSL mode or with another one ("disable", "no-verify", "verify-full"), one whose
 * parameters pg does not read (a Unix socket) or that the URL parser cannot read, and one that pg
 * would read in another way once the parameter is added.
 */
export function verifiedTlsConnectionString(connectionString: string): string {
  const before = readAsPg(connectionString);
  const sslMode = before?.searchParams.getAll("sslmode").at(-1);
  const libpqCompat = before?.searchParams.getAll("uselibpqcompat").at(-1) === "true";

  if (!before || libpqCompat || sslMode === undefined || !verifyFullAliases.has(sslMode)) {
    return connectionString;
  }

  // The query starts at the first "?" (none comes before it in a database address the URL parser
  // reads), and ends where the fragment starts: at the first "#", which nothing before it can escape.
  const queryStart = connectionString.indexOf("?");
  const fragmentStart = connectionString.indexOf("#");
  const queryEnd = fragmentStart === -1 ? connectionString.length : fragmentStart;

  if (queryStart === -1 || queryStart > queryEnd) {
    return connectionString;
  }

  const head = connectionString.slice(0, queryStart);
  const query = connectionString.slice(queryStart, queryEnd);
  const tail = connectionString.slice(queryEnd);
  // The SSL mode as it is written: "?sslmode=require" or "&sslmode=require".
  const written = new RegExp(`([?&])sslmode=${sslMode}(?=&|$)`, "g");
  const candidates = [
    // Changed where it stands, so that the database address has one SSL mode whichever value of a
    // repeated parameter a later pg goes by. Neon's database addresses have one, written so.
    before.searchParams.getAll("sslmode").length === 1 && [...query.matchAll(written)].length === 1
      ? head + query.replace(written, "$1sslmode=verify-full") + tail
      : null,
    // Else one more SSL mode at the end of the query: pg 8 goes by the last value.
    head + query + "&sslmode=verify-full" + tail
  ];

  // The first one that pg reads as the same database address with verify-full, else the database
  // address as it is. Never the case for a real one, but one that ends in a stray "%" or a control
  // character is read in another way once a parameter comes after that end.
  return (
    candidates.find((candidate) => {
      const after = candidate === null ? null : readAsPg(candidate);

      return after !== null && isWithVerifyFull(before, after);
    }) ?? connectionString
  );
}

/**
 * The database address [connectionString] as pg-connection-string reads it: with the URL parser,
 * after percent-encoding one that has a space or a "%" that starts no escape, and again with a
 * stand-in server name when it has none. Null for one whose parameters pg does not read (a Unix
 * socket) or that the URL parser cannot read.
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

    // A "socket:" database address goes by its path and ignores the SSL mode.
    return url.protocol === "socket:" ? null : url;
  } catch {
    // pg cannot read it either, and says so when it connects.
    return null;
  }
}

/** Whether pg reads the database address [after] as [before] with "sslmode=verify-full", and nothing else changed. */
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
