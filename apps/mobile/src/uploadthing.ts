import Constants from "expo-constants";
import { generateReactNativeHelpers } from "@uploadthing/expo";
import type { FileRouter } from "uploadthing/types";

let tokenGetter: (() => Promise<string | null>) | null = null;

function getBaseUrl() {
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

export const uploadThingUrl = `${getBaseUrl()}/api/uploadthing`;

export function setUploadThingTokenGetter(getToken: () => Promise<string | null>) {
  tokenGetter = getToken;
}

export const { useImageUploader } = generateReactNativeHelpers<FileRouter>({
  url: uploadThingUrl,
  async fetch(input, init) {
    const url = input instanceof Request ? input.url : input.toString();

    if (!url.startsWith(uploadThingUrl)) {
      return fetch(input, init);
    }

    const token = await tokenGetter?.();
    const headers = new Headers(init?.headers);

    if (token) {
      headers.set("authorization", `Bearer ${token}`);
    }

    return fetch(input, {
      ...init,
      headers
    });
  }
});
