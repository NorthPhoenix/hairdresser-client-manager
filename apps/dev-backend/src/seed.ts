// Fills the local dev database with a demo Stylist workspace by calling the real
// tRPC procedures, so seeded data goes through the same rules as the app.
//
// Usage: pnpm --filter @hcm/dev-backend seed [dev user name, default "demo"]
// Re-running wipes and recreates that dev user's data.
import { appRouter, createTRPCContext } from "@hcm/api";
import { db } from "@hcm/db";

const userId = `dev_${(process.argv[2] ?? "demo").replace(/^dev_/, "")}`;
// Seeded times are whole hours on this machine's clock, so default the Stylist to the same timezone.
const timezone = process.env.SEED_TIMEZONE ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
const language = process.env.SEED_LANGUAGE === "en" ? "en" : "ru";

await db.stylist.deleteMany({ where: { clerkId: userId } });

const api = appRouter.createCaller(await createTRPCContext({ auth: async () => ({ userId }) }));

await api.stylist.bootstrap({ deviceLanguage: language, deviceTimezone: timezone });
await api.stylist.saveSettings({ language, timezone, salonAddress: "500 Salon Ave, Austin, TX" });

const menu = {
  haircut: await api.serviceMenu.save({ name: language === "ru" ? "Стрижка" : "Haircut", defaultPriceCents: 8500 }),
  color: await api.serviceMenu.save({ name: language === "ru" ? "Окрашивание корней" : "Root color", defaultPriceCents: 12000 }),
  balayage: await api.serviceMenu.save({ name: language === "ru" ? "Балаяж" : "Balayage", defaultPriceCents: 22050 }),
  styling: await api.serviceMenu.save({ name: language === "ru" ? "Укладка" : "Blowout", defaultPriceCents: 4500 })
};

const anna = await api.clientProfile.save({
  name: "Анна Петрова",
  language: "ru",
  phone: "+1 512 555 0100",
  email: "anna@example.com",
  address: "742 Evergreen Terrace, Austin, TX",
  note: "Предпочитает мягкие слои. Чувствительная кожа головы."
});
const maria = await api.clientProfile.save({ name: "Мария Соколова", language: "ru", phone: "+1 512 555 0101" });
const emily = await api.clientProfile.save({
  name: "Emily Carter",
  language: "en",
  phone: "+1 512 555 0102",
  email: "emily@example.com",
  address: "18 Barton Springs Rd, Austin, TX"
});
const olga = await api.clientProfile.save({ name: "Ольга Иванова", language: "ru", phone: "+1 512 555 0103" });
await api.clientProfile.save({ name: "Sofia Nguyen", language: "en" });

// Whole hours relative to now, so "today" and "unresolved" stay populated whenever this runs.
function at(dayOffset: number, hour: number, minute = 0): string {
  const date = new Date();
  date.setDate(date.getDate() + dayOffset);
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
}

// A completed visit a week ago: the source for Appointment Copying and Profile Share history.
const lastWeek = (
  await api.appointment.create({ primaryClientId: anna.id, additionalClientIds: [], startsAt: at(-7, 11), endsAt: at(-7, 13), locationType: "inSalon" })
).appointment;
const rootColor = await api.appointment.addService({ appointmentId: lastWeek.id, clientId: anna.id, menuItemId: menu.color.id });
const rootColorService = rootColor.services.at(-1)!;
await api.appointment.addColorFormula({ appointmentServiceId: rootColorService.id, formula: "7N + 7G (1:1) + 20 vol, 35 min", placement: language === "ru" ? "Корни" : "Roots" });
await api.appointment.addColorFormula({ appointmentServiceId: rootColorService.id, formula: "9V gloss + 6 vol, 10 min", placement: language === "ru" ? "Длина" : "Lengths" });
await api.appointment.addService({ appointmentId: lastWeek.id, clientId: anna.id, menuItemId: menu.haircut.id });
await api.appointment.update({ id: lastWeek.id, status: "completed", note: language === "ru" ? "Понравился холодный оттенок." : "Loved the cooler tone." });

// Yesterday, still scheduled: shows up on the Home View as needing an outcome.
await api.appointment.create({ primaryClientId: olga.id, additionalClientIds: [], startsAt: at(-1, 16), endsAt: at(-1, 17), locationType: "inSalon" });

// Today.
const morning = (
  await api.appointment.create({ primaryClientId: emily.id, additionalClientIds: [], startsAt: at(0, 9, 30), endsAt: at(0, 10, 30), locationType: "inSalon" })
).appointment;
await api.appointment.addService({ appointmentId: morning.id, clientId: emily.id, menuItemId: menu.haircut.id });
await api.appointment.update({ id: morning.id, status: "completed" });

const group = (
  await api.appointment.create({
    primaryClientId: anna.id,
    additionalClientIds: [maria.id],
    startsAt: at(0, 13),
    endsAt: at(0, 15, 30),
    locationType: "atHome"
  })
).appointment;
await api.appointment.addService({ appointmentId: group.id, clientId: anna.id, menuItemId: menu.balayage.id, note: language === "ru" ? "Осветлить у лица." : "Brighter around the face." });
await api.appointment.addService({ appointmentId: group.id, clientId: maria.id, menuItemId: menu.styling.id });

await api.appointment.create({ inlineClientName: "Walk-in", additionalClientIds: [], startsAt: at(0, 17), locationType: "inSalon" });

// Tomorrow, for the Calendar View.
await api.appointment.create({ primaryClientId: emily.id, additionalClientIds: [], startsAt: at(1, 10), endsAt: at(1, 11, 30), locationType: "atHome" });

await api.clientProfile.createProfileShare({ clientId: anna.id });

console.log(`Seeded demo data for ${userId} (${language}, ${timezone}).`);
await db.$disconnect();
