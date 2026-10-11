import type { Metadata } from "next";
import type { ReactNode } from "react";
import { defaultLocale, t } from "@hcm/shared";
import "./globals.css";

// For a page that names nothing itself. A Profile Share page and the page of a link that shows
// nothing name themselves, in their own language.
export const metadata: Metadata = {
  title: t(defaultLocale, "profileShareTitle"),
  description: t(defaultLocale, "profileSharePlaceholder")
};

// No <html> here: it states the language of the page, and only the page knows it (htmlDocument.tsx).
export default function RootLayout({ children }: { children: ReactNode }) {
  return children;
}
