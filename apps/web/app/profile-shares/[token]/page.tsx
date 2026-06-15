import { notFound } from "next/navigation";
import { db } from "@hcm/db";
import { normalizeLocale, t, type SupportedLocale } from "@hcm/shared";

type ProfileSharePageProps = {
  params: Promise<{
    token: string;
  }>;
  searchParams: Promise<{
    lang?: string;
  }>;
};

type SharedAppointment = {
  id: string;
  startsAt: Date;
  endsAt: Date | null;
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
};

function formatAppointmentTime(locale: SupportedLocale, appointment: SharedAppointment): string {
  const dateLocale = locale === "ru" ? "ru-RU" : "en-US";
  const start = appointment.startsAt.toLocaleString(dateLocale, {
    dateStyle: "medium",
    timeStyle: "short"
  });
  const end = appointment.endsAt?.toLocaleTimeString(dateLocale, {
    timeStyle: "short"
  });

  return [start, end].filter(Boolean).join(" - ");
}

export default async function ProfileSharePage({ params, searchParams }: ProfileSharePageProps) {
  const { token } = await params;
  const { lang } = await searchParams;
  const share = await db.profileShare.findFirst({
    where: {
      token,
      revokedAt: null
    },
    include: {
      client: true
    }
  });

  if (!share) {
    notFound();
  }

  const locale = lang ? normalizeLocale(lang) : share.client.language;
  const upcomingAppointments = await db.appointment.findMany({
    where: {
      stylistId: share.client.stylistId,
      status: "scheduled",
      startsAt: {
        gte: new Date()
      },
      participants: {
        some: {
          clientId: share.clientId
        }
      }
    },
    orderBy: {
      startsAt: "asc"
    }
  });
  const lastCompletedAppointment = await db.appointment.findFirst({
    where: {
      stylistId: share.client.stylistId,
      status: "completed",
      participants: {
        some: {
          clientId: share.clientId
        }
      }
    },
    include: {
      services: {
        where: {
          clientId: share.clientId
        },
        include: {
          colorFormulas: {
            orderBy: {
              createdAt: "asc"
            }
          }
        },
        orderBy: {
          createdAt: "asc"
        }
      }
    },
    orderBy: {
      startsAt: "desc"
    }
  });
  const lastCompletedServices = lastCompletedAppointment?.services ?? [];

  return (
    <main style={styles.shell}>
      <article style={styles.page}>
        <header style={styles.header}>
          <p style={styles.kicker}>Profile Share</p>
          <h1 style={styles.title}>{share.client.name}</h1>
          <nav style={styles.languageNav} aria-label="Language">
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
          {upcomingAppointments.length > 0 ? (
            <div style={styles.list}>
              {upcomingAppointments.map((appointment) => (
                <div key={appointment.id} style={styles.item}>
                  <p style={styles.itemTitle}>{formatAppointmentTime(locale, appointment)}</p>
                  <p style={styles.body}>
                    {appointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon")}
                    {appointment.locationAddress ? ` · ${appointment.locationAddress}` : ""}
                  </p>
                </div>
              ))}
            </div>
          ) : (
            <p style={styles.body}>{t(locale, "profileShareNoUpcoming")}</p>
          )}
        </section>

        <section style={styles.section}>
          <h2 style={styles.sectionTitle}>{t(locale, "profileShareLastCompletedTitle")}</h2>
          {lastCompletedAppointment ? (
            <div style={styles.item}>
              <p style={styles.itemTitle}>{formatAppointmentTime(locale, lastCompletedAppointment)}</p>
              <p style={styles.body}>
                {lastCompletedAppointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon")}
                {lastCompletedAppointment.locationAddress ? ` · ${lastCompletedAppointment.locationAddress}` : ""}
              </p>

              <h3 style={styles.subsectionTitle}>{t(locale, "profileShareServicesTitle")}</h3>
              {lastCompletedServices.length > 0 ? (
                <div style={styles.list}>
                  {lastCompletedServices.map((service) => (
                    <div key={service.id} style={styles.service}>
                      <p style={styles.itemTitle}>{service.name}</p>
                      {service.colorFormulas.map((formula) => (
                        <p key={formula.id} style={styles.body}>
                          {formula.placement ? `${formula.placement}: ${formula.formula}` : formula.formula}
                        </p>
                      ))}
                    </div>
                  ))}
                </div>
              ) : (
                <p style={styles.body}>{t(locale, "profileShareNoCompleted")}</p>
              )}

              <h3 style={styles.subsectionTitle}>{t(locale, "profileSharePhotosTitle")}</h3>
              <p style={styles.body}>{t(locale, "profileSharePhotosEmpty")}</p>
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
  page: {
    margin: "0 auto",
    maxWidth: 760
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
  title: {
    fontSize: 48,
    lineHeight: 1,
    margin: "14px 0"
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
