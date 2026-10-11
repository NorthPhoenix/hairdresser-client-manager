import type { ReactNode } from "react";
import type { SupportedLocale } from "@hcm/shared";

/**
 * The document of a page, in the language the page is written in. Every page renders it around
 * what it shows, and so does not-found.tsx: a layout is not told the language, which a Profile
 * Share page takes from its Client and from `?lang=`.
 */
export function HtmlDocument({ locale, children }: { locale: SupportedLocale; children: ReactNode }) {
  return (
    <html lang={locale}>
      <body>{children}</body>
    </html>
  );
}
