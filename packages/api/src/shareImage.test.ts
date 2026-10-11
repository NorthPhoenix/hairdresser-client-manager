import { describe, expect, it, vi } from "vitest";
import type { ProfileShareContent } from "./profileShare";
import { buildShareImage, buildShareImageSvg, shareImageLimits, type ShareImageInput } from "./shareImage";

type Completed = NonNullable<ProfileShareContent["lastCompletedAppointment"]>;

function completedAppointment(overrides: Partial<Completed> = {}): Completed {
  return {
    startsAt: new Date("2026-06-01T15:00:00.000Z"),
    endsAt: null,
    locationType: "inSalon",
    locationAddress: null,
    services: [],
    photos: [],
    ...overrides
  };
}

function shareImage(overrides: Partial<ShareImageInput> = {}) {
  // Dates are written with a narrow no-break space before AM and PM.
  return buildShareImageSvg({
    clientName: "Anna",
    stylistName: null,
    salonAddress: null,
    timezone: "UTC",
    upcomingAppointments: [],
    lastCompletedAppointment: null,
    includesColorFormulas: true,
    locale: "en",
    embeddedPhotos: [],
    ...overrides
  }).replaceAll(" ", " ");
}

const photo = (name: string, category: "before" | "after" | "other" = "after") => ({
  url: `https://app.ufs.sh/f/${name}`,
  category
});

const image = (name: string, width = 720, height = 960) => ({
  dataUri: `data:image/jpeg;base64,${name}`,
  width,
  height
});

