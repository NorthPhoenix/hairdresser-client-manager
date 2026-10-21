import { createTRPCRouter } from "./trpc";
import { appointmentRouter } from "./routers/appointment";
import { clientRouter } from "./routers/client";
import { serviceMenuRouter } from "./routers/serviceMenu";
import { statsRouter } from "./routers/stats";
import { stylistRouter } from "./routers/stylist";

export const appRouter = createTRPCRouter({
  appointment: appointmentRouter,
  clientProfile: clientRouter,
  serviceMenu: serviceMenuRouter,
  stats: statsRouter,
  stylist: stylistRouter
});

export type AppRouter = typeof appRouter;
