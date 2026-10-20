import "../src/environment.js";

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { analyzeEncodedAudio } from "../src/audio-analysis.js";
import { closeDatabase, upsertDefaultTrack } from "../src/database.js";
import { defaultTracksBucket, uploadObject } from "../src/storage.js";

const scriptsDirectory = path.dirname(fileURLToPath(import.meta.url));
const sourceDirectory = path.resolve(
  process.argv[2] || path.join(scriptsDirectory, "..", "default-tracks"),
);
const supported = new Set([".mp3", ".wav", ".m4a", ".aac", ".ogg"]);
const mimeTypes = {
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".m4a": "audio/mp4",
  ".aac": "audio/aac",
  ".ogg": "audio/ogg",
};

try {
  const entries = await fs.readdir(sourceDirectory, { withFileTypes: true });
  const files = entries.filter((entry) => entry.isFile() && supported.has(path.extname(entry.name).toLowerCase()));
  if (!files.length) {
    console.log(`No supported audio files found in ${sourceDirectory}`);
  }

  for (const file of files) {
    const absolutePath = path.join(sourceDirectory, file.name);
    const bytes = await fs.readFile(absolutePath);
    const id = createHash("sha256").update(bytes).digest("hex").slice(0, 16);
    const extension = path.extname(file.name).toLowerCase();
    const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, "-");
    const objectKey = `defaults/${id}/${safeName}`;
    console.log(`Analyzing ${file.name}…`);
    const decoded = await analyzeEncodedAudio(bytes, { minBpm: 60, maxBpm: 200 });
    const analysis = {
      bpm: Math.round(decoded.current.bpm * 10) / 10,
      beats: Array.from(decoded.current.beats, (beat) => Math.round(beat * 1000) / 1000),
      duration: Math.round(decoded.duration * 100) / 100,
    };
    await uploadObject({
      bucket: defaultTracksBucket,
      key: objectKey,
      body: bytes,
      contentLength: bytes.length,
      contentType: mimeTypes[extension],
      publicRead: true,
    });
    await upsertDefaultTrack({
      id,
      name: path.basename(file.name, extension),
      bucket: defaultTracksBucket,
      objectKey,
      mimeType: mimeTypes[extension],
      sizeBytes: bytes.length,
      ...analysis,
    });
    console.log(`Seeded ${file.name} (${analysis.bpm} BPM)`);
  }
} finally {
  await closeDatabase();
}
