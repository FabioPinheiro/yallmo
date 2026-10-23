import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

// sbt owns Scala compilation; Vite only bundles already-linked JavaScript.
// Fixed linker directories avoid parsing sbt output or recursively launching sbt.
export default defineConfig(({ command }) => ({
  resolve: {
    alias: {
      "@view-app": fileURLToPath(
        new URL(`./target/scalajs-${command === "serve" ? "dev" : "prod"}/main.js`, import.meta.url),
      ),
    },
  },
  server: {
    host: true, // Listens on all addresses, including the Docker network
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
}));
