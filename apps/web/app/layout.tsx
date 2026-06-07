import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Hairdresser Client Manager",
  description: "Client Profile share pages for Hairdresser Client Manager"
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}
