import { useAuth } from "@clerk/expo";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createTRPCReact, httpBatchLink } from "@trpc/react-query";
import type { AppRouter } from "@hcm/api";
import Constants from "expo-constants";
import { useEffect, useState, type ReactNode } from "react";
import { setUploadThingTokenGetter } from "../uploadthing";

export const trpc = createTRPCReact<AppRouter>();

export function getBaseUrl() {
  const configuredUrl = process.env.EXPO_PUBLIC_TRPC_URL;

  if (configuredUrl) {
    return configuredUrl.replace(/\/api\/trpc\/?$/, "").replace(/\/$/, "");
  }

  const hostUri = Constants.expoConfig?.hostUri;

  if (hostUri) {
    const host = hostUri.split(":")[0];
    return `http://${host}:3000`;
  }

  return "http://localhost:3000";
}

export function TRPCProvider({ children }: { children: ReactNode }) {
  const { getToken } = useAuth();
  const [queryClient] = useState(() => new QueryClient());
  const [trpcClient] = useState(() =>
    trpc.createClient({
      links: [
        httpBatchLink({
          url: `${getBaseUrl()}/api/trpc`,
          async headers() {
            const token = await getToken();

            return token
              ? {
                  authorization: `Bearer ${token}`
                }
              : {};
          }
        })
      ]
    })
  );

  useEffect(() => {
    setUploadThingTokenGetter(getToken);
  }, [getToken]);

  return (
    <trpc.Provider client={trpcClient} queryClient={queryClient}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </trpc.Provider>
  );
}
