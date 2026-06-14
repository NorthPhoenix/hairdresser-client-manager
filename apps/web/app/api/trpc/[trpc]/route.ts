import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { auth } from "@clerk/nextjs/server";
import { appRouter, createTRPCContext } from "@hcm/api";

function handler(request: Request) {
  return fetchRequestHandler({
    endpoint: "/api/trpc",
    req: request,
    router: appRouter,
    createContext: () =>
      createTRPCContext({
        auth: async () => {
          const clerkAuth = await auth();

          return {
            userId: clerkAuth.userId
          };
        }
      })
  });
}

export { handler as GET, handler as POST };
