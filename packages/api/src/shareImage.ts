import { t, type SupportedLocale } from "@hcm/shared";
import {
  formatAppointmentTime,
  type ProfileShareContent,
  type SharedAppointment,
  type SharedPhoto
} from "./profileShare";

/** A stored Appointment Photo made ready for a Share Image: upright, small, and as a data URI. */
export type SharePhotoImage = {
  dataUri: string;
  width: number;
  height: number;
};

/**
 * Reads the photo at a URL of the photo storage. A Share Image has to carry its photos inside
 * itself, because it leaves the app as one file and the app's SVG renderer loads nothing from the
 * network. Null: the photo could not be read.
 */
export type SharePhotoReader = (url: string) => Promise<SharePhotoImage | null>;

export type ShareImageInput = ProfileShareContent & {
  locale: SupportedLocale;
  /**
   * Those photos of the last completed Appointment that the picture carries, in their order. The
   * picture says how many of the Appointment's photos it leaves out.
   */
  embeddedPhotos: (SharePhotoImage & Pick<SharedPhoto, "category">)[];
};

/**
 * A Share Image is one picture that a phone has to draw, so each of its lists ends somewhere. A
 * list that is cut says how many entries it leaves out; the Profile Share page shows them all.
 */
export const shareImageLimits = {
  upcomingAppointments: 6,
  services: 10,
  colorFormulas: 6,
  photos: 6,
  /** Long texts take many lines: a picture that is already this tall lists no further Service. */
  height: 8000
};

// The text starts at `left` and ends at `right` at the latest, both 40 inside the frame; photos
// stand in two columns of `photoWidth`.
const left = 112;
const right = 1088;
const photoWidth = 476;
const photoMaxHeight = 640;
const photoGap = 24;

/**
 * The widest a character is, in ems, in the fonts that draw a Share Image: Roboto on Android,
 * Arial, and DejaVu Sans where neither is installed. The server cannot measure the text, so it
 * breaks lines by these upper bounds: a line may end early, but not outside the frame. The first
 * class that holds a character counts; a character of no class is one em wide.
 */