describe("share image", () => {
  it("renders the selected language and allowed Client-scoped details", () => {
    const svg = shareImage({
      upcomingAppointments: [
        {
          startsAt: new Date("2026-06-13T15:00:00.000Z"),
          endsAt: null,
          locationType: "inSalon",
          locationAddress: "500 Salon Ave"
        }
      ],
      lastCompletedAppointment: completedAppointment({
        locationType: "atHome",
        locationAddress: "Client appointment location",
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
      })
    });

    expect(svg).toContain("<svg");
    expect(svg).toContain("Upcoming Appointments");
    expect(svg).toContain("Anna");
    expect(svg).toContain("Color");
    expect(svg).toContain("Roots: 7N + 20 vol");
    expect(svg).toContain("In salon · 500 Salon Ave");
    // The place of the last completed Appointment, as on the page.
    expect(svg).toContain("At home · Client appointment location");
  });

  it("uses Russian labels when requested", () => {
    const svg = shareImage({ locale: "ru" });

    expect(svg).toContain("Будущие записи");
    expect(svg).toContain("Будущих записей нет.");
  });

  it("excludes private fields and custom branding from static output", () => {
    const svg = shareImage({
      lastCompletedAppointment: completedAppointment({
        services: [
          {
            name: "Cut",
            colorFormulas: []
          }
        ]
      })
    });

    expect(svg).not.toContain("+15551234567");
    expect(svg).not.toContain("anna@example.com");
    expect(svg).not.toContain("service note");
    expect(svg).not.toContain("$85");
    expect(svg).not.toContain("logo");
    expect(svg).not.toContain("<script");
  });

  it("shows every time in the Stylist's timezone, also across midnight", () => {
    const appointments = {
      upcomingAppointments: [
        {
          // 12:30 AM to 1:30 AM on Oct 23 in Moscow, which is still Oct 22 in UTC.
          startsAt: new Date("2026-10-22T21:30:00.000Z"),
          endsAt: new Date("2026-10-22T22:30:00.000Z"),
          locationType: "inSalon" as const,
          locationAddress: null
        }
      ],
      lastCompletedAppointment: completedAppointment({
        startsAt: new Date("2026-10-05T11:00:00.000Z"),
        endsAt: new Date("2026-10-05T12:30:00.000Z")
      })
    };
    const moscow = shareImage({ ...appointments, timezone: "Europe/Moscow" });

    expect(moscow).toContain("Oct 23, 2026, 12:30 AM - 1:30 AM");
    expect(moscow).toContain("Oct 5, 2026, 2:00 PM - 3:30 PM");
    expect(moscow).not.toContain("Oct 22");

    const moscowInRussian = shareImage({ ...appointments, timezone: "Europe/Moscow", locale: "ru" });

    expect(moscowInRussian).toContain("23 окт. 2026 г., 00:30 - 01:30");
    expect(moscowInRussian).toContain("5 окт. 2026 г., 14:00 - 15:30");

    const chicago = shareImage({ ...appointments, timezone: "America/Chicago" });

    expect(chicago).toContain("Oct 22, 2026, 4:30 PM - 5:30 PM");
    expect(chicago).toContain("Oct 5, 2026, 6:00 AM - 7:30 AM");
  });

  it("says who the share is from: the Stylist's name and the salon address", () => {
    const named = shareImage({ stylistName: "Maria Ivanova", salonAddress: "12 Salon Street" });

    expect(named).toContain("Stylist: Maria Ivanova");
    expect(named).toContain("Salon: 12 Salon Street");
    expect(shareImage({ stylistName: "Мария Иванова", salonAddress: "Садовая, 12", locale: "ru" })).toContain(
      "Мастер: Мария Иванова"
    );

    const unnamed = shareImage();

    expect(unnamed).not.toContain("Stylist:");
    expect(unnamed).not.toContain("Salon:");
  });

  it("shows every upcoming Appointment, Service and Color Formula that the page shows", () => {
    const svg = shareImage({
      upcomingAppointments: [23, 28, 30].map((day) => ({
        startsAt: new Date(`2026-10-${day}T12:00:00.000Z`),
        endsAt: null,
        locationType: "inSalon",
        locationAddress: null
      })),
      lastCompletedAppointment: completedAppointment({
        services: Array.from({ length: 7 }, (_, serviceIndex) => ({
          name: `Service ${serviceIndex + 1}`,
          colorFormulas:
            serviceIndex === 0
              ? Array.from({ length: 5 }, (_, formulaIndex) => ({
                  formula: `Formula ${formulaIndex + 1}`,
                  placement: null
                }))
              : []
        }))
      })
    });

    for (const text of ["Oct 23, 2026", "Oct 28, 2026", "Oct 30, 2026", "Service 7", "Formula 5"]) {
      expect(svg).toContain(text);
    }

    expect(svg).not.toContain("more");
  });

  it("says how many entries a list leaves out when it is too long for one picture", () => {
    const svg = shareImage({
      upcomingAppointments: Array.from({ length: shareImageLimits.upcomingAppointments + 2 }, (_, index) => ({
        startsAt: new Date(Date.UTC(2026, 10, index + 1, 12)),
        endsAt: null,
        locationType: "inSalon",
        locationAddress: null
      })),
      lastCompletedAppointment: completedAppointment({
        services: Array.from({ length: shareImageLimits.services + 3 }, (_, serviceIndex) => ({
          name: `Service ${serviceIndex + 1}`,
          colorFormulas: Array.from({ length: serviceIndex === 0 ? shareImageLimits.colorFormulas + 4 : 0 }, (_, formulaIndex) => ({
            formula: `Formula ${formulaIndex + 1}`,
            placement: null
          }))
        }))
      })
    });

    expect(svg).toContain(`Service ${shareImageLimits.services}<`);
    expect(svg).not.toContain(`Service ${shareImageLimits.services + 1}<`);
    expect(svg).toContain(">and 2 more<");
    expect(svg).toContain(">and 3 more<");
    expect(svg).toContain(">and 4 more<");
    expect(
      shareImage({
        locale: "ru",
        lastCompletedAppointment: completedAppointment({
          services: Array.from({ length: shareImageLimits.services + 3 }, (_, index) => ({
            name: `Услуга ${index + 1}`,
            colorFormulas: []
          }))
        })
      })
    ).toContain(">и ещё 3<");
  });

  it("says that a completed Appointment has no Services instead of that there is no completed Appointment", () => {
    const svg = shareImage({ lastCompletedAppointment: completedAppointment() });

    expect(svg).toContain("No Services were recorded for this Appointment.");
    expect(svg).not.toContain("No completed Appointments yet.");
    expect(shareImage({ lastCompletedAppointment: completedAppointment(), locale: "ru" })).toContain(
      "Для этой записи услуги не указаны."
    );
    expect(shareImage()).toContain("No completed Appointments yet.");
  });

  it("does not promise Color Formulas that the Stylist left out", () => {
    const lastCompletedAppointment = completedAppointment({
      services: [
        {
          name: "Color",
          colorFormulas: []
        }
      ]
    });

    expect(shareImage({ lastCompletedAppointment, includesColorFormulas: false })).toContain(">Services<");
    expect(shareImage({ lastCompletedAppointment, includesColorFormulas: false })).not.toContain("formulas");
    expect(shareImage({ lastCompletedAppointment })).toContain(">Services and formulas<");
  });

  it("carries the shared photos inside the picture, each under its kind", () => {
    const svg = shareImage({
      lastCompletedAppointment: completedAppointment({
        photos: [photo("a"), photo("b", "before"), photo("c", "other")]
      }),
      embeddedPhotos: [
        { ...image("AAAA"), category: "after" },
        { ...image("BBBB", 960, 720), category: "before" },
        { ...image("CCCC"), category: "other" }
      ]
    });

    // The app's SVG renderer loads nothing from the network: a photo is a data URI or it is missing.
    expect(svg).toContain('xmlns:xlink="http://www.w3.org/1999/xlink"');
    expect(svg.match(/<image [^>]*xlink:href="data:image\/jpeg;base64,[A-D]{4}"\/>/g)).toHaveLength(3);
    expect(svg).not.toContain("https://app.ufs.sh");
    expect(svg).toContain(">Photos<");
    expect(svg).toContain(">After<");
    expect(svg).toContain(">Before<");
    expect(svg).not.toContain("not shown");
    // A portrait and a landscape photo each keep their shape within a column.
    expect(svg).toContain('width="476" height="635" xlink:href="data:image/jpeg;base64,AAAA"');
    expect(svg).toContain('width="476" height="357" xlink:href="data:image/jpeg;base64,BBBB"');
  });

  it("says how many photos the picture leaves out", () => {
    const lastCompletedAppointment = completedAppointment({
      photos: [photo("a"), photo("b"), photo("c")]
    });

    expect(shareImage({ lastCompletedAppointment, embeddedPhotos: [{ ...image("AAAA"), category: "after" }] })).toContain(
      "Photos not shown in this picture: 2"
    );
    expect(shareImage({ lastCompletedAppointment, locale: "ru" })).toContain("Фото, которых нет на этой картинке: 3");
  });

  it("has no photo section, and no word about selecting photos, when no photo is shared", () => {
    const svg = shareImage({
      lastCompletedAppointment: completedAppointment({
        services: [
          {
            name: "Cut",
            colorFormulas: []
          }
        ]
      })
    });

    expect(svg).not.toContain(">Photos<");
    expect(svg).not.toContain("selected for sharing");
    expect(svg).not.toContain("<image");
  });

  it("expands the canvas so that many Services, Color Formulas and photos stay inside the frame", () => {
    const svg = shareImage({
      lastCompletedAppointment: completedAppointment({
        services: Array.from({ length: 6 }, (_, serviceIndex) => ({
          name: `Service ${serviceIndex + 1}`,
          colorFormulas: Array.from({ length: 4 }, (_, formulaIndex) => ({
            formula: `Formula ${formulaIndex + 1}`,
            placement: `Placement ${formulaIndex + 1}`
          }))
        })),
        photos: [photo("a"), photo("b"), photo("c")]
      }),
      embeddedPhotos: ["AAAA", "BBBB", "CCCC"].map((name) => ({ ...image(name), category: "after" as const }))
    });

    const height = Number(svg.match(/<svg [^>]*height="(\d+)"/)?.[1]);
    const frameBottom = 72 + Number(svg.match(/<rect x="72" y="72" width="1056" height="(\d+)"/)?.[1]);
    const textBottoms = [...svg.matchAll(/<text x="\d+" y="(\d+)"/g)].map((match) => Number(match[1]));
    const photoBottoms = [...svg.matchAll(/<image x="\d+" y="(\d+)" width="\d+" height="(\d+)"/g)].map(
      (match) => Number(match[1]) + Number(match[2])
    );

    expect(height).toBeGreaterThan(1600);
    expect(frameBottom).toBe(height - 72);
    expect(photoBottoms).toHaveLength(3);
    expect(Math.max(...textBottoms, ...photoBottoms)).toBeLessThan(frameBottom - 40);
  });
});

