import assert from "node:assert/strict";
import test from "node:test";
import { readAudioFileFacts } from "../webview/audioFacts";
import {
  normalizedFromSampleValue,
  sampleValueFromNormalized,
  sampleValueScaleFor,
  sourceSampleValueFormatOf
} from "./sampleValue";

test("signed integer scales use the source PCM range", () => {
  const scale16 = sampleValueScaleFor({ bitDepth: 16, sampleFormat: "signed-int" });
  assert.deepEqual({ min: scale16.min, max: scale16.max }, { min: -32768, max: 32767 });
  assert.equal(sampleValueFromNormalized(-1, scale16), -32768);
  assert.equal(sampleValueFromNormalized(32767 / 32768, scale16), 32767);
  assert.equal(sampleValueFromNormalized(1, scale16), 32767);
  assert.equal(normalizedFromSampleValue(sampleValueFromNormalized(1, scale16), scale16), 32767 / 32768);
  assert.equal(normalizedFromSampleValue(16384, scale16), 0.5);

  const scale24 = sampleValueScaleFor({ bitDepth: 24, sampleFormat: "signed-int" });
  assert.deepEqual({ min: scale24.min, max: scale24.max }, { min: -8388608, max: 8388607 });
});

test("unsigned 8-bit PCM keeps its original zero-to-255 values", () => {
  const scale = sampleValueScaleFor({ bitDepth: 8, sampleFormat: "unsigned-int" });
  assert.deepEqual({ min: scale.min, max: scale.max }, { min: 0, max: 255 });
  assert.equal(sampleValueFromNormalized(-1, scale), 0);
  assert.equal(sampleValueFromNormalized(0, scale), 128);
  assert.equal(sampleValueFromNormalized(127 / 128, scale), 255);
  assert.equal(normalizedFromSampleValue(255, scale), 127 / 128);
});

test("floating-point and unknown sources retain decoded float sample values", () => {
  const floatScale = sampleValueScaleFor({ bitDepth: 32, sampleFormat: "float" });
  const unknownScale = sampleValueScaleFor(undefined);
  assert.deepEqual(floatScale, { min: -1, max: 1, factor: 1, offset: 0, integer: false });
  assert.deepEqual(unknownScale, floatScale);
  assert.equal(sourceSampleValueFormatOf({ bitDepth: undefined, sampleFormat: "signed-int" }), undefined);
  assert.equal(sourceSampleValueFormatOf({ bitDepth: 64, sampleFormat: "signed-int" }), undefined);
});

test("WAV facts expose integer sample depth for the waveform scale", () => {
  const wav = new Uint8Array(44);
  writeAscii(wav, 0, "RIFF");
  writeAscii(wav, 8, "WAVE");
  writeAscii(wav, 12, "fmt ");
  writeAscii(wav, 36, "data");
  const view = new DataView(wav.buffer);
  view.setUint32(4, 36, true);
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, 16000, true);
  view.setUint32(28, 32000, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  view.setUint32(40, 0, true);
  assert.deepEqual(readAudioFileFacts(wav, "voice.wav"), {
    sampleRate: 16000,
    bitDepth: 16,
    sampleFormat: "signed-int"
  });
});

test("FLAC STREAMINFO exposes its original integer sample depth", () => {
  const flac = new Uint8Array(42);
  writeAscii(flac, 0, "fLaC");
  flac[4] = 0;
  flac[7] = 34;
  const sampleRate = 48000;
  const bitDepth = 24;
  flac[18] = sampleRate >> 12;
  flac[19] = sampleRate >> 4;
  flac[20] = ((sampleRate & 0x0f) << 4) | ((bitDepth - 1) >> 4);
  flac[21] = ((bitDepth - 1) & 0x0f) << 4;
  assert.deepEqual(readAudioFileFacts(flac, "voice.flac"), {
    sampleRate,
    bitDepth,
    sampleFormat: "signed-int"
  });
});

function writeAscii(target: Uint8Array, offset: number, value: string): void {
  for (let index = 0; index < value.length; index += 1) {
    target[offset + index] = value.charCodeAt(index);
  }
}
