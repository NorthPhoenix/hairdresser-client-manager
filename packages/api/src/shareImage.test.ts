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

const entities: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

/**
 * Reads a picture the way an XML parser does: its elements, and each line of text with its place.
 * With `entityReferences: false` it reads like AndroidSVG 1.4, the app's SVG reader, which skips
 * every entity reference in text and keeps CDATA sections. Throws on markup that is not
 * well-formed.
 */
function readPicture(svg: string, entityReferences = true) {
  const token = /<!\[CDATA\[([\s\S]*?)\]\]>|<(\/?)([a-z]+)((?:"[^"]*"|[^">])*)>|&(#?\w+);|([^<&]+)/y;
  const elements: string[] = [];
  const lines: { text: string; x: number; y: number; size: number }[] = [];
  let line: (typeof lines)[number] | null = null;

  while (token.lastIndex < svg.length) {
    const at = token.lastIndex;
    const match = token.exec(svg);

    if (!match) {
      throw new Error(`The picture is not well-formed at ${at}: ${svg.slice(at, at + 40)}`);
    }

    const [, cdata, closing, element, attributes = "", entity, text] = match;
    const attribute = (name: string) => Number(new RegExp(` ${name}="(\\d+)"`).exec(attributes)?.[1]);

    if (element && !closing) {
      elements.push(element);

      if (element === "text") {
        line = { text: "", x: attribute("x"), y: attribute("y"), size: attribute("font-size") };
      }
    } else if (element === "text" && line) {
      lines.push(line);
      line = null;
    } else if (line && entity !== undefined) {
      const code = /^#(x?)(.+)$/.exec(entity);

      if (entityReferences) {
        line.text += code ? String.fromCodePoint(parseInt(code[2] ?? "", code[1] ? 16 : 10)) : (entities[entity] ?? "");
      }
    } else if (line) {
      line.text += cdata ?? text ?? "";
    }
  }

  return { elements, lines, texts: lines.map((entry) => entry.text) };
}

/** The lines of text of a picture, as the app reads them. */
const texts = (svg: string) => readPicture(svg, false).texts;

// Every character that XML 1.0 allows in a document.
const wellFormedCharacters = /^[\t\n\r\x20-\ud7ff\ue000-\ufffd\u{10000}-\u{10ffff}]*$/u;

// Where the text of a picture ends at the latest: 40 inside the frame, like its left edge.
const textRight = 1088;

/**
 * Advance widths in ems of the widest of the fonts that draw a picture (DejaVu Sans and DejaVu
 * Sans Bold; Roboto for the slash), measured from the font files and rounded up.
 */
const widestRegular = {
  a: 0.613, i: 0.278, r: 0.412, m: 0.975, W: 0.989, O: 0.788, д: 0.692, щ: 0.942, Щ: 1.094,
  "0": 0.637, "/": 0.416, "+": 0.838
};
const widestBold = {
  a: 0.675, i: 0.343, r: 0.494, m: 1.042, W: 1.104, O: 0.851, д: 0.808, щ: 1.106, Щ: 1.326, Ц: 0.928,
  "0": 0.696
};

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

    expect(texts(svg)).toContain(`Service ${shareImageLimits.services}`);
    expect(texts(svg)).not.toContain(`Service ${shareImageLimits.services + 1}`);
    expect(texts(svg)).toContain("and 2 more");
    expect(texts(svg)).toContain("and 3 more");
    expect(texts(svg)).toContain("and 4 more");
    expect(
      texts(
        shareImage({
          locale: "ru",
          lastCompletedAppointment: completedAppointment({
            services: Array.from({ length: shareImageLimits.services + 3 }, (_, index) => ({
              name: `Услуга ${index + 1}`,
              colorFormulas: []
            }))
          })
        })
      )
    ).toContain("и ещё 3");
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

    expect(texts(shareImage({ lastCompletedAppointment, includesColorFormulas: false }))).toContain("Services");
    expect(shareImage({ lastCompletedAppointment, includesColorFormulas: false })).not.toContain("formulas");
    expect(texts(shareImage({ lastCompletedAppointment }))).toContain("Services and formulas");
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
    expect(texts(svg)).toContain("Photos");
    expect(texts(svg)).toContain("After");
    expect(texts(svg)).toContain("Before");
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

    expect(texts(svg)).not.toContain("Photos");
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

