export const SAMPLE_RATE = 24000; export const STYLE_DIM = 256; export const DEFAULT_REPO = 'oddadmix/Nabra-7M-Distill-ONNX';
export type Device = 'auto' | 'webgpu' | 'wasm'; export type DType = 'fp32' | 'fp16';
export interface ProgressInfo { file: 'model' | 'voice'; loaded: number; total: number; }
export interface LoadOptions { dtype?: DType; device?: Device; worker?: boolean; repo?: string; revision?: string; modelUrl?: string; voiceUrl?: string; wasmPaths?: string; numThreads?: number; cache?: boolean; diacritize?: (text: string) => string | Promise<string>; normalize?: boolean; onProgress?: (p: ProgressInfo) => void; }
export interface GenerateOptions { speed?: number; }
export interface AudioChunk { text: string; phonemes: string; audio: Float32Array; sampleRate: number; }
export interface GeneratedAudio { audio: Float32Array; sampleRate: number; }
export interface EngineConfig { repo: string; revision: string; dtype: DType; device: Device; modelUrl?: string; voiceUrl?: string; wasmPaths?: string; numThreads?: number; cache: boolean; normalize: boolean; }
export interface ChunkAudio { graphemes: string; phonemes: string; audio: Float32Array; }
