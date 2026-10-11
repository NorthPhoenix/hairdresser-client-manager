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
  photos: 6
};

// The text starts at `left`; photos stand in two columns of `photoWidth`.
const left = 112;
const photoWidth = 476;
const photoMaxHeight = 640;
const photoGap = 24;

function escapeSvg(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function svgLine(text: string, x: number, y: number, size = 28, weight = 500): string {
  return `<text x="${x}" y="${y}" font-family="Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="#17130f">${escapeSvg(text)}</text>`;
}

function appointmentLocation(locale: SupportedLocale, appointment: SharedAppointment): string {
  const place = appointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon");

  return appointment.locationAddress ? `${place} · ${appointment.locationAddress}` : place;
}

export function buildShareImageSvg(input: ShareImageInput): string {
  const { locale } = input;
  const parts: string[] = [];
  let y = 150;
  // Puts one line of text `gap` below the line before it.
  const line = (gap: number, text: string, x = left, size = 26, weight = 500) => {
    y += gap;
    parts.push(svgLine(text, x, y, size, weight));
  };
  // Says that a list was cut, when it was.
  const more = (gap: number, count: number, x = left) => {
    if (count > 0) {
      line(gap, t(locale, "profileShareMore", { count }), x, 24);
    }
  };

  line(0, t(locale, "profileShareTitle"), left, 24, 700);
  line(80, input.clientName, left, 64, 800);

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

    const services = completed.services.slice(0, shareImageLimits.services);

    services.forEach((service, index) => {
      const colorFormulas = service.colorFormulas.slice(0, shareImageLimits.colorFormulas);

      line(index === 0 ? 46 : 50, service.name, left + 24, 27, 700);

      for (const formula of colorFormulas) {
        line(36, formula.placement ? `${formula.placement}: ${formula.formula}` : formula.formula, left + 48, 24);
      }

      more(36, service.colorFormulas.length - colorFormulas.length, left + 48);
    });

    if (services.length === 0) {
      line(46, t(locale, "profileShareNoServices"));
    }

    more(50, completed.services.length - services.length, left + 24);

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
            `<image x="${x}" y="${top}" width="${width}" height="${height}" xlink:href="${escapeSvg(photo.dataUri)}"/>`
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
