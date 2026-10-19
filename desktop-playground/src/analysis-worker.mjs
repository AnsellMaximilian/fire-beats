import fs from "node:fs/promises";
import { parentPort, workerData } from "node:worker_threads";
import { analyzeEncodedAudio } from "../../server/src/audio-analysis.js";

try {
  const encodedAudio = await fs.readFile(workerData.filePath);
  const result = await analyzeEncodedAudio(encodedAudio, workerData.options);
  parentPort.postMessage({ ok: true, result });
} catch (error) {
  parentPort.postMessage({
    ok: false,
    error: error instanceof Error ? error.message : String(error),
  });
}
