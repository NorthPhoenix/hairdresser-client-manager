import { defaultLocale, t } from "@hcm/shared";

type ProfileSharePageProps = {
  params: Promise<{
    token: string;
  }>;
};

export default async function ProfileSharePage({ params }: ProfileSharePageProps) {
  const { token } = await params;

  return (
    <main style={styles.shell}>
      <article style={styles.page}>
        <header style={styles.header}>
          <p style={styles.kicker}>Profile Share</p>
          <h1 style={styles.title}>{t(defaultLocale, "profileShareTitle")}</h1>
          <p style={styles.body}>{t(defaultLocale, "profileSharePlaceholder")}</p>
        </header>
        <dl style={styles.meta}>
          <div>
            <dt style={styles.label}>Token</dt>
            <dd style={styles.value}>{token}</dd>
          </div>
          <div>
            <dt style={styles.label}>English</dt>
            <dd style={styles.value}>{t("en", "profileSharePlaceholder")}</dd>
          </div>
        </dl>
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
    border: "1px solid #17130f",
    borderRadius: 8,
    margin: "0 auto",
    maxWidth: 760,
    padding: 32
  },
  header: {
    borderBottom: "1px solid #17130f",
    paddingBottom: 28
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
  body: {
    color: "#312c27",
    fontFamily: "Arial, sans-serif",
    fontSize: 18,
    lineHeight: 1.5,
    margin: 0
  },
  meta: {
    display: "grid",
    gap: 18,
    margin: "28px 0 0"
  },
  label: {
    color: "#6d6259",
    fontFamily: "Arial, sans-serif",
    fontSize: 12,
    fontWeight: 700,
    marginBottom: 6,
    textTransform: "uppercase"
  },
  value: {
    color: "#17130f",
    fontFamily: "Arial, sans-serif",
    fontSize: 16,
    margin: 0,
    overflowWrap: "anywhere"
  }
} as const;
