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
 * last value of a parameter, so that one is the SSL mode, and the rest of the address stays exactly
 * as pg read it before. An address that asks for libpq's meaning ("uselibpqcompat=true") is
 * returned as it is, as is one without an SSL mode or with another one ("disable", "no-verify",
 * "verify-full"), and one that pg cannot read.
 */
export function verifiedTlsConnectionString(connectionString: string): string {
  const parameters = parametersAsPgReadsThem(connectionString);
  const sslMode = parameters?.getAll("sslmode").at(-1);
  const libpqCompat = parameters?.getAll("uselibpqcompat").at(-1) === "true";

  if (libpqCompat || sslMode === undefined || !verifyFullAliases.has(sslMode)) {
    return connectionString;
  }

  // The query ends where the fragment starts: at the first "#", which nothing before it can escape.
  const fragmentStart = connectionString.indexOf("#");
  const queryEnd = fragmentStart === -1 ? connectionString.length : fragmentStart;

  return connectionString.slice(0, queryEnd) + "&sslmode=verify-full" + connectionString.slice(queryEnd);
}

/**
 * The query parameters of [connectionString] as pg-connection-string reads them: with the URL
 * parser, after percent-encoding an address that has a space or a "%" that starts no escape, and
 * again with a stand-in host when the address has none. Null for an address whose parameters pg
 * does not read (a Unix socket) or cannot read at all.
 */
function parametersAsPgReadsThem(connectionString: string): URLSearchParams | null {
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
    return url.protocol === "socket:" ? null : url.searchParams;
  } catch {
    // pg cannot read it either, and says so when it connects.
    return null;
  }
}
