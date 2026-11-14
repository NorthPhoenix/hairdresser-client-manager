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
});
