import path from "node:path";
import { fileURLToPath } from "node:url";

import dotenv from "dotenv";

const sourceDirectory = path.dirname(fileURLToPath(import.meta.url));
const serverDirectory = path.resolve(sourceDirectory, "..");
const projectDirectory = path.resolve(serverDirectory, "..");

// Render injects environment variables directly. Locally, Neon writes the
// branch-scoped values to the repository-level .env.local file.
dotenv.config({ path: path.join(projectDirectory, ".env.local"), quiet: true });
dotenv.config({ path: path.join(serverDirectory, ".env"), override: false, quiet: true });

export { projectDirectory, serverDirectory };

export function requireEnvironment(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

export function postgresConnectionString(name) {
  const url = new URL(requireEnvironment(name));
  if (["prefer", "require", "verify-ca"].includes(url.searchParams.get("sslmode"))) {
    url.searchParams.set("sslmode", "verify-full");
  }
  return url.toString();
}
