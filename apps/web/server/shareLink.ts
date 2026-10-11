import { db } from "@hcm/db";
import { isProfileShareToken } from "@hcm/shared";

// Kept free of everything a page needs to show a share: proxy.ts loads this for every request.

/**
 * The request header in which proxy.ts tells the page of a link that shows nothing
 * (app/not-found.tsx) which language to answer in. The framework tells that page nothing about
 * the address it stands for.
 */
export const unavailableLanguageHeader = "x-hcm-unavailable-language";

/**
 * An address under which this server has no page and never will: a folder of `app` whose name
 * starts with `_` is not a page. What is passed on to it is answered by app/not-found.tsx.
 */
export const unknownAddress = "/_unavailable";

/**
 * The link as it is stored, working or revoked, with its Client. Null: there is no such link.
 * Whoever shows something of it has to look at `revokedAt` first: see loadProfileShare.
 */
export async function findShareLink(token: string) {
  // Only a token is looked up. A link that was cut short or changed by hand ends here, and so
  // does a character the database refuses, such as a NUL.
  if (!isProfileShareToken(token)) {
    return null;
  }

  return db.profileShare.findUnique({
    where: {
      token
    },
    include: {
      client: {
        include: {
          stylist: true
        }
      }
    }
  });
}
