import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

// sbt owns Scala compilation; Vite only bundles already-linked JavaScript.
// Fixed linker directories avoid parsing sbt output or recursively launching sbt.
export default defineConfig(({ command }) => ({
  cacheDir: fileURLToPath(new URL("../target/vite/cache", import.meta.url)),
  build: {
    outDir: fileURLToPath(new URL("./dist", import.meta.url)),
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      "@view-app": fileURLToPath(
        new URL(`../target/scalajs-${command === "serve" ? "dev" : "prod"}/main.js`, import.meta.url),
      ),
    },
  },
  server: {
    // Linked Scala.js output lives beside this npm project, under web-harness/target/.
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    //host: "127.0.0.1", // Keep local transcript/WebSocket access private by default.
    host: true, // Listens on all addresses, including the Docker network
    port: 5173,
    strictPort: true,
    proxy: {
      "/rpc": {
        target: "ws://127.0.0.1:8080",
        ws: true,
        changeOrigin: true,
        rewriteWsOrigin: false, // Forward the browser's real Origin unchanged.
      },
    },
  },
}));
