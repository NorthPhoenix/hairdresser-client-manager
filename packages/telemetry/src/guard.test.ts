import { describe, expect, it } from "vitest";
import { cleanString, guardFields, normalizeKey } from "./guard";

describe("the privacy guard", () => {
  it("flattens nested objects into dotted snake_case keys", () => {
    expect(guardFields({ client: { visitCount: 3, isNew: true }, appointmentId: "ckabc" }).fields).toEqual({
      "client.visit_count": 3,
      "client.is_new": true,
      appointment_id: "ckabc"
    });
  });

  it("refuses keys that name personal data, credentials or contents", () => {
    const { fields, blocked } = guardFields({
      "client.name": "Анна",
      "client.phone": "+7 999 123-45-67",
      notes: "колорирование",
      shareToken: "abc",
      "photo.url": "https://x.ufs.sh/f/key",
      authorization: "Bearer x",
      "appointment.id": "ck1"
    });

    expect(fields).toEqual({ "appointment.id": "ck1" });
    expect(blocked).toEqual(
      expect.arrayContaining(["client.name:personal", "client.phone:personal", "notes:personal", "share_token:personal", "photo.url:personal", "authorization:personal"])
    );
  });

  it("lets measures of personal data through, as numbers and booleans only", () => {
    const { fields, blocked } = guardFields({
      "client.note_length": 42,
      "client.has_phone": true,
      "photo.url_count": 2,
      "client.phone_count": "+7 999 123 45 67",
      "share.token_hash": "a1b2c3d4e5f6",
      "share.other_hash": "not hex at all"
    });

    expect(fields).toEqual({
      "client.note_length": 42,
      "client.has_phone": true,
      "photo.url_count": 2,
      "share.token_hash": "a1b2c3d4e5f6"
    });
    expect(blocked).toEqual(["client.phone_count:value", "share.other_hash:value"]);
  });

  it("keeps the keys that the event writes itself out of reach", () => {
    const { fields, blocked } = guardFields({ outcome: "success", "error.type": "x", "db.query_count": 0, trace_id: "x", "trpc.procedure": "client.list" });

    expect(fields).toEqual({ "trpc.procedure": "client.list" });
    expect(blocked).toHaveLength(4);
  });

  it("caps strings and blanks what looks like an email address or a phone number", () => {
    expect(cleanString("x".repeat(500)).length).toBe(200);
    expect(cleanString("sent to anna@example.com")).toBe("sent to [email]");
    expect(cleanString("call +7 (999) 123-45-67 now")).toBe("call [phone] now");
    expect(cleanString("8 999 123 45 67")).toBe("[phone]");
    // Dates, times, ids and versions are left as they are.
    expect(cleanString("2026-11-14T10:52:00.000Z")).toBe("2026-11-14T10:52:00.000Z");
    expect(cleanString("cmhz1k2l30000a1b2c3d4e5f6")).toBe("cmhz1k2l30000a1b2c3d4e5f6");
    expect(cleanString("1.16.0")).toBe("1.16.0");
  });

  it("drops values it cannot record and arrays of mixed or refused values", () => {
    const { fields, blocked } = guardFields({
      count: Number.NaN,
      ids: ["a", "b"],
      mixed: [1, "a"],
      when: new Date("2026-11-14T00:00:00Z"),
      big: 12n,
      fn: () => 1,
      nothing: null
    });

    expect(fields).toEqual({ ids: ["a", "b"], when: "2026-11-14T00:00:00.000Z", big: 12 });
    expect(blocked).toEqual(["count:value", "mixed:value", "fn:value"]);
  });

  it("never throws, whatever it is given", () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    const hostile = Object.defineProperty({}, "boom", {
      enumerable: true,
      get() {
        throw new Error("getter");
      }
    });

    expect(() => guardFields(circular)).not.toThrow();
    expect(() => guardFields(hostile)).not.toThrow();
    expect(() => guardFields(null as never)).not.toThrow();
    expect(() => guardFields({ [Symbol("s")]: 1, a: { b: { c: { d: { e: 1 } } } } })).not.toThrow();
    expect(guardFields(circular).blocked.some((key) => key.endsWith(":too_deep"))).toBe(true);
  });

  it("normalizes keys", () => {
    expect(normalizeKey("stylistTransaction.lockWaitMs")).toBe("stylist_transaction.lock_wait_ms");
    expect(normalizeKey("  Weird Key!! ")).toBe("weird_key");
  });

  it("blanks phone numbers as people write them", () => {
    for (const phone of ["+7 (912) 345-67-89", "+7 912 345 67 89", "+79123456789", "89123456789", "8 (912) 345-67-89", "8-912-345-67-89", "(415) 555-0123", "415-555-0123", "415.555.0123", "+1 415 555 0123", "4155550123", "+44 20 7946 0958"]) {
      expect(cleanString(`call ${phone} today`), phone).toBe("call [phone] today");
      expect(cleanString(phone), phone).toBe("[phone]");
    }
  });

  it("leaves ids, versions, timestamps and stack positions alone", () => {
    for (const technical of [
      "iad1::iad1::8k2xz-1731590000000-3f8a2b1c9d0e",
      "fra1::iad1::abcde-1700000000000-0123",
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36",
      "okhttp/4.12.0",
      "Build/UP1A.231005.007",
      "550e8400-e29b-41d4-a716-446655440000",
      "at zr.request (/app/node_modules/@prisma/client/runtime/client.js:65:7288)",
      "1731590000000",
      "120.0.6099.230"
    ]) {
      expect(cleanString(technical), technical).toBe(technical);
    }
  });

  it("records the request id and the User-Agent as they are", () => {
    const fields = {
      "request.id": "iad1::iad1::8k2xz-1731590000000-3f8a2b1c9d0e",
      "http.user_agent": "Mozilla/5.0 (Linux; Android 14) Chrome/131.0.6778.135 Mobile Safari/537.36"
    };

    expect(guardFields(fields).fields).toEqual(fields);
  });
});
