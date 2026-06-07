import Link from "next/link";
import { defaultLocale, t } from "@hcm/shared";

export default function WebIndexPage() {
  return (
    <main style={styles.shell}>
      <section style={styles.panel}>
        <p style={styles.kicker}>Hairdresser Client Manager</p>
        <h1 style={styles.title}>{t(defaultLocale, "profileShareTitle")}</h1>
        <p style={styles.body}>{t(defaultLocale, "profileSharePlaceholder")}</p>
        <Link href="/profile-shares/demo-token" style={styles.link}>
          /profile-shares/demo-token
        </Link>
      </section>
    </main>
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
    fontSize: 44,
    lineHeight: 1,
    margin: "14px 0"
  },
  body: {
    color: "#312c27",
    fontFamily: "Arial, sans-serif",
    fontSize: 18,
    lineHeight: 1.5,
    margin: "0 0 24px"
  },
  link: {
    color: "#7d2f1b",
    fontFamily: "Arial, sans-serif",
    fontSize: 16,
    fontWeight: 700
  }
} as const;
