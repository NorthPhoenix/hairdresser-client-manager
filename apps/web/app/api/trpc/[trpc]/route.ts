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
      }),
    // The reply to an unexpected failure says nothing about it, so the log has to.
    onError: ({ error, path }) => {
      if (error.code === "INTERNAL_SERVER_ERROR") {
        console.error(`tRPC ${path ?? "request"} failed:`, error.cause ?? error);
      }
    }
  });
}

export { handler as GET, handler as POST };
