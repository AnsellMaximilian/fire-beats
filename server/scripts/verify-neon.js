import "../src/environment.js";

import { randomBytes } from "node:crypto";
import { Readable } from "node:stream";

import { checkDatabase, closeDatabase } from "../src/database.js";
import {
  defaultTracksBucket,
  deleteObject,
  downloadObject,
  publicObjectUrl,
  signedObjectUrl,
  uploadObject,
  userTracksBucket,
} from "../src/storage.js";

const marker = randomBytes(12);
const key = `diagnostics/${Date.now()}-${randomBytes(4).toString("hex")}.bin`;

try {
  const database = await checkDatabase();
  console.log(`Postgres OK: ${database.database}; tracks table=${database.tracks_table}`);

  const jwksResponse = await fetch(process.env.NEON_AUTH_JWKS_URL);
  const jwks = await jwksResponse.json();
  if (!jwksResponse.ok || !Array.isArray(jwks.keys) || !jwks.keys.length) {
    throw new Error("Neon Auth JWKS endpoint did not return signing keys");
  }
  console.log(`Neon Auth OK: ${jwks.keys.length} signing key(s)`);

  await uploadObject({
    bucket: userTracksBucket,
    key,
    body: Readable.from(marker),
    contentLength: marker.length,
    contentType: "application/octet-stream",
  });
  const downloaded = await downloadObject(userTracksBucket, key);
  if (!downloaded.equals(marker)) throw new Error("Private object round trip did not match");
  const signedResponse = await fetch(await signedObjectUrl(userTracksBucket, key, 60));
  if (!signedResponse.ok || !Buffer.from(await signedResponse.arrayBuffer()).equals(marker)) {
    throw new Error("Private signed URL did not return the uploaded object");
  }
  console.log("Private object storage OK: put, get, and signed URL");

  await uploadObject({
    bucket: defaultTracksBucket,
    key,
    body: marker,
    contentLength: marker.length,
    contentType: "application/octet-stream",
    publicRead: true,
  });
  const publicResponse = await fetch(publicObjectUrl(defaultTracksBucket, key));
  if (!publicResponse.ok || !Buffer.from(await publicResponse.arrayBuffer()).equals(marker)) {
    throw new Error(`Public object URL failed with HTTP ${publicResponse.status}`);
  }
  console.log("Public object storage OK: anonymous URL");
} finally {
  await Promise.allSettled([
    deleteObject(userTracksBucket, key),
    deleteObject(defaultTracksBucket, key),
  ]);
  await closeDatabase();
}
