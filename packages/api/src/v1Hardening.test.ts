/// <reference types="node" />

import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { buildShareImageSvg } from "./shareImage";
import {
  buildClientReminderMessage,
  getMissingLocalizationKeys,
  messageKeys,
  requestedLocale,
  t,
  themeTokens,
  type SupportedLocale
} from "@hcm/shared";

function readWorkspaceFile(path: string): string {
  return readFileSync(new URL(`../../../${path}`, import.meta.url), "utf8");
}

// A module of the web app, which has no test runner of its own: what it configures is covered here.
async function loadWebModule(path: string) {
  return (await import(/* @vite-ignore */ new URL(`../../../apps/web/${path}`, import.meta.url).href)).default;
}

type HeaderRule = { source: string; headers: { key: string; value: string }[] };

/**
 * The headers the web app's configuration sends with the answer of an address, in production
 * unless said otherwise. Of the rules that cover an address the later one counts for a header.
 */
async function webHeaders(path: string, nodeEnv = "production") {
  vi.stubEnv("NODE_ENV", nodeEnv);

  const rules: HeaderRule[] = await (await loadWebModule("next.config.mjs")).headers();
  const sent: Record<string, string> = {};

  for (const rule of rules) {
    // `:name*` stands for any number of path segments, `:name` for one.
    const source = rule.source.replace(/\/:\w+\*/g, "(?:/.*)?").replace(/:\w+/g, "[^/]+");

    if (new RegExp(`^${source}$`).test(path)) {
      for (const { key, value } of rule.headers) {
        sent[key] = value;
      }
    }
  }

  return sent;
}

// The Content-Security-Policy of an address, as the list of its directives.
async function webPolicy(path: string, nodeEnv?: string) {
  return ((await webHeaders(path, nodeEnv))["Content-Security-Policy"] ?? "").split("; ");
}

const sharePath = `/profile-shares/${"a".repeat(64)}`;

const emptyShare = {
  clientName: "Anna",
  stylistName: null,
  salonAddress: null,
  timezone: "UTC",
  upcomingAppointments: [],
  lastCompletedAppointment: null,
  includesColorFormulas: true,
  embeddedPhotos: []
};

