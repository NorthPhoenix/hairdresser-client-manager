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
 * An address that asks for libpq's meaning ("uselibpqcompat=true") is returned as it is, as is one
 * without an SSL mode or with another one ("disable", "no-verify", "verify-full"), and one that
 * cannot be read. A changed address is the one pg reads (see [asPgReadsIt]) with only its "sslmode"
 * parameters changed.
 */
export function verifiedTlsConnectionString(connectionString: string): string {
  // pg reads a Unix socket ("/var/run/postgresql dbname") without parameters.
  if (connectionString.startsWith("/")) {
    return connectionString;
  }

  let address: string;

  try {
    address = asPgReadsIt(connectionString);
  } catch {
    // Text pg cannot read either: it says so when it connects.
    return connectionString;
  }

  const queryStart = address.indexOf("?");
  const fragmentStart = address.indexOf("#");

  // No query, or a "?" in the fragment, which is not one.
  if (queryStart === -1 || (fragmentStart !== -1 && fragmentStart < queryStart)) {
    return connectionString;
  }

  const queryEnd = fragmentStart === -1 ? address.length : fragmentStart;
  const query = address.slice(queryStart + 1, queryEnd);
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

  return address.slice(0, queryStart + 1) + rewritten + address.slice(queryEnd);
}

/**
 * [connectionString] as pg-connection-string hands it to the URL parser, and as that parser then
 * reads it: percent-encoded first when it has a space or a "%" that starts no escape, then without
 * tabs and line breaks anywhere and without control characters and spaces at either end, such as
 * a line break left at the end of an environment variable. pg connects to this address exactly as
 * to [connectionString], and reads no part of it again in another way.
 */
function asPgReadsIt(connectionString: string): string {
  const encoded = / |%[^a-f0-9]|%[a-f0-9][^a-f0-9]/i.test(connectionString)
    ? encodeURI(connectionString).replace(/%25(\d\d)/g, "%$1")
    : connectionString;

  return encoded.replace(/[\t\n\r]/g, "").replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, "");
}

/** The name of one "name=value" pair of a query, decoded as the URL parser decodes it. */
function parameterName(pair: string): string | undefined {
  return new URLSearchParams(pair).keys().next().value;
}
