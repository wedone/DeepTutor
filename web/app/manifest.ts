import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "DeepTutor",
    short_name: "DeepTutor",
    description: "Agent-native intelligent learning companion",
    start_url: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#0f1115",
    icons: [
      { src: "/brand/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/brand/color-logo.png", sizes: "512x512", type: "image/png" },
      {
        src: "/brand/color-logo.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
