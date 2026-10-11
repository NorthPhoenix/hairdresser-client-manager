/** @type {import('next').NextConfig} */
const nextConfig = {
  transpilePackages: ["@hcm/shared"],
  // Development only: the address under which an Android emulator reaches this machine. Without
  // it the development server refuses that browser its live connection, and a page the browser
  // has to draw itself (the page of a link that shows nothing) stays blank there.
  allowedDevOrigins: ["10.0.2.2"]
};

export default nextConfig;
