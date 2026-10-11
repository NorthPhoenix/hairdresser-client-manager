import type { Metadata } from "next";
import { headers } from "next/headers";
import { normalizeLocale, t } from "@hcm/shared";
import { unavailableLanguageHeader } from "../server/shareLink";
import { HtmlDocument } from "./htmlDocument";

// The language proxy.ts chose for the address that was asked for.
async function pageLocale() {
  return normalizeLocale((await headers()).get(unavailableLanguageHeader) ?? undefined);
}

export async function generateMetadata(): Promise<Metadata> {
  const locale = await pageLocale();

  return {
    title: t(locale, "profileShareUnavailableTitle"),
    description: t(locale, "profileShareUnavailableBody")
  };
}

/**
 * What a Client sees behind a Profile Share link that was revoked, was cut short or never existed,
 * and behind any other address this server does not know. Answered with status 404.
 */
export default async function UnavailablePage() {
  const locale = await pageLocale();

  return (
    <HtmlDocument locale={locale}>
      <main style={styles.shell}>
        <section style={styles.panel}>
          <p style={styles.kicker}>{t(locale, "profileShareTitle")}</p>
          <h1 style={styles.title}>{t(locale, "profileShareUnavailableTitle")}</h1>
          <p style={styles.body}>{t(locale, "profileShareUnavailableBody")}</p>
          <nav style={styles.languageNav} aria-label={t(locale, "languageSwitchLabel")}>
            {/* Only a query: the links stay on the address that was asked for. */}
            <a style={styles.languageLink} href="?lang=ru">
              {t(locale, "switchToRussian")}
            </a>
            <a style={styles.languageLink} href="?lang=en">
              {t(locale, "switchToEnglish")}
            </a>
          </nav>
        </section>
      </main>
    </HtmlDocument>
  );
}

const styles = {
  shell: {
    background: "#f7f1e8",
    minHeight: "100vh",
    padding: 24
  },
  panel: {
    margin: "0 auto",
    maxWidth: 760,
    overflowWrap: "anywhere",
    wordBreak: "break-word"
  },
  kicker: {
    color: "#6d6259",
    fontFamily: "Arial, sans-serif",
    fontSize: 13,
    fontWeight: 700,
    letterSpacing: 0,
    margin: 0,
    textTransform: "uppercase"
  },
  title: {
    fontSize: "clamp(28px, 8vw, 48px)",
    lineHeight: 1.1,
    margin: "14px 0"
  },
  body: {
    color: "#312c27",
    fontFamily: "Arial, sans-serif",
    fontSize: 16,
    lineHeight: 1.5,
    margin: "0 0 20px"
  },
  languageNav: {
    display: "flex",
    flexWrap: "wrap",
    gap: 12
  },
  languageLink: {
    border: "1px solid #7d2f1b",
    borderRadius: 6,
    color: "#7d2f1b",
    fontFamily: "Arial, sans-serif",
    fontSize: 14,
    fontWeight: 700,
    padding: "8px 10px",
    textDecoration: "none"
  }
} as const;
