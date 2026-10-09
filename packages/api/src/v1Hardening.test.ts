/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { buildShareImageSvg } from "./shareImage";
import {
  buildClientReminderMessage,
  getMissingLocalizationKeys,
  messageKeys,
  t,
  themeTokens,
  type SupportedLocale
} from "@hcm/shared";

function readWorkspaceFile(path: string): string {
  return readFileSync(new URL(`../../../${path}`, import.meta.url), "utf8");
}

describe("v1 acceptance hardening", () => {
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
        clientName: "Anna",
        locale: "ru",
        nextAppointment: null,
        lastCompletedAppointment: null,
        services: []
      })
    ).toContain("Будущие записи");
    expect(
      buildShareImageSvg({
        clientName: "Anna",
        locale: "en",
        nextAppointment: null,
        lastCompletedAppointment: null,
        services: []
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
