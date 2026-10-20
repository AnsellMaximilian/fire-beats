import esbuild from "esbuild";
import path from "node:path";
import { fileURLToPath } from "node:url";

const serverDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

await esbuild.build({
  absWorkingDir: serverDirectory,
  entryPoints: ["./src/browser-auth.js"],
  outfile: "./public/auth-client.js",
  bundle: true,
  format: "iife",
  platform: "browser",
  target: ["es2020"],
  minify: process.env.NODE_ENV === "production",
  sourcemap: process.env.NODE_ENV !== "production",
  legalComments: "none",
});

console.log("Built public/auth-client.js");
