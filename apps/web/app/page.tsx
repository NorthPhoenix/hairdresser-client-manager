import type { Metadata } from "next";
import { defaultLocale, requestedLocale, t } from "@hcm/shared";
import { HtmlDocument } from "./htmlDocument";

type WebIndexPageProps = {
  searchParams: Promise<{
    // A list when the address repeats it.
    lang?: string | string[];
  }>;
};

// The primary language, unless the address asks for the other one.
async function pageLocale({ searchParams }: WebIndexPageProps) {
  return requestedLocale((await searchParams).lang) ?? defaultLocale;
}

export async function generateMetadata(props: WebIndexPageProps): Promise<Metadata> {
  const locale = await pageLocale(props);

  return {
    title: t(locale, "homeTitle"),
    description: t(locale, "homeAbout")
  };
}

/**
 * What the site answers without a link, for instance to a Client who cut a Profile Share link
 * down to its first part: what the product is, with nothing to follow but the other language.
 */
export default async function WebIndexPage(props: WebIndexPageProps) {
  const locale = await pageLocale(props);

  return (
    <HtmlDocument locale={locale}>
      <main style={styles.shell}>
        <section style={styles.panel}>
          <h1 style={styles.title}>{t(locale, "homeTitle")}</h1>
          <p style={styles.body}>{t(locale, "homeAbout")}</p>
          <p style={styles.body}>{t(locale, "homeProfileShares")}</p>
          <nav style={styles.languageNav} aria-label={t(locale, "languageSwitchLabel")}>
            <a style={styles.languageLink} href="/?lang=ru">
              {t(locale, "switchToRussian")}
            </a>
            <a style={styles.languageLink} href="/?lang=en">
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
    alignItems: "center",
    background: "#f7f1e8",
    display: "flex",
    minHeight: "100vh",
    padding: 24
  },
  panel: {
    border: "1px solid #17130f",
    borderRadius: 8,
    maxWidth: 680,
    padding: 32
  },
  title: {
    fontSize: "clamp(28px, 8vw, 44px)",
    lineHeight: 1.1,
    margin: "0 0 14px"
  },
  body: {
    color: "#312c27",
    fontFamily: "Arial, sans-serif",
    fontSize: 18,
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