describe("v1 acceptance hardening", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("keeps Russian and English copy present for every shared client-facing message", () => {
    expect(messageKeys.length).toBeGreaterThan(0);
    expect(getMissingLocalizationKeys()).toEqual({
      ru: [],
      en: []
    });

    for (const locale of ["ru", "en"] satisfies SupportedLocale[]) {
      for (const key of messageKeys) {
        expect(t(locale, key).trim(), `${locale}.${key}`).not.toBe("");
      }
    }
  });

  it("reads the language an address asks for, from the first value when lang is repeated", () => {
    expect(requestedLocale("en")).toBe("en");
    expect(requestedLocale("ru")).toBe("ru");
    expect(requestedLocale("EN-us")).toBe("en");
    // ?lang=en&lang=ru reaches a page as a list.
    expect(requestedLocale(["en", "ru"])).toBe("en");
    expect(requestedLocale(["ru", "en"])).toBe("ru");
    // No language asked for: the page keeps the Client's language.
    expect(requestedLocale(undefined)).toBeNull();
    expect(requestedLocale(null)).toBeNull();
    expect(requestedLocale("")).toBeNull();
    expect(requestedLocale([])).toBeNull();
    expect(requestedLocale(["", "en"])).toBeNull();
  });

  it("tells a Client in both languages that a link is no longer available", () => {
    expect(t("ru", "profileShareUnavailableTitle")).toBe("Ссылка больше не действует");
    expect(t("en", "profileShareUnavailableTitle")).toBe("This link is no longer available");
    expect(t("ru", "profileShareUnavailableBody")).not.toMatch(/[a-z]/i);
    expect(t("ru", "profileShareDescription")).not.toMatch(/[a-z]/i);
    expect(t("ru", "languageSwitchLabel")).toBe("Язык");
    expect(t("en", "languageSwitchLabel")).toBe("Language");
  });

  it("says on the home page what the product is, in both languages and with nothing to follow", () => {
    expect(t("ru", "homeTitle")).toBe("Менеджер клиентов");
    expect(t("en", "homeTitle")).toBe("Client Manager");

    for (const key of ["homeTitle", "homeAbout", "homeProfileShares"] as const) {
      expect(t("ru", key), key).not.toMatch(/[a-z]/i);
      expect(t("en", key), key).not.toMatch(/[а-яё]/i);
    }

    // The placeholder it replaces linked to a Profile Share that never existed. The only links
    // now are the two languages.
    const page = readWorkspaceFile("apps/web/app/page.tsx");

    expect(page.match(/href="[^"]*"/g)).toEqual(['href="/?lang=ru"', 'href="/?lang=en"']);
    expect(page).not.toContain("demo-token");
    expect(page).not.toContain("profileSharePlaceholder");
  });

  it("sends the security headers with every answer and does not name the framework", async () => {
    for (const path of ["/", sharePath, `${sharePath}/image`, `${sharePath}/photos/abc`, "/api/trpc/clientProfile.list", "/api/uploadthing", "/no-such-page"]) {
      const headers = await webHeaders(path);

      expect(headers["X-Content-Type-Options"], path).toBe("nosniff");
      expect(headers["X-Frame-Options"], path).toBe("DENY");
      expect(headers["Referrer-Policy"], path).toBe("no-referrer");
      expect(headers["Permissions-Policy"], path).toContain("camera=()");
      expect(headers["Strict-Transport-Security"], path).toMatch(/^max-age=\d+$/);
      expect(headers["Content-Security-Policy"], path).toContain("frame-ancestors 'none'");
    }

    expect((await loadWebModule("next.config.mjs")).poweredByHeader).toBe(false);
  });

  it("lets a page load only what this server serves, and loosens that for development only", async () => {
    const policy = await webPolicy(sharePath);

    expect(policy).toContain("default-src 'self'");
    expect(policy).toContain("script-src 'self' 'unsafe-inline'");
    expect(policy).toContain("connect-src 'self'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("base-uri 'self'");
    // The photos of a share page are served under its own link.
    expect(policy).toContain("img-src 'self' data:");
    expect(policy.join(" ")).not.toMatch(/https?:|\*|unsafe-eval|ws:/);

    const development = await webPolicy(sharePath, "development");

    expect(development).toContain("script-src 'self' 'unsafe-inline' 'unsafe-eval'");
    expect(development).toContain("connect-src 'self' ws:");
  });

  it("serves a Share Image as a picture that may run nothing", async () => {
    for (const nodeEnv of ["production", "development"]) {
      const policy = await webPolicy(`${sharePath}/image`, nodeEnv);

      expect(policy).toContain("default-src 'none'");
      expect(policy).toContain("sandbox");
      expect(policy).toContain("frame-ancestors 'none'");
      // Its photos are inside it.
      expect(policy).toContain("img-src data:");
      expect(policy.join(" ")).not.toMatch(/script-src|'self'|unsafe-eval/);
    }

    // Only the picture: the page keeps the policy of a page.
    expect(await webPolicy(sharePath)).toContain("script-src 'self' 'unsafe-inline'");
    expect(await webPolicy(`${sharePath}/photos/abc`)).toContain("default-src 'self'");
  });

  it("keeps Profile Share addresses out of search engines without closing them in robots.txt", async () => {
    for (const path of [sharePath, `${sharePath}/image`, `${sharePath}/photos/abc`, "/profile-shares/cut-short"]) {
      expect((await webHeaders(path))["X-Robots-Tag"], path).toBe("noindex, nofollow");
    }

    // A search engine reads "noindex" only where it may read the address.
    const robots = await loadWebModule("app/robots.ts");

    expect(robots()).toEqual({ rules: { userAgent: "*", disallow: "/api/" } });
    // The page says it in its head too.
    expect(readWorkspaceFile("apps/web/app/profile-shares/[token]/page.tsx")).toMatch(/robots: \{\s+index: false,\s+follow: false\s+\}/);
  });

  it("hands every Profile Share address to the proxy, also one with a dot in it", () => {
    // The matcher of proxy.ts as the framework reads it: each entry stands for a whole path.
    const matcher: string[] = JSON.parse(/matcher: (\[.*\])/.exec(readWorkspaceFile("apps/web/proxy.ts"))?.[1] ?? "[]");
    const reachesProxy = (path: string) => matcher.some((source) => new RegExp(`^${source}$`).test(path));

    // A link copied with the full stop of its sentence, or mangled otherwise, is a link that shows
    // nothing: only the proxy has its page answered whole and in the language the address asks for.
    for (const path of [sharePath, `${sharePath}.`, "/profile-shares/abc.def", `${sharePath}/image`, `${sharePath}/photos/abc`]) {
      expect(reachesProxy(path), path).toBe(true);
    }

    expect(reachesProxy("/")).toBe(true);
    expect(reachesProxy("/api/trpc/clientProfile.list")).toBe(true);
    // The framework's own files stay out.
    expect(reachesProxy("/_next/static/chunks/main.js")).toBe(false);
  });

  it("answers for a share page before the page only when its link is known to show nothing", async () => {
    const findUnique = vi.fn();
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);

    vi.doMock("@hcm/db", () => ({ db: { profileShare: { findUnique } } }));

    try {
      const { lookUpSharePage } = await import(
        /* @vite-ignore */ new URL("../../../apps/web/server/shareLink.ts", import.meta.url).href
      );
      const token = "ab".repeat(32);

      findUnique.mockResolvedValueOnce(null);
      expect(await lookUpSharePage(token)).toEqual({ showsNothing: true, language: undefined });

      findUnique.mockResolvedValueOnce({ revokedAt: new Date(), client: { language: "en" } });
      expect(await lookUpSharePage(token)).toEqual({ showsNothing: true, language: "en" });

      findUnique.mockResolvedValueOnce({ revokedAt: null, client: { language: "en" } });
      expect(await lookUpSharePage(token)).toEqual({ showsNothing: false, language: "en" });

      // Anything but a token is not looked up.
      expect(await lookUpSharePage(`${token}.`)).toEqual({ showsNothing: true, language: undefined });
      expect(findUnique).toHaveBeenCalledTimes(3);
      expect(logged).not.toHaveBeenCalled();

      // A lookup that fails does not fail the request: the page is left to load the link itself.
      findUnique.mockRejectedValueOnce(new Error("The database is away."));
      expect(await lookUpSharePage(token)).toEqual({ showsNothing: false, language: undefined });
      expect(logged).toHaveBeenCalledOnce();
    } finally {
      vi.doUnmock("@hcm/db");
      logged.mockRestore();
    }
  });

  it("uses Client language for client-facing reminders and shares", () => {
    expect(t("ru", "profileShareTitle")).toBe("Профиль клиента");
    expect(t("en", "profileShareTitle")).toBe("Client Profile");

    const russianReminder = buildClientReminderMessage({
      locale: "ru",
      appointmentTime: "13 июн. 2026 г., 15:00",
      location: "500 Salon Ave"
    });
    const englishReminder = buildClientReminderMessage({
      locale: "en",
      appointmentTime: "Jun 13, 2026, 3:00 PM",
      location: "500 Salon Ave"
    });

    expect(russianReminder).toContain("Напоминание");
    expect(russianReminder).not.toContain("Reminder");
    expect(englishReminder).toContain("Reminder");

    expect(
      buildShareImageSvg({
        ...emptyShare,
        locale: "ru"
      })
    ).toContain("Будущие записи");
    expect(
      buildShareImageSvg({
        ...emptyShare,
        locale: "en"
      })
    ).toContain("Upcoming Appointments");
  });

  it("keeps web Tailwind and the Android theme wired to shared theme tokens", () => {
    const webTailwindConfig = readWorkspaceFile("apps/web/tailwind.config.ts");
    const webGlobalCss = readWorkspaceFile("apps/web/app/globals.css");
    const androidTheme = readWorkspaceFile(
      "apps/android/app/src/main/java/com/northphoenix/hairdresserclientmanager/ui/theme/Theme.kt"
    ).toLowerCase();

    expect(webTailwindConfig).toContain("themeTokens");
    expect(webGlobalCss).toContain("@tailwind utilities");

    // Kotlin cannot import the tokens, so the Android palette must repeat the same colour values.
    for (const color of Object.values(themeTokens.colors)) {
      expect(androidTheme, color).toContain(`0xff${color.slice(1).toLowerCase()}`);
    }
  });

  it("documents optional provider configuration and future-agent entry points", () => {
    const setupDoc = readWorkspaceFile("docs/setup.md");
    const acceptanceDoc = readWorkspaceFile("docs/v1-acceptance.md");

    expect(setupDoc).toContain("Expected missing-env behavior");
    expect(acceptanceDoc).toContain("CONTEXT.md");
    expect(acceptanceDoc).toContain("docs/adr/");
    expect(acceptanceDoc).toContain("UploadThing");
    expect(acceptanceDoc).toContain("Google Calendar");
    expect(acceptanceDoc).toContain("Stylist Reminder");
    expect(acceptanceDoc).toContain("Profile Share");
  });
});
