// What a page of this server may load and do: its own files and nothing from elsewhere, and it is
// shown in no frame. The framework writes scripts into the document and the pages write their
// styles there, hence 'unsafe-inline'.
function pagePolicy() {
  // Development only: the framework's tools evaluate code there and keep a live connection.
  const development = process.env.NODE_ENV !== "production";

  return [
    "default-src 'self'",
    `script-src 'self' 'unsafe-inline'${development ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    `connect-src 'self'${development ? " ws:" : ""}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'"
  ].join("; ");
}

// A Share Image is text of the Stylist's and the Client's inside an SVG, which a browser opens as
// a document. It may run nothing and load nothing but the photos it carries inside itself, so
// that a text that ever got through unescaped would still only be text.
const sharePicturePolicy = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'; sandbox";

/** @type {import('next').NextConfig} */
const nextConfig = {
  transpilePackages: ["@hcm/shared"],
  // Development only: the address under which an Android emulator reaches this machine. Without
  // it the development server refuses that browser its live connection, and a page the browser
  // has to draw itself (the page of a link that shows nothing) stays blank there.
  allowedDevOrigins: ["10.0.2.2"],
  // No "X-Powered-By: Next.js".
  poweredByHeader: false,
  // Where the Android app is downloaded from. The file is kept on another server, so /download
  // only sends the browser there. Without the address there is no such page.
  async redirects() {
    const appDownload = process.env.APP_DOWNLOAD_URL;

    return appDownload ? [{ source: "/download", destination: appDownload, permanent: false }] : [];
  },
  // Sent with every answer. Where two rules name the same header, the later rule counts.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: pagePolicy() },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          // The address of a Profile Share is all that protects it: it is told to no other site.
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()" },
          { key: "Strict-Transport-Security", value: "max-age=31536000" }
        ]
      },
      {
        // A Profile Share page, its picture and its photos are for whoever holds the link, never
        // for a search engine. robots.txt (app/robots.ts) does not close these addresses: a
        // search engine that may not read an address never sees this and can still list it.
        source: "/profile-shares/:path*",
        headers: [{ key: "X-Robots-Tag", value: "noindex, nofollow" }]
      },
      {
        source: "/profile-shares/:token/image",
        headers: [{ key: "Content-Security-Policy", value: sharePicturePolicy }]
      }
    ];
  }
};

export default nextConfig;
