/**
 * The maths behind `stats.summary`: the periods of Итоги in the Stylist's timezone, and what the
 * Appointments of a period come to. Pure, so the edges (midnight, the Monday a week starts on,
 * daylight saving) are tested without a database.
 */

export const earningsPeriods = ["week", "month", "year"] as const;
export type EarningsPeriod = (typeof earningsPeriods)[number];

/** A calendar date with no time or timezone: "2026-10-21". */
export type LocalDate = { year: number; month: number; day: number };

export function formatLocalDate({ year, month, day }: LocalDate) {
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

/** Reads "2026-10-21"; null when it is not a real date. */
export function parseLocalDate(value: string): LocalDate | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);

  if (!match) {
    return null;
  }

  const date = { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
  const check = new Date(Date.UTC(date.year, date.month - 1, date.day));

  return check.getUTCFullYear() === date.year && check.getUTCMonth() === date.month - 1 && check.getUTCDate() === date.day
    ? date
    : null;
}

/** The date `days` after (or before, when negative) the given one. */
function addDays(date: LocalDate, days: number): LocalDate {
  const moved = new Date(Date.UTC(date.year, date.month - 1, date.day + days));

  return { year: moved.getUTCFullYear(), month: moved.getUTCMonth() + 1, day: moved.getUTCDate() };
}

/** How far the timezone is ahead of UTC at an instant, in milliseconds. */
function offsetAt(timezone: string, instant: number) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric"
  }).formatToParts(new Date(instant));
  const part = (type: string) => Number(parts.find((candidate) => candidate.type === type)?.value);
  const wall = Date.UTC(part("year"), part("month") - 1, part("day"), part("hour"), part("minute"), part("second"));

  return wall - Math.floor(instant / 1000) * 1000;
}

/** The first instant of a calendar day in the timezone. */
export function startOfLocalDay(timezone: string, date: LocalDate) {
  const wall = Date.UTC(date.year, date.month - 1, date.day);
  // The offset is looked up at the wall time first, then at the instant that gives, so a day
  // that starts in the other side of a daylight saving change still lands on its midnight.
  const first = wall - offsetAt(timezone, wall);

  return new Date(wall - offsetAt(timezone, first));
}

/** The calendar date of an instant in the timezone. */
export function localDateOf(timezone: string, instant: Date): LocalDate {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    year: "numeric",
    month: "numeric",
    day: "numeric"
  }).formatToParts(instant);
  const part = (type: string) => Number(parts.find((candidate) => candidate.type === type)?.value);

  return { year: part("year"), month: part("month"), day: part("day") };
}

export type PeriodBounds = {
  /** First day of the period. */
  firstDay: LocalDate;
  /** Last day of the period, itself included. */
  lastDay: LocalDate;
  /** The first instant of the period. */
  startsAt: Date;
  /** The first instant after the period. */
  endsAt: Date;
};

/** The period of the given kind that holds the date: a week from Monday, a calendar month, a calendar year. */
export function periodBounds(period: EarningsPeriod, date: LocalDate, timezone: string): PeriodBounds {
  let firstDay: LocalDate;
  let after: LocalDate;

  if (period === "week") {
    const sinceMonday = (new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay() + 6) % 7;

    firstDay = addDays(date, -sinceMonday);
    after = addDays(firstDay, 7);
  } else if (period === "month") {
    firstDay = { year: date.year, month: date.month, day: 1 };
    after = date.month === 12 ? { year: date.year + 1, month: 1, day: 1 } : { year: date.year, month: date.month + 1, day: 1 };
  } else {
    firstDay = { year: date.year, month: 1, day: 1 };
    after = { year: date.year + 1, month: 1, day: 1 };
  }

  return {
    firstDay,
    lastDay: addDays(after, -1),
    startsAt: startOfLocalDay(timezone, firstDay),
    endsAt: startOfLocalDay(timezone, after)
  };
}

/** What an Appointment comes to: the price of its Services (the custom total when set) and its Tip. */
export function appointmentAmount(appointment: {
  finalTotalCentsOverride: number | null;
  tipCents: number | null;
  services: { priceCents: number }[];
}) {
  const servicesCents =
    appointment.finalTotalCentsOverride ?? appointment.services.reduce((sum, service) => sum + service.priceCents, 0);
  const tipCents = appointment.tipCents ?? 0;

  return { servicesCents, tipCents, totalCents: servicesCents + tipCents };
}

/** Case, ё/е and repeated spaces do not tell two names apart. */
export function nameKey(name: string) {
  return name.trim().replace(/\s+/g, " ").toLowerCase().replaceAll("ё", "е");
}

export type RevenueLine = { name: string; priceCents: number; menuItemId: string | null };

export type TopService = {
  name: string;
  revenueCents: number;
  count: number;
};

export const topServicesLimit = 5;

/**
 * The Service lines grouped by what the Stylist would call them: the group of the Service Menu
 * Item a Service was started from, else the Service's own name. Biggest revenue first.
 */
export function topServices(lines: RevenueLine[], groupOfItem: ReadonlyMap<string, string>): TopService[] {
  const totals = new Map<string, TopService>();

  for (const line of lines) {
    const group = line.menuItemId ? groupOfItem.get(line.menuItemId) : undefined;
    const label = (group ?? line.name).trim().replace(/\s+/g, " ");
    const key = nameKey(label);
    const total = totals.get(key);

    if (total) {
      total.revenueCents += line.priceCents;
      total.count += 1;
    } else {
      totals.set(key, { name: label, revenueCents: line.priceCents, count: 1 });
    }
  }

  return [...totals.values()]
    .sort((a, b) => b.revenueCents - a.revenueCents || b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, topServicesLimit);
}
