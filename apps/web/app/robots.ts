import type { MetadataRoute } from "next";

/**
 * /robots.txt. Profile Share addresses are left open on purpose: they answer "noindex" themselves
 * (next.config.mjs, and the share page in its head), and a search engine reads that only where it
 * may read the address. Closed here, an address that was posted somewhere could still be listed.
 */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      disallow: "/api/"
    }
  };
}
