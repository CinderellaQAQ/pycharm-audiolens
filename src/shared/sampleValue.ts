export type SourceSampleFormat = "signed-int" | "unsigned-int" | "float";

export interface SourceSampleValueFormat {
  bitDepth: number;
  sampleFormat: SourceSampleFormat;
}

export interface SampleValueScale {
  min: number;
  max: number;
  factor: number;
  offset: number;
  integer: boolean;
}

export function sourceSampleValueFormatOf(value: {
  bitDepth?: number;
  sampleFormat?: SourceSampleFormat;
}): SourceSampleValueFormat | undefined {
  const bitDepth = value.bitDepth;
  const sampleFormat = value.sampleFormat;
  if (
    !Number.isInteger(bitDepth) ||
    bitDepth === undefined ||
    bitDepth <= 0 ||
    !sampleFormat ||
    (sampleFormat === "float" ? bitDepth !== 32 && bitDepth !== 64 : bitDepth > 32)
  ) {
    return undefined;
  }
  return { bitDepth, sampleFormat };
}

export function sampleValueScaleFor(format: SourceSampleValueFormat | undefined): SampleValueScale {
  if (!format || format.sampleFormat === "float") {
    return { min: -1, max: 1, factor: 1, offset: 0, integer: false };
  }
  const factor = 2 ** (format.bitDepth - 1);
  if (format.sampleFormat === "unsigned-int") {
    return { min: 0, max: factor * 2 - 1, factor, offset: factor, integer: true };
  }
  return { min: -factor, max: factor - 1, factor, offset: 0, integer: true };
}

export function sampleValueFromNormalized(value: number, scale: SampleValueScale): number {
  const raw = value * scale.factor + scale.offset;
  const bounded = clamp(raw, scale.min, scale.max);
  return scale.integer ? Math.round(bounded) : bounded;
}

export function normalizedFromSampleValue(value: number, scale: SampleValueScale): number {
  return (clamp(value, scale.min, scale.max) - scale.offset) / scale.factor;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
