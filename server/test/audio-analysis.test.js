import assert from "node:assert/strict";
import test from "node:test";
import { analyzeEncodedAudio } from "../src/audio-analysis.js";

function clickTrackWav({ durationSeconds = 8, sampleRate = 44_100, bpm = 120 } = {}) {
  const channels = 2;
  const sampleCount = durationSeconds * sampleRate;
  const pcmBytes = sampleCount * channels * 2;
  const wav = Buffer.alloc(44 + pcmBytes);
  wav.write("RIFF", 0);
  wav.writeUInt32LE(36 + pcmBytes, 4);
  wav.write("WAVE", 8);
  wav.write("fmt ", 12);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(channels, 22);
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * channels * 2, 28);
  wav.writeUInt16LE(channels * 2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36);
  wav.writeUInt32LE(pcmBytes, 40);

  const beatSamples = Math.round(sampleRate * 60 / bpm);
  for (let sample = 0; sample < sampleCount; sample += 1) {
    const phase = sample % beatSamples;
    const envelope = phase < 500 ? Math.exp(-phase / 85) : 0;
    const value = Math.round(Math.sin(sample * 0.67) * envelope * 28_000);
    const offset = 44 + sample * channels * 2;
    wav.writeInt16LE(value, offset);
    wav.writeInt16LE(value, offset + 2);
  }
  return wav;
}

test("analyzes encoded audio for the server and desktop playground", async () => {
  const result = await analyzeEncodedAudio(clickTrackWav(), {
    minBpm: 100,
    maxBpm: 140,
    includeAdaptive: true,
    includeWaveform: true,
    waveformBuckets: 200,
  });

  assert.equal(result.sampleRate, 44_100);
  assert.equal(result.channelCount, 2);
  assert.equal(result.channelMode, "first");
  assert.equal(Math.round(result.duration), 8);
  assert.ok(result.current.beats.length >= 12);
  assert.ok(result.current.onsets.length >= 12);
  assert.ok(result.current.bpm >= 100 && result.current.bpm <= 140);
  assert.ok(result.adaptive.beats.length >= 12);
  assert.equal(result.waveform.length, 400);
});

test("can downmix stereo input before analysis", async () => {
  const result = await analyzeEncodedAudio(clickTrackWav({ durationSeconds: 4 }), {
    channelMode: "mix",
    minBpm: 100,
    maxBpm: 140,
  });
  assert.equal(result.channelMode, "mix");
  assert.equal(result.channelCount, 2);
  assert.ok(result.current.beats.length > 0);
});
