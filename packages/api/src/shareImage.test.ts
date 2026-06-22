import { describe, expect, it } from "vitest";
import { buildShareImageSvg } from "./shareImage";

describe("share image", () => {
  it("renders the selected language and allowed Client-scoped details", () => {
    const svg = buildShareImageSvg({
      clientName: "Anna",
      locale: "en",
      nextAppointment: {
        startsAt: new Date("2026-06-13T15:00:00.000Z"),
        endsAt: null,
        locationType: "inSalon",
        locationAddress: "500 Salon Ave"
      },
      lastCompletedAppointment: {
        startsAt: new Date("2026-06-01T15:00:00.000Z"),
        endsAt: null,
        locationType: "atHome",
        locationAddress: "Client appointment location"
      },
      services: [
        {
          name: "Color",
          colorFormulas: [
            {
              formula: "7N + 20 vol",
              placement: "Roots"
            }
          ]
        }
      ]
    });

    expect(svg).toContain("<svg");
    expect(svg).toContain("Upcoming Appointments");
    expect(svg).toContain("Anna");
    expect(svg).toContain("Color");
    expect(svg).toContain("Roots: 7N + 20 vol");
    expect(svg).toContain("500 Salon Ave");
  });

  it("uses Russian labels when requested", () => {
    const svg = buildShareImageSvg({
      clientName: "Anna",
      locale: "ru",
      nextAppointment: null,
      lastCompletedAppointment: null,
      services: []
    });

    expect(svg).toContain("Будущие записи");
    expect(svg).toContain("Будущих записей нет.");
  });

  it("excludes private fields and custom branding from static output", () => {
    const svg = buildShareImageSvg({
      clientName: "Anna",
      locale: "en",
      nextAppointment: null,
      lastCompletedAppointment: null,
      services: [
        {
          name: "Cut",
          colorFormulas: []
        }
      ]
    });

    expect(svg).not.toContain("+15551234567");
    expect(svg).not.toContain("anna@example.com");
    expect(svg).not.toContain("service note");
    expect(svg).not.toContain("$85");
    expect(svg).not.toContain("logo");
    expect(svg).not.toContain("<script");
  });

  it("expands the canvas for many services and formulas", () => {
    const svg = buildShareImageSvg({
      clientName: "Anna",
      locale: "en",
      nextAppointment: null,
      lastCompletedAppointment: {
        startsAt: new Date("2026-06-01T15:00:00.000Z"),
        endsAt: null,
        locationType: "inSalon",
        locationAddress: null
      },
      services: Array.from({ length: 6 }, (_, serviceIndex) => ({
        name: `Service ${serviceIndex + 1}`,
        colorFormulas: Array.from({ length: 4 }, (_, formulaIndex) => ({
          formula: `Formula ${formulaIndex + 1}`,
          placement: `Placement ${formulaIndex + 1}`
        }))
      }))
    });

    const height = Number(svg.match(/height="(\d+)"/)?.[1]);
    const footerY = Number(svg.match(/y="(\d+)"[^>]*>No stored photos/)?.[1]);

    expect(height).toBeGreaterThan(1600);
    expect(footerY).toBeGreaterThan(1698);
  });
});
