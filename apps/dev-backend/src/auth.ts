const devTokenPattern = /^Bearer (dev_[A-Za-z0-9_-]{1,64})$/;

// The dev backend trusts `Authorization: Bearer dev_<name>` and uses the token
// itself as the Clerk user id, so each name gets its own lazily created Stylist.
export function getDevUserId(authorization: string | null): string | null {
  return authorization?.match(devTokenPattern)?.[1] ?? null;
}