const characterWidths: [RegExp, number][] = [
  [/[ \u00a0\u202f'’.,:;|·ijlI]/, 0.34],
  [/[-!()[\]/\\frt]/, 0.42],
  [/[mwжмфшщъыю]/, 0.98],
  [/[MWЖМФШЩЪЫЮ@%№]/, 1.11],
  [/[0-9a-z]/, 0.64],
  [/[а-яё]/, 0.7],
  [/[A-ZА-ЯЁ]/, 0.79],
  [/[!-~]/, 0.84],
  [/\p{Extended_Pictographic}/u, 1.3]
];
// Bold letters are up to a fifth wider.
const boldWidth = 1.2;

function textWidth(text: string, size: number, weight: number): number {
  let ems = 0;

  for (const character of text) {
    ems += characterWidths.find(([characters]) => characters.test(character))?.[1] ?? 1;
  }

  return ems * size * (weight > 500 ? boldWidth : 1);
}

// What a picture cannot hold or show: the characters XML forbids (control characters, a half of a
// surrogate pair, U+FFFE and U+FFFF) and the other control characters. Line breaks and tabs stay.
const unprintable = /[^\t\n\r\x20-\x7e\u00a0-\ud7ff\ue000-\ufffd\u{10000}-\u{10ffff}]/gu;
const graphemes = new Intl.Segmenter(undefined, { granularity: "grapheme" });
// Where a word wider than a whole line is broken before it is cut: after its hyphens.
const afterHyphen = /(?=[^-])(?<=[^-]-+)/;
// A word that is cut leaves at least this many letters for its last line.
const fewestLettersCutOff = 3;

/** The lines `text` was typed in, each as its words. An unprintable character becomes a space. */
function typedWords(text: string): string[][] {
  return text
    .replace(unprintable, " ")
    .split(/\r\n?|[\n\u2028\u2029]/)
    .map((typedLine) => typedLine.split(/[ \t]+/).filter(Boolean));
}

/**
 * `text` as lines of a Share Image, none wider than `width`. The text keeps its own line breaks
 * and a longer line is broken between words. A word wider than a whole line is broken after its
 * hyphens, and cut where even that is not enough.
 */
function wrapText(text: string, width: number, size: number, weight: number): string[] {
  const lines: string[] = [];
  const space = textWidth(" ", size, weight);

  for (const words of typedWords(text)) {
    let current = "";
    let used = 0;
    const end = () => {
      if (current) {
        lines.push(current);
      }

      current = "";
      used = 0;
    };

    for (const word of words) {
      const parts = textWidth(word, size, weight) > width ? word.split(afterHyphen) : [word];

      parts.forEach((part, index) => {
        // The parts of one word follow each other without a space.
        const gap = index === 0 ? space : 0;
        const partWidth = textWidth(part, size, weight);

        if (current && used + gap + partWidth <= width) {
          current += gap ? ` ${part}` : part;
          used += gap + partWidth;
          return;
        }

        end();

        // Letter by letter, so that a part wider than a whole line is cut where the line ends, or
        // sooner where one or two letters would be left for the next line.
        const letters = Array.from(graphemes.segment(part), ({ segment }) => segment);

        letters.forEach((letter, at) => {
          const next = at === letters.length - fewestLettersCutOff ? letters.slice(at).join("") : letter;

          if (used + textWidth(next, size, weight) > width) {
            end();
          }

          current += letter;
          used += textWidth(letter, size, weight);
        });
      });
    }

    end();
  }

  return lines;
}

/**
 * The largest letter size, from `largest` down to `smallest`, at which `wrapText` cuts no word of
 * `text`: at which no word is wider than `width` between its hyphens. `smallest` where a word is
 * cut even then.
 */
function fittingSize(text: string, width: number, weight: number, largest: number, smallest: number): number {
  const parts = typedWords(text).flat().flatMap((word) => word.split(afterHyphen));
  const widest = parts.reduce((most, part) => Math.max(most, textWidth(part, largest, weight)), 0);

  return widest <= width ? largest : Math.max(smallest, Math.floor((largest * width) / widest));
}

function escapeSvgAttribute(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/**
 * Text as the content of an element: a CDATA section, never entity references. The app draws the
 * picture with AndroidSVG 1.4, whose parser skips every entity reference in text, so `&amp;` would
 * lose its `&`; it reads CDATA sections, and so do browsers. A `]]>` in the text ends one section
 * and starts the next. (In attribute values the parser itself resolves entity references.)
 */
function svgText(value: string): string {
  return `<![CDATA[${value.replaceAll("]]>", "]]]]><![CDATA[>")}]]>`;
}

/** One line of text as it is: text that may be long or hold line breaks goes through `wrapText`. */
function svgLine(text: string, x: number, y: number, size = 28, weight = 500): string {
  return `<text x="${x}" y="${y}" font-family="Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="#17130f">${svgText(text)}</text>`;
}

function appointmentLocation(locale: SupportedLocale, appointment: SharedAppointment): string {
  const place = appointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon");

  return appointment.locationAddress ? `${place} · ${appointment.locationAddress}` : place;
}

export function buildShareImageSvg(input: ShareImageInput): string {
  const { locale } = input;
  const parts: string[] = [];
  let y = 150;
  // Puts a text `gap` below the line before it, on as many lines as it needs inside the frame.
  const line = (gap: number, text: string, x = left, size = 26, weight = 500) => {
    y += gap;
    wrapText(text, right - x, size, weight).forEach((part, index) => {
      // The lines of one text stand closer together than two texts.
      y += index === 0 ? 0 : Math.round(size * 1.2);
      parts.push(svgLine(part, x, y, size, weight));
    });
  };
  // Says that a list was cut, when it was.
  const more = (gap: number, count: number, x = left) => {
    if (count > 0) {
      line(gap, t(locale, "profileShareMore", { count }), x, 24);
    }
  };

  line(0, t(locale, "profileShareTitle"), left, 24, 700);
  // The Client's name is the largest text of the picture. A long surname is drawn in smaller
  // letters rather than cut, since the widths by which lines are broken are upper bounds: the
  // letters that a cut would move to the next line mostly fit the frame.
  line(80, input.clientName, left, fittingSize(input.clientName, right - left, 800, 64, 44), 800);

  if (input.stylistName) {
    line(46, t(locale, "profileShareStylist", { name: input.stylistName }));
  }

  if (input.salonAddress) {
    line(46, t(locale, "profileShareSalon", { address: input.salonAddress }));
  }

  line(100, t(locale, "profileShareUpcomingTitle"), left, 34, 800);

  const upcomingAppointments = input.upcomingAppointments.slice(0, shareImageLimits.upcomingAppointments);

  upcomingAppointments.forEach((appointment, index) => {
    line(index === 0 ? 52 : 58, formatAppointmentTime(locale, input.timezone, appointment), left, 28, 700);
    line(42, appointmentLocation(locale, appointment));
  });

  if (upcomingAppointments.length === 0) {
    line(52, t(locale, "profileShareNoUpcoming"));
  }

  more(58, input.upcomingAppointments.length - upcomingAppointments.length);
  line(100, t(locale, "profileShareLastCompletedTitle"), left, 34, 800);

  const completed = input.lastCompletedAppointment;

  if (completed) {
    line(52, formatAppointmentTime(locale, input.timezone, completed), left, 28, 700);
    line(42, appointmentLocation(locale, completed));
    line(
      58,
      t(locale, input.includesColorFormulas ? "profileShareServicesTitle" : "profileShareServicesOnlyTitle"),
      left,
      30,
      800
    );

    let listedServices = 0;

    for (const service of completed.services.slice(0, shareImageLimits.services)) {
      if (listedServices > 0 && y >= shareImageLimits.height) {
        break;
      }

      const colorFormulas = service.colorFormulas.slice(0, shareImageLimits.colorFormulas);

      line(listedServices === 0 ? 46 : 50, service.name, left + 24, 27, 700);

      for (const formula of colorFormulas) {
        line(36, formula.placement ? `${formula.placement}: ${formula.formula}` : formula.formula, left + 48, 24);
      }

      more(36, service.colorFormulas.length - colorFormulas.length, left + 48);
      listedServices += 1;
    }

    if (listedServices === 0) {
      line(46, t(locale, "profileShareNoServices"));
    }

    more(50, completed.services.length - listedServices, left + 24);

    if (completed.photos.length > 0) {
      line(64, t(locale, "profileSharePhotosTitle"), left, 30, 800);

      // Two photos to a row, each as large as its column allows.
      for (let first = 0; first < input.embeddedPhotos.length; first += 2) {
        const top = y + photoGap;

        input.embeddedPhotos.slice(first, first + 2).forEach((photo, column) => {
          const scale = Math.min(photoWidth / photo.width, photoMaxHeight / photo.height);
          const width = Math.round(photo.width * scale);
          const height = Math.round(photo.height * scale);
          const x = left + column * (photoWidth + photoGap);
          let bottom = top + height;

          parts.push(
            `<image x="${x}" y="${top}" width="${width}" height="${height}" xlink:href="${escapeSvgAttribute(photo.dataUri)}"/>`
          );

          // Which of the photos were taken before and which after.
          if (photo.category !== "other") {
            bottom += 30;
            parts.push(
              svgLine(t(locale, photo.category === "before" ? "profileSharePhotoBefore" : "profileSharePhotoAfter"), x, bottom, 22)
            );
          }

          y = Math.max(y, bottom);
        });
      }

      if (completed.photos.length > input.embeddedPhotos.length) {
        line(46, t(locale, "profileSharePhotosNotShown", { count: completed.photos.length - input.embeddedPhotos.length }), left, 24);
      }
    }
  } else {
    line(52, t(locale, "profileShareNoCompleted"));
  }

  const height = Math.max(1600, y + 160);
  const frameHeight = height - 144;
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1200" height="${height}" viewBox="0 0 1200 ${height}">`,
    `<rect width="1200" height="${height}" fill="#f7f1e8"/>`,
    `<rect x="72" y="72" width="1056" height="${frameHeight}" rx="24" fill="#fffaf3" stroke="#17130f" stroke-width="4"/>`,
    ...parts,
    `</svg>`
  ];

  return svg.join("");
}

/**
 * Builds the Share Image of `content`: reads as many of its photos as one picture carries, each
 * through `readSharePhoto`, and draws the picture. A photo that cannot be read is left out, and
 * the picture says so.
 */
export async function buildShareImage(
  content: ProfileShareContent,
  locale: SupportedLocale,
  readSharePhoto: SharePhotoReader
): Promise<string> {
  const photos = content.lastCompletedAppointment?.photos.slice(0, shareImageLimits.photos) ?? [];
  const embeddedPhotos = await Promise.all(
    photos.map(async ({ url, category }) => {
      const image = await readSharePhoto(url).catch(() => null);

      return image && image.width > 0 && image.height > 0 ? [{ ...image, category }] : [];
    })
  );

  return buildShareImageSvg({
    ...content,
    locale,
    embeddedPhotos: embeddedPhotos.flat()
  });
}
