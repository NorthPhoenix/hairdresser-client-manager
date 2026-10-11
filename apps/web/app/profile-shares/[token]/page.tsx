import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { formatAppointmentTime, sharedPhotoName, type ProfileShareContent } from "@hcm/api";
import { requestedLocale, t, type SupportedLocale } from "@hcm/shared";
import { loadProfileShare } from "../../../server/profileShare";
import { HtmlDocument } from "../../htmlDocument";

type ProfileSharePageProps = {
  params: Promise<{
    token: string;
  }>;
  searchParams: Promise<{
    // A list when the address repeats it.
    lang?: string | string[];
  }>;
};

// The title and the description are in the language of the page. A link that shows nothing is
// named by not-found.tsx.
export async function generateMetadata({ params, searchParams }: ProfileSharePageProps): Promise<Metadata> {
  const share = await loadProfileShare((await params).token);

  if (!share) {
    return {};
  }

  const locale = requestedLocale((await searchParams).lang) ?? share.language;

  return {
    title: t(locale, "profileShareTitle"),
    description: t(locale, "profileShareDescription"),
    // For whoever holds the link, never for a search engine (next.config.mjs says so in a header too).
    robots: {
      index: false,
      follow: false
    }
  };
}

function appointmentLocation(locale: SupportedLocale, appointment: { locationType: "inSalon" | "atHome"; locationAddress: string | null }) {
  return (
    <>
      {appointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon")}
      {appointment.locationAddress ? ` · ${appointment.locationAddress}` : ""}
    </>
  );
}

export default async function ProfileSharePage({ params, searchParams }: ProfileSharePageProps) {
  const { token } = await params;
  const { lang } = await searchParams;
  const share = await loadProfileShare(token);

  if (!share) {
    notFound();
  }

  const locale = requestedLocale(lang) ?? share.language;

  return <HtmlDocument locale={locale}>{profileShare(token, locale, share.content)}</HtmlDocument>;
}

// Called, not rendered as a component of its own: in development the framework sends what a
// component is given to the browser, and `content` holds the addresses of the stored photos.
function profileShare(token: string, locale: SupportedLocale, content: ProfileShareContent) {
  const completed = content.lastCompletedAppointment;
  const photoCaptions = {
    before: t(locale, "profileSharePhotoBefore"),
    after: t(locale, "profileSharePhotoAfter"),
    other: null
  };

  return (
    <main style={styles.shell}>
      <article style={styles.page}>
        <header style={styles.header}>
          <p style={styles.kicker}>{t(locale, "profileShareTitle")}</p>
          <h1 style={styles.title}>{content.clientName}</h1>
          {content.stylistName || content.salonAddress ? (
            <div style={styles.from}>
              {content.stylistName ? (
                <p style={styles.body}>{t(locale, "profileShareStylist", { name: content.stylistName })}</p>
              ) : null}
              {content.salonAddress ? (
                <p style={styles.body}>{t(locale, "profileShareSalon", { address: content.salonAddress })}</p>
              ) : null}
            </div>
          ) : null}
          <nav style={styles.languageNav} aria-label={t(locale, "languageSwitchLabel")}>
            <a style={styles.languageLink} href={`/profile-shares/${token}?lang=ru`}>
              {t(locale, "switchToRussian")}
            </a>
            <a style={styles.languageLink} href={`/profile-shares/${token}?lang=en`}>
              {t(locale, "switchToEnglish")}
            </a>
          </nav>
        </header>

        <section style={styles.section}>
          <h2 style={styles.sectionTitle}>{t(locale, "profileShareUpcomingTitle")}</h2>
          {content.upcomingAppointments.length > 0 ? (
            <div style={styles.list}>
              {content.upcomingAppointments.map((appointment, index) => (
                <div key={index} style={styles.item}>
                  <p style={styles.itemTitle}>{formatAppointmentTime(locale, content.timezone, appointment)}</p>
                  <p style={styles.body}>{appointmentLocation(locale, appointment)}</p>
                </div>
              ))}
            </div>
          ) : (
            <p style={styles.body}>{t(locale, "profileShareNoUpcoming")}</p>
          )}
        </section>

        <section style={styles.section}>
          <h2 style={styles.sectionTitle}>{t(locale, "profileShareLastCompletedTitle")}</h2>
          {completed ? (
            <div style={styles.item}>
              <p style={styles.itemTitle}>{formatAppointmentTime(locale, content.timezone, completed)}</p>
              <p style={styles.body}>{appointmentLocation(locale, completed)}</p>

              <h3 style={styles.subsectionTitle}>
                {t(locale, content.includesColorFormulas ? "profileShareServicesTitle" : "profileShareServicesOnlyTitle")}
              </h3>
              {completed.services.length > 0 ? (
                <div style={styles.list}>
                  {completed.services.map((service, serviceIndex) => (
                    <div key={serviceIndex} style={styles.service}>
                      <p style={styles.itemTitle}>{service.name}</p>
                      {service.colorFormulas.map((formula, formulaIndex) => (
                        <p key={formulaIndex} style={formulaStyle}>
                          {formula.placement ? `${formula.placement}: ${formula.formula}` : formula.formula}
                        </p>
                      ))}
                    </div>
                  ))}
                </div>
              ) : (
                <p style={styles.body}>{t(locale, "profileShareNoServices")}</p>
              )}

              {completed.photos.length > 0 ? (
                <>
                  <h3 style={styles.subsectionTitle}>{t(locale, "profileSharePhotosTitle")}</h3>
                  <div style={styles.photoGrid}>
                    {completed.photos.map((photo, index) => {
                      // Served by this share (photos/[photo]/route.ts), never from the stored file's own address.
                      const photoLink = `/profile-shares/${token}/photos/${sharedPhotoName(token, photo)}`;

                      return (
                        <figure key={index} style={styles.photoFigure}>
                          <a href={photoLink}>
                            <img
                              alt={photoCaptions[photo.category] ?? t(locale, "profileSharePhotosTitle")}
                              loading="lazy"
                              src={photoLink}
                              style={styles.photo}
                            />
                          </a>
                          {photoCaptions[photo.category] ? (
                            <figcaption style={styles.photoCaption}>{photoCaptions[photo.category]}</figcaption>
                          ) : null}
                        </figure>
                      );
                    })}
                  </div>
                </>
              ) : null}
            </div>
          ) : (
            <p style={styles.body}>{t(locale, "profileShareNoCompleted")}</p>
          )}
        </section>
      </article>
    </main>
  );
}

const styles = {
  shell: {
    background: "#f7f1e8",
    minHeight: "100vh",
    padding: 24
  },
  // No text widens the page: a word longer than its line is broken (the Client name on a phone,
  // or a Color Formula without a space). `wordBreak` says the same to browsers that do not know
  // `overflowWrap: "anywhere"`.
  page: {
    margin: "0 auto",
    maxWidth: 760,
    overflowWrap: "anywhere",
    wordBreak: "break-word"
  },
  header: {
    borderBottom: "1px solid #17130f",
    paddingBottom: 24
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
  // Smaller on a phone, so that a long surname stays whole on its line.
  title: {
    fontSize: "clamp(28px, 8vw, 48px)",
    lineHeight: 1.1,
    margin: "14px 0"
  },
  from: {
    marginBottom: 14
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
  },
  section: {
    borderBottom: "1px solid #d8c5ad",
    padding: "28px 0"
  },
  sectionTitle: {
    fontSize: 26,
    margin: "0 0 16px"
  },
  subsectionTitle: {
    fontSize: 18,
    margin: "20px 0 10px"
  },
  list: {
    display: "grid",
    gap: 12
  },
  item: {
    border: "1px solid #d8c5ad",
    borderRadius: 8,
    padding: 16
  },
  service: {
    border: "1px solid #d8c5ad",
    borderRadius: 6,
    padding: 12
  },
  photoGrid: {
    alignItems: "start",
    display: "grid",
    gap: 12,
    gridTemplateColumns: "repeat(auto-fill, minmax(min(200px, 100%), 1fr))"
  },
  photoFigure: {
    margin: 0
  },
  // The whole photo, not a cut of it.
  photo: {
    borderRadius: 6,
    display: "block",
    height: "auto",
    width: "100%"
  },
  photoCaption: {
    color: "#6d6259",
    fontFamily: "Arial, sans-serif",
    fontSize: 13,
    marginTop: 4
  },
  itemTitle: {
    color: "#17130f",
    fontFamily: "Arial, sans-serif",
    fontSize: 16,
    fontWeight: 700,
    margin: "0 0 6px"
  },
  body: {
    color: "#312c27",
    fontFamily: "Arial, sans-serif",
    fontSize: 16,
    lineHeight: 1.5,
    margin: 0
  }
} as const;

// A Color Formula keeps the line breaks the Stylist typed.
const formulaStyle = { ...styles.body, whiteSpace: "pre-line" } as const;
