import { t, type SupportedLocale } from "@hcm/shared";

type ShareImageAppointment = {
  startsAt: Date;
  endsAt: Date | null;
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
};

type ShareImageService = {
  name: string;
  colorFormulas: {
    formula: string;
    placement: string | null;
  }[];
};

export type ShareImageInput = {
  clientName: string;
  locale: SupportedLocale;
  nextAppointment: ShareImageAppointment | null;
  lastCompletedAppointment: ShareImageAppointment | null;
  services: ShareImageService[];
};

function escapeSvg(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function formatAppointmentTime(locale: SupportedLocale, appointment: ShareImageAppointment): string {
  const dateLocale = locale === "ru" ? "ru-RU" : "en-US";
  const start = appointment.startsAt.toLocaleString(dateLocale, {
    dateStyle: "medium",
    timeStyle: "short"
  });
  const end = appointment.endsAt?.toLocaleTimeString(dateLocale, {
    timeStyle: "short"
  });

  return [start, end].filter(Boolean).join(" - ");
}

function svgLine(text: string, x: number, y: number, size = 28, weight = 500): string {
  return `<text x="${x}" y="${y}" font-family="Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="#17130f">${escapeSvg(text)}</text>`;
}

export function buildShareImageSvg(input: ShareImageInput): string {
  const lines: string[] = [
    svgLine("Profile Share", 112, 150, 24, 700),
    svgLine(input.clientName, 112, 230, 64, 800)
  ];
  let y = 330;

  lines.push(svgLine(t(input.locale, "profileShareUpcomingTitle"), 112, y, 34, 800));
  y += 52;

  if (input.nextAppointment) {
    lines.push(svgLine(formatAppointmentTime(input.locale, input.nextAppointment), 112, y, 28, 700));
    y += 42;
    lines.push(
      svgLine(
        `${input.nextAppointment.locationType === "atHome" ? t(input.locale, "appointmentAtHome") : t(input.locale, "appointmentInSalon")}${input.nextAppointment.locationAddress ? ` · ${input.nextAppointment.locationAddress}` : ""}`,
        112,
        y,
        26
      )
    );
  } else {
    lines.push(svgLine(t(input.locale, "profileShareNoUpcoming"), 112, y, 26));
  }

  y += 100;
  lines.push(svgLine(t(input.locale, "profileShareLastCompletedTitle"), 112, y, 34, 800));
  y += 52;

  if (input.lastCompletedAppointment) {
    lines.push(svgLine(formatAppointmentTime(input.locale, input.lastCompletedAppointment), 112, y, 28, 700));
    y += 48;
    lines.push(svgLine(t(input.locale, "profileShareServicesTitle"), 112, y, 30, 800));
    y += 46;

    for (const service of input.services.slice(0, 6)) {
      lines.push(svgLine(service.name, 136, y, 27, 700));
      y += 38;

      for (const formula of service.colorFormulas.slice(0, 4)) {
        lines.push(svgLine(formula.placement ? `${formula.placement}: ${formula.formula}` : formula.formula, 160, y, 24));
        y += 34;
      }

      y += 12;
    }
  } else {
    lines.push(svgLine(t(input.locale, "profileShareNoCompleted"), 112, y, 26));
  }

  const height = Math.max(1600, y + 220);
  const footerY = height - 150;
  const frameHeight = height - 144;
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="${height}" viewBox="0 0 1200 ${height}">`,
    `<rect width="1200" height="${height}" fill="#f7f1e8"/>`,
    `<rect x="72" y="72" width="1056" height="${frameHeight}" rx="24" fill="#fffaf3" stroke="#17130f" stroke-width="4"/>`,
    ...lines,
    svgLine(t(input.locale, "profileSharePhotosEmpty"), 112, footerY, 24),
    `</svg>`
  ];

  return svg.join("");
}
