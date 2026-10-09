import { createTRPCRouter } from "./trpc";
import { appointmentRouter } from "./routers/appointment";
import { clientRouter } from "./routers/client";
import { serviceMenuRouter } from "./routers/serviceMenu";
import { stylistRouter } from "./routers/stylist";

export const appRouter = createTRPCRouter({
  appointment: appointmentRouter,
  clientProfile: clientRouter,
  serviceMenu: serviceMenuRouter,
  stylist: stylistRouter
});

export type AppRouter = typeof appRouter;
