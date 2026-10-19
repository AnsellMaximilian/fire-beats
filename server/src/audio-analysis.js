import decodeAudio from "@audio/decode";
import { beatTrack, detect as detectBeats } from "@audio/beat";

function mixToMono(channelData) {
  if (channelData.length === 1) return channelData[0];
  const sampleCount = Math.min(...channelData.map((channel) => channel.length));
  const mixed = new Float32Array(sampleCount);
  for (const channel of channelData) {
    for (let index = 0; index < sampleCount; index += 1) {
      mixed[index] += channel[index] / channelData.length;
    }
  }
  return mixed;
}

function waveformEnvelope(samples, bucketCount = 1600) {
  const count = Math.max(1, Math.min(bucketCount, samples.length));
  const envelope = new Float32Array(count * 2);
  for (let bucket = 0; bucket < count; bucket += 1) {
    const start = Math.floor((bucket * samples.length) / count);
    const end = Math.max(start + 1, Math.floor(((bucket + 1) * samples.length) / count));
    let minimum = 1;
    let maximum = -1;
    for (let index = start; index < end; index += 1) {
      const value = samples[index] || 0;
      if (value < minimum) minimum = value;
      if (value > maximum) maximum = value;
    }
    envelope[bucket * 2] = minimum;
    envelope[bucket * 2 + 1] = maximum;
  }
  return envelope;
}

export async function analyzeEncodedAudio(encodedAudio, options = {}) {
  const decoded = await decodeAudio(encodedAudio);
  const channels = decoded.channelData || [];
  if (!channels.length || !channels[0]?.length) {
    throw new Error("The audio file did not contain a decodable audio channel");
  }

  const channelMode = options.channelMode === "mix" ? "mix" : "first";
  const samples = channelMode === "mix" ? mixToMono(channels) : channels[0];
  const analysisOptions = {
    fs: decoded.sampleRate,
    minBpm: options.minBpm ?? 60,
    maxBpm: options.maxBpm ?? 200,
    delta: options.delta ?? 1.4,
  };
  const current = detectBeats(samples, analysisOptions);
  const result = {
    sampleRate: decoded.sampleRate,
    channelCount: channels.length,
    channelMode,
    duration: samples.length / decoded.sampleRate,
    current,
  };

  if (options.includeAdaptive) {
    result.adaptive = beatTrack(samples, analysisOptions);
  }
  if (options.includeWaveform) {
    result.waveform = waveformEnvelope(samples, options.waveformBuckets);
  }
  return result;
}

