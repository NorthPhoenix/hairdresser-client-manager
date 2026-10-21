import type { Db } from "./context";

// A Family groups Clients of one Stylist (CONTEXT.md: Family). A Client is in at most one
// (`Client.familyId`) and may have a short role in it (`Client.familyRole`). A Family always has
// at least two Clients: whoever would be left alone in it leaves it, and it is deleted. These
// helpers run inside the Stylist lock (ADR 0014).

/** What a Client's row says of their Family. */
export type FamilyFields = {
  id: string;
  familyId?: string | null;
  familyRole?: string | null;
};

/** A typed role, or null for none: an empty role is no role. */
export function storedRole(role: string | null | undefined) {
  const trimmed = (role ?? "").trim();

  return trimmed ? trimmed : null;
}

/**
 * Deletes [familyId] when fewer than two Clients are left in it: the one who is left leaves it
 * and has no role any more. Answers the ids of the Clients it took out.
 */
export async function dissolveIfAlone(db: Db, stylistId: string, familyId: string | null | undefined): Promise<string[]> {
  if (!familyId) {
    return [];
  }

  const members = await db.client.findMany({
    where: {
      stylistId,
      familyId
    },
    select: {
      id: true
    }
  });

  if (members.length >= 2) {
    return [];
  }

  if (members.length > 0) {
    await db.client.updateMany({
      where: {
        stylistId,
        familyId
      },
      data: {
        familyId: null,
        familyRole: null
      }
    });
  }

  await db.family.deleteMany({
    where: {
      id: familyId,
      stylistId
    }
  });

  return members.map((member) => member.id);
}

/**
 * Takes a Client out of their Family, with their role, and deletes the Family when one Client
 * would be left in it. Answers the ids of every Client it changed.
 */
export async function leaveFamily(db: Db, stylistId: string, client: FamilyFields): Promise<string[]> {
  if (!client.familyId) {
    return [];
  }

  await db.client.update({
    where: {
      id: client.id
    },
    data: {
      familyId: null,
      familyRole: null
    }
  });

  return [client.id, ...(await dissolveIfAlone(db, stylistId, client.familyId))];
}