describe("share image photos", () => {
  const content = (photos: ReturnType<typeof photo>[]): ProfileShareContent => ({
    clientName: "Anna",
    stylistName: null,
    salonAddress: null,
    timezone: "UTC",
    upcomingAppointments: [],
    lastCompletedAppointment: completedAppointment({ photos }),
    includesColorFormulas: true
  });

  it("reads each shared photo through the server's reader and embeds what it gets", async () => {
    const readSharePhoto = vi.fn(async (url: string) => image(url.endsWith("/a") ? "AAAA" : "BBBB"));
    const svg = await buildShareImage(content([photo("a"), photo("b", "before")]), "en", readSharePhoto);

    expect(readSharePhoto.mock.calls).toEqual([["https://app.ufs.sh/f/a"], ["https://app.ufs.sh/f/b"]]);
    expect(svg).toContain("data:image/jpeg;base64,AAAA");
    expect(svg).toContain("data:image/jpeg;base64,BBBB");
    expect(svg.indexOf("AAAA")).toBeLessThan(svg.indexOf("BBBB"));
    expect(svg).not.toContain("not shown");
  });

  it("leaves out a photo that cannot be read and says so, instead of failing", async () => {
    const readSharePhoto = vi.fn(async (url: string) => {
      if (url.endsWith("/b")) {
        throw new Error("storage is down");
      }

      return url.endsWith("/c") ? null : image("AAAA");
    });
    const svg = await buildShareImage(content([photo("a"), photo("b"), photo("c")]), "en", readSharePhoto);

    expect(svg.match(/<image /g)).toHaveLength(1);
    expect(svg).toContain("Photos not shown in this picture: 2");
  });

  it("reads no more photos than one picture carries and counts the rest", async () => {
    const readSharePhoto = vi.fn(async () => image("AAAA"));
    const photos = Array.from({ length: shareImageLimits.photos + 5 }, (_, index) => photo(`p${index}`));
    const svg = await buildShareImage(content(photos), "en", readSharePhoto);

    expect(readSharePhoto).toHaveBeenCalledTimes(shareImageLimits.photos);
    expect(svg.match(/<image /g)).toHaveLength(shareImageLimits.photos);
    expect(svg).toContain("Photos not shown in this picture: 5");
  });

  it("reads nothing when the share has no photo", async () => {
    const readSharePhoto = vi.fn(async () => image("AAAA"));

    await buildShareImage({ ...content([]), lastCompletedAppointment: null }, "en", readSharePhoto);
    await buildShareImage(content([]), "en", readSharePhoto);

    expect(readSharePhoto).not.toHaveBeenCalled();
  });
});
