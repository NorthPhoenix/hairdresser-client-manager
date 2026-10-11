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

/**
 * What proxy.ts has to know of the link of a share page before the page is made: whether the link
 * is known to show nothing (it is unknown or revoked), and the language of its Client where there
 * is one. This lookup decides only how such a link is answered, so a failure of it is not the
 * answer: thrown in proxy.ts it would end the request before any page and without the headers of
 * next.config.mjs. The link then counts as not known to show nothing, and the request goes on to
 * the share page, which loads the link itself and answers for what it finds or for its own failure.
 */
export async function lookUpSharePage(token: string) {
  try {
    const link = await findShareLink(token);

    return {
      showsNothing: !link || link.revokedAt !== null,
      language: link?.client.language
    };
  } catch (error) {
    console.error("A Profile Share link could not be looked up before its page:", error);

    return {
      showsNothing: false,
      language: undefined
    };
  }
}
