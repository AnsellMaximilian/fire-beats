import "../src/environment.js";
import { postgresConnectionString } from "../src/environment.js";

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import pg from "pg";

const { Client } = pg;
const scriptsDirectory = path.dirname(fileURLToPath(import.meta.url));
const migrationsDirectory = path.resolve(scriptsDirectory, "..", "migrations");
const connectionString = process.env.DATABASE_URL_UNPOOLED
  ? postgresConnectionString("DATABASE_URL_UNPOOLED")
  : postgresConnectionString("DATABASE_URL");

const client = new Client({ connectionString });

try {
  await client.connect();
  await client.query(`CREATE TABLE IF NOT EXISTS app_migrations (
    filename text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
  )`);

  const files = (await fs.readdir(migrationsDirectory))
    .filter((name) => name.endsWith(".sql"))
    .sort();

  for (const filename of files) {
    const exists = await client.query("SELECT 1 FROM app_migrations WHERE filename = $1", [filename]);
    if (exists.rowCount) {
      console.log(`Already applied ${filename}`);
      continue;
    }

    const sql = await fs.readFile(path.join(migrationsDirectory, filename), "utf8");
    await client.query("BEGIN");
    try {
      await client.query(sql);
      await client.query("INSERT INTO app_migrations (filename) VALUES ($1)", [filename]);
      await client.query("COMMIT");
      console.log(`Applied ${filename}`);
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    }
  }
} finally {
  await client.end();
}
