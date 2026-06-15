import { describe, expect, it } from "vitest";
import { buildClientReminderMessage, buildSmsComposerUrl } from "@hcm/shared";

describe("client reminder SMS composition", () => {
  it("builds an English reminder with appointment time and location", () => {
    const message = buildClientReminderMessage({
      locale: "en",
      appointmentTime: "Jun 13, 2026, 3:00 PM",
      location: "500 Salon Ave"
    });

    expect(message).toContain("Jun 13, 2026, 3:00 PM");
    expect(message).toContain("500 Salon Ave");
    expect(message).toContain("Reminder");
  });

  it("builds a Russian reminder with appointment time and location", () => {
    const message = buildClientReminderMessage({
      locale: "ru",
      appointmentTime: "13 июн. 2026 г., 15:00",
      location: "500 Salon Ave"
    });

    expect(message).toContain("13 июн. 2026 г., 15:00");
    expect(message).toContain("500 Salon Ave");
    expect(message).toContain("Напоминание");
  });

  it("opens the SMS adapter for the primary recipient phone in group reminders", () => {
    const groupParticipants = [
      {
        phone: "+15550000001",
        isPrimary: false
      },
      {
        phone: "+15550000002",
        isPrimary: true
      }
    ];
    const primaryRecipient = groupParticipants.find((participant) => participant.isPrimary);
    const message = buildClientReminderMessage({
      locale: "en",
      appointmentTime: "Jun 13, 2026, 3:00 PM",
      location: "500 Salon Ave"
    });
    const url = buildSmsComposerUrl(primaryRecipient?.phone ?? "", message);

    expect(url).toContain("sms:+15550000002");
    expect(url).toContain("body=");
    expect(url).not.toContain("+15550000001");
  });

  it("does not produce delivery tracking state", () => {
    const url = buildSmsComposerUrl("+15550000002", "Reminder");

    expect(url).toBe("sms:+15550000002?body=Reminder");
    expect(url).not.toContain("sent");
    expect(url).not.toContain("delivery");
    expect(url).not.toContain("draft");
  });
});
