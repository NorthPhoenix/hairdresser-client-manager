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
 * An address that asks for libpq's meaning ("uselibpqcompat=true") is left as it is, as is one
 * without an SSL mode or with another one ("disable", "no-verify", "verify-full"). Only the
 * "sslmode" parameter changes: every other byte of the address stays as it was.
 */
export function verifiedTlsConnectionString(connectionString: string): string {
  // A Unix socket ("/var/run/postgresql dbname") has no parameters.
  if (connectionString.startsWith("/")) {
    return connectionString;
  }

  const queryStart = connectionString.indexOf("?");

  if (queryStart === -1) {
    return connectionString;
  }

  const fragmentStart = connectionString.indexOf("#", queryStart);
  const queryEnd = fragmentStart === -1 ? connectionString.length : fragmentStart;
  const query = connectionString.slice(queryStart + 1, queryEnd);
  const parameters = new URLSearchParams(query);

  // A parameter given more than once counts with its last value, as pg reads it.
  const sslMode = parameters.getAll("sslmode").at(-1);
  const libpqCompat = parameters.getAll("uselibpqcompat").at(-1) === "true";

  if (libpqCompat || sslMode === undefined || !verifyFullAliases.has(sslMode)) {
    return connectionString;
  }

  const rewritten = query
    .split("&")
    .map((pair) => (parameterName(pair) === "sslmode" ? "sslmode=verify-full" : pair))
    .join("&");

  return connectionString.slice(0, queryStart + 1) + rewritten + connectionString.slice(queryEnd);
}

/** The name of one "name=value" pair of a query, decoded as pg decodes it. */
function parameterName(pair: string): string | undefined {
  return new URLSearchParams(pair).keys().next().value;
}