describe("share image text", () => {
  const withFormulas = (...formulas: { formula: string; placement: string | null }[]) =>
    shareImage({
      lastCompletedAppointment: completedAppointment({ services: [{ name: "Color", colorFormulas: formulas }] })
    });
  // The Color Formulas of a picture stand at 160, in letters of 24; Service names at 136, in bold letters of 27.
  const formulaLines = (svg: string) => readPicture(svg, false).lines.filter((line) => line.x === 160);
  const serviceLines = (svg: string) => readPicture(svg, false).lines.filter((line) => line.x === 136);

  it("keeps &, <, > and quotes for the app's SVG reader, which skips entity references", () => {
    const typed = {
      clientName: `Oak & <Elm> "Co"`,
      stylistName: "Maria & Co",
      salonAddress: `5 Main St <b>x</b> & "A"`,
      address: "Bee's & Sons",
      service: `Cut & Color <b>bold</b> "Q"`,
      placement: "Roots <1>",
      formula: "5N&6G &amp; 20 vol"
    };
    const svg = shareImage({
      clientName: typed.clientName,
      stylistName: typed.stylistName,
      salonAddress: typed.salonAddress,
      lastCompletedAppointment: completedAppointment({
        locationAddress: typed.address,
        services: [{ name: typed.service, colorFormulas: [{ formula: typed.formula, placement: typed.placement }] }]
      })
    });
    const inTheApp = readPicture(svg, false);

    for (const text of [
      typed.clientName,
      `Stylist: ${typed.stylistName}`,
      `Salon: ${typed.salonAddress}`,
      `In salon · ${typed.address}`,
      typed.service,
      `${typed.placement}: ${typed.formula}`
    ]) {
      expect(inTheApp.texts).toContain(text);
    }

    // Not one entity reference in text, so a reader that resolves them sees the same.
    expect(readPicture(svg).texts).toEqual(inTheApp.texts);
    // And nothing that was typed became markup.
    expect(new Set(inTheApp.elements)).toEqual(new Set(["svg", "rect", "text"]));
  });

  it("keeps text that holds the end of a CDATA section, and markup after it, as text", () => {
    const svg = shareImage({
      clientName: "a]]>b",
      lastCompletedAppointment: completedAppointment({
        services: [
          {
            name: "]]><script>alert(1)</script>",
            colorFormulas: [
              { formula: "7N]]", placement: null },
              { formula: "]]>]]>", placement: "]" }
            ]
          }
        ]
      })
    });

    for (const reader of [readPicture(svg, false), readPicture(svg)]) {
      for (const text of ["a]]>b", "]]><script>alert(1)</script>", "7N]]", "]: ]]>]]>"]) {
        expect(reader.texts).toContain(text);
      }

      expect(new Set(reader.elements)).toEqual(new Set(["svg", "rect", "text"]));
    }
  });

  it("breaks a long Color Formula between words into lines that follow each other", () => {
    const formula =
      "6N 30g + 7N 30g + 20 vol 60g, apply to the roots first and process 35 min, then pull through to the ends " +
      "for the last 10 min, rinse, then gloss 9V 20g + 9N 10g with 6 vol 30g for 12 min at the basin";
    const lines = formulaLines(withFormulas({ formula, placement: "Roots" }, { formula: "8A 40g", placement: null }));
    const wrapped = lines.slice(0, -1);

    expect(wrapped.length).toBeGreaterThan(2);
    // Every word is there, whole and in its place.
    expect(wrapped.map((line) => line.text).join(" ")).toBe(`Roots: ${formula}`);
    expect(Math.max(...wrapped.map((line) => line.text.length))).toBeLessThan(80);
    // The lines of one formula stand closer together than two formulas.
    expect(wrapped.slice(1).map((line, index) => line.y - (wrapped[index]?.y ?? 0))).toEqual(wrapped.slice(1).map(() => 29));
    expect(lines.at(-1)).toMatchObject({ text: "8A 40g", y: (wrapped.at(-1)?.y ?? 0) + 36 });
  });

  it("leaves a line that fits as it is", () => {
    const formula = "6N 30g + 7N 30g + 20 vol 60g, 35 min, then gloss 9V";

    expect(formulaLines(withFormulas({ formula, placement: "Roots" })).map((line) => line.text)).toEqual([`Roots: ${formula}`]);
    expect(texts(shareImage({ clientName: "Екатерина Смирнова" }))).toContain("Екатерина Смирнова");
  });

  it.each(Object.entries(widestRegular))("ends a line of %s inside the frame in the widest font", (letter, width) => {
    const lines = formulaLines(withFormulas({ formula: letter.repeat(400), placement: null }));

    expect(lines.map((line) => line.text).join("")).toBe(letter.repeat(400));

    for (const line of lines.slice(0, -1)) {
      const right = line.x + line.text.length * width * line.size;

      expect(right).toBeLessThanOrEqual(textRight);
      // Not much earlier than it has to, either.
      expect(right).toBeGreaterThan(line.x + (textRight - line.x) * 0.75);
    }
  });

  it.each(Object.entries(widestBold))("ends a bold line of %s inside the frame in the widest font", (letter, width) => {
    const lines = serviceLines(
      shareImage({
        lastCompletedAppointment: completedAppointment({ services: [{ name: letter.repeat(100), colorFormulas: [] }] })
      })
    );

    expect(lines.map((line) => line.text).join("")).toBe(letter.repeat(100));
    expect(lines.length).toBeGreaterThan(1);

    for (const line of lines.slice(0, -1)) {
      const right = line.x + line.text.length * width * line.size;

      expect(right).toBeLessThanOrEqual(textRight);
      expect(right).toBeGreaterThan(line.x + (textRight - line.x) * 0.75);
    }
  });

  it("breaks a long Client name, address and Service name too, and never inside a pair of code units", () => {
    const clientName = `<img src=x onerror=alert(1)> & "Co" Oak & Elm`;
    const salonAddress =
      "Бизнес-центр «Северное сияние», 3 этаж, офис 314, улица Большая Конюшенная, дом 27, Санкт-Петербург, 191186";
    const emoji = "💇‍♀️".repeat(60);
    const picture = readPicture(
      shareImage({
        clientName,
        salonAddress,
        lastCompletedAppointment: completedAppointment({ services: [{ name: emoji, colorFormulas: [] }] })
      }),
      false
    );
    const nameLines = picture.lines.filter((line) => line.size === 64);
    const addressLines = picture.lines.filter((line) => line.size === 26 && line.y > (nameLines.at(-1)?.y ?? 0)).slice(0, 2);
    const emojiLines = picture.lines.filter((line) => line.x === 136);

    expect(nameLines.length).toBeGreaterThan(1);
    expect(nameLines.map((line) => line.text).join(" ")).toBe(clientName);
    expect(addressLines.map((line) => line.text).join(" ")).toBe(`Salon: ${salonAddress}`);
    // What follows the name moves down with it.
    expect(addressLines[0]?.y).toBeGreaterThan(nameLines.at(-1)?.y ?? 0);
    expect(emojiLines.length).toBeGreaterThan(1);
    expect(emojiLines.map((line) => line.text).join("")).toBe(emoji);

    for (const line of emojiLines) {
      // Each line holds whole signs: none is cut between the parts that make one sign.
      expect(line.text.length % "💇‍♀️".length).toBe(0);
    }
  });

  // The Client name stands in the largest letters of a picture: 64, and no smaller than 44.
  const nameLines = (svg: string) => readPicture(svg, false).lines.filter((line) => line.size >= 44);

  it.each([
    ["Мария Константинопольская", ["Мария", "Константинопольская"]],
    ["Елена Христорождественская", ["Елена", "Христорождественская"]]
  ])("draws the long surname of %s in smaller letters instead of cutting it", (clientName, expected) => {
    const lines = nameLines(shareImage({ clientName }));
    const size = lines[0]?.size ?? 0;

    expect(lines.map((line) => line.text)).toEqual(expected);
    expect(size).toBeLessThan(64);
    expect(lines.map((line) => line.size)).toEqual(lines.map(() => size));
    // The lines of the name stand as far apart as its letters need.
    expect((lines[1]?.y ?? 0) - (lines[0]?.y ?? 0)).toBe(Math.round(size * 1.2));
    // A name that needs no smaller letters keeps the large ones.
    expect(nameLines(shareImage({ clientName: "Екатерина Смирнова" })).map((line) => line.size)).toEqual([64]);
  });

  it("makes the letters of a long name only as small as its longest word needs, inside the frame in the widest font", () => {
    const sizes: number[] = [];

    for (const length of [18, 20, 23, 26]) {
      const [line, ...others] = nameLines(shareImage({ clientName: "д".repeat(length) }));
      const right = (line?.x ?? 0) + length * widestBold.д * (line?.size ?? 0);

      expect(others).toEqual([]);
      expect(line?.text).toBe("д".repeat(length));
      expect(right).toBeLessThanOrEqual(textRight);
      expect(line?.size).toBeGreaterThanOrEqual(44);

      // Not much smaller than they have to be, either.
      if ((line?.size ?? 0) < 64) {
        expect(right).toBeGreaterThan((line?.x ?? 0) + (textRight - (line?.x ?? 0)) * 0.9);
      }

      sizes.push(line?.size ?? 0);
    }

    expect(sizes).toEqual([64, 58, 50, 44]);
  });

  it("breaks a double surname after its hyphen and keeps the large letters", () => {
    const lines = nameLines(shareImage({ clientName: "Анна Голенищева-Кутузова" }));

    expect(lines.map((line) => line.text)).toEqual(["Анна Голенищева-", "Кутузова"]);
    expect(lines.map((line) => line.size)).toEqual([64, 64]);
  });

  it("breaks a word after a hyphen only when the word is wider than a whole line", () => {
    const wide = `${"a".repeat(40)}-${"b".repeat(40)}-${"c".repeat(10)}`;

    expect(formulaLines(withFormulas({ formula: `7N ${wide} 8A`, placement: null })).map((line) => line.text)).toEqual([
      `7N ${"a".repeat(40)}-`,
      `${"b".repeat(40)}-${"c".repeat(10)} 8A`
    ]);
    // A word that fits a line goes to the next line whole.
    expect(formulaLines(withFormulas({ formula: `${"слово ".repeat(10)}светло-русый`, placement: null })).map((line) => line.text)).toEqual([
      Array.from({ length: 10 }, () => "слово").join(" "),
      "светло-русый"
    ]);
    // Hyphens at the start or the end of a word, or several in a row, are no place to break.
    expect(formulaLines(withFormulas({ formula: `-${"a".repeat(50)}--${"b".repeat(30)}-`, placement: null })).map((line) => line.text)).toEqual([
      `-${"a".repeat(50)}--`,
      `${"b".repeat(30)}-`
    ]);
  });

  it("cuts a word that fits no line so that at least three letters go to the next line", () => {
    // 60 letters "a" of a Color Formula fill a line.
    for (const [length, expected] of [[60, [60]], [61, [58, 3]], [62, [59, 3]], [63, [60, 3]], [64, [60, 4]], [121, [60, 58, 3]]] as const) {
      const lines = formulaLines(withFormulas({ formula: "a".repeat(length), placement: null }));

      expect(lines.map((line) => line.text.length)).toEqual(expected);
      expect(lines.map((line) => line.text).join("")).toBe("a".repeat(length));
    }

    // A name with a word too long for the smallest letters is cut in the same way.
    const name = nameLines(shareImage({ clientName: `Анна ${"д".repeat(28)}` }));

    expect(name.map((line) => line.text)).toEqual(["Анна", "д".repeat(25), "д".repeat(3)]);
    expect(name.map((line) => line.size)).toEqual([44, 44, 44]);

    // What follows a word that was cut stands after its last letters.
    expect(formulaLines(withFormulas({ formula: `${"a".repeat(61)} 8A 40g`, placement: null })).map((line) => line.text)).toEqual([
      "a".repeat(58),
      "aaa 8A 40g"
    ]);
  });

  it("keeps the line breaks of a Color Formula", () => {
    const lines = formulaLines(
      withFormulas(
        { formula: "Line1: 30g 7N\nLine2: 30g 7G\r\nLine3: 60g 6pct\n\n\rLine4: tone", placement: "Roots" },
        { formula: "Корни: 6N\u2028Длина: 7G", placement: null }
      )
    );

    expect(lines.map((line) => line.text)).toEqual([
      "Roots: Line1: 30g 7N",
      "Line2: 30g 7G",
      "Line3: 60g 6pct",
      "Line4: tone",
      "Корни: 6N",
      "Длина: 7G"
    ]);
    expect(lines.map((line) => line.y - (lines[0]?.y ?? 0))).toEqual([0, 29, 58, 87, 123, 152]);
  });

  it("is well-formed and readable when a text holds characters that XML forbids", () => {
    const svg = shareImage({
      clientName: "Zoe\u0001Ctrl",
      stylistName: "Vera\u000bTab\u0000",
      salonAddress: "1 Main\u007f St\u0085\ufffe\uffff",
      lastCompletedAppointment: completedAppointment({
        locationAddress: "Flat\u001b 5",
        services: [
          {
            name: "Cut\u0008\u000c& Go \ud83d",
            colorFormulas: [{ formula: "6N\u000b20 vol \ude00", placement: "Ro\u0002ots" }]
          }
        ]
      })
    });

    expect(svg).toMatch(wellFormedCharacters);
    expect(svg).not.toMatch(/[\u007f-\u009f]/);

    for (const reader of [readPicture(svg, false), readPicture(svg)]) {
      for (const text of ["Zoe Ctrl", "Stylist: Vera Tab", "Salon: 1 Main St", "In salon · Flat 5", "Cut & Go"]) {
        expect(reader.texts).toContain(text);
      }

      expect(reader.texts).toContain("Ro ots: 6N 20 vol");
    }

    // A sign outside the first plane is no forbidden character.
    expect(texts(shareImage({ clientName: "Zoe 💇" }))).toContain("Zoe 💇");
  });

  it("draws nothing for a text of only forbidden characters and keeps its place", () => {
    const plain = readPicture(shareImage({ clientName: "Anna", stylistName: "Maria" }));
    const empty = readPicture(shareImage({ clientName: "\u0001\u0002", stylistName: "Maria" }));

    expect(empty.texts).toEqual(plain.texts.filter((text) => text !== "Anna"));
    expect(empty.lines.find((line) => line.text === "Stylist: Maria")?.y).toBe(
      plain.lines.find((line) => line.text === "Stylist: Maria")?.y
    );
  });

  it("stops listing Services once long texts have made the picture tall, and says how many it leaves out", () => {
    const longFormula = Array.from({ length: 250 }, (_, index) => `part${index}`).join(" ");
    const services = Array.from({ length: 8 }, (_, serviceIndex) => ({
      name: `Service ${serviceIndex + 1}`,
      colorFormulas: Array.from({ length: 4 }, () => ({ formula: longFormula, placement: null }))
    }));
    const svg = shareImage({
      lastCompletedAppointment: completedAppointment({ services, photos: [photo("a")] }),
      embeddedPhotos: [{ ...image("AAAA"), category: "after" }]
    });
    const picture = readPicture(svg, false);
    const listed = picture.texts.filter((text) => text.startsWith("Service ")).length;
    const height = Number(svg.match(/<svg [^>]*height="(\d+)"/)?.[1]);
    const top = (text: string) => picture.lines.find((line) => line.text === text)?.y ?? 0;
    const oneService = top("Service 2") - top("Service 1");

    expect(listed).toBeGreaterThan(1);
    expect(listed).toBeLessThan(services.length);
    expect(picture.texts).toContain(`and ${services.length - listed} more`);
    // The Service that was begun is whole, and the photos still follow.
    expect(picture.texts.join(" ")).toContain(`Service ${listed} ${Array.from({ length: 4 }, () => longFormula).join(" ")} and`);
    expect(picture.elements).toContain("image");
    expect(height).toBeLessThan(shareImageLimits.height + oneService + 1000);

    // The first Service is listed however long its texts are.
    const tall = readPicture(
      shareImage({
        lastCompletedAppointment: completedAppointment({
          services: [
            { name: "Only", colorFormulas: Array.from({ length: 6 }, () => ({ formula: "Щ".repeat(2000), placement: null })) },
            { name: "Next", colorFormulas: [] }
          ]
        })
      }),
      false
    );

    expect(tall.texts).toContain("Only");
    expect(tall.texts).not.toContain("Next");
    expect(tall.texts).toContain("and 1 more");
  });

  it("keeps wrapped text inside the frame from top to bottom", () => {
    const svg = shareImage({
      clientName: "Anastasia Konstantinopolskaya-Zadunaiskaya",
      lastCompletedAppointment: completedAppointment({
        services: [
          {
            name: "Color",
            colorFormulas: Array.from({ length: 6 }, () => ({ formula: "7N + 20 vol ".repeat(60), placement: "Roots" }))
          }
        ]
      })
    });
    const height = Number(svg.match(/<svg [^>]*height="(\d+)"/)?.[1]);
    const picture = readPicture(svg, false);

    expect(height).toBeGreaterThan(1600);
    expect(Math.max(...picture.lines.map((line) => line.y))).toBeLessThan(height - 72 - 40);
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
