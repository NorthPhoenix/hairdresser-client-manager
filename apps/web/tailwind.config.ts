import type { Config } from "tailwindcss";
import { themeTokens } from "@hcm/shared";

export default {
  content: ["./app/**/*.{ts,tsx}", "../../packages/shared/src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: themeTokens.colors,
      borderRadius: themeTokens.borderRadius,
      fontFamily: {
        body: [...themeTokens.fontFamily.body],
        display: [...themeTokens.fontFamily.display]
      }
    }
  }
} satisfies Config;
