import { escapeXml } from "./validation.mjs";

export class ProviderError extends Error {
  constructor(providerId, status, message, retryable = false) {
    super(message);
    this.name = "ProviderError";
    this.providerId = providerId;
    this.status = status;
    this.retryable = retryable;
  }
}

async function readProviderError(response) {
  const raw = await response.text().catch(() => "");
  let message = raw.slice(0, 500);
  try {
    const parsed = JSON.parse(raw);
    message = parsed.error?.message || parsed.detail || parsed.message || message;
  } catch { /* non-JSON error body */ }
  return message || "HTTP_" + response.status;
}

function retryableStatus(status) {
  return [0, 401, 403, 404, 408, 429, 500, 502, 503, 504].includes(status);
}

async function checkedFetch(fetchImpl, url, options, providerId, timeoutMs = 120_000) {
  let response;
  try {
    response = await fetchImpl(url, { ...options, signal: AbortSignal.timeout(timeoutMs) });
  } catch (error) {
    const code = error.name === "TimeoutError" ? "PROVIDER_TIMEOUT" : "PROVIDER_NETWORK_ERROR";
    throw new ProviderError(providerId, 0, code, true);
  }
  if (!response.ok) {
    const message = await readProviderError(response);
    throw new ProviderError(providerId, response.status, message, retryableStatus(response.status));
  }
  return response;
}

let easyVoiceRequests = [];
let easyVoiceInFlight = 0;

async function withEasyVoiceCapacity(task) {
  const now = Date.now();
  easyVoiceRequests = easyVoiceRequests.filter(timestamp => now - timestamp < 60_000);
  if (easyVoiceRequests.length >= 20 || easyVoiceInFlight >= 2) {
    throw new ProviderError("easyvoice-msa", 429, "EASYVOICE_FREE_RATE_LIMIT", true);
  }
  easyVoiceRequests.push(now);
  easyVoiceInFlight += 1;
  try { return await task(); }
  finally { easyVoiceInFlight = Math.max(0, easyVoiceInFlight - 1); }
}

async function synthesizeEasyVoice({ text, voice, speed, providerId }, env, fetchImpl) {
  if (!env.EASYVOICE_API_KEY) throw new ProviderError(providerId, 503, "EASYVOICE_NOT_CONFIGURED", true);
  const voiceId = voice === "male" ? "ar_m1" : "ar_f1";
  return withEasyVoiceCapacity(async () => {
    const response = await checkedFetch(fetchImpl, "https://easyvoice.ae/api/v1/audio/speech", {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + env.EASYVOICE_API_KEY,
        "Content-Type": "application/json",
        "Accept": "audio/mpeg"
      },
      body: JSON.stringify({
        model: "kokoro-82m",
        input: text,
        voice: voiceId,
        response_format: "mp3",
        speed: Math.min(2, Math.max(0.5, Number(speed || 1)))
      })
    }, providerId);
    const audio = Buffer.from(await response.arrayBuffer());
    const validMp3 = audio.length > 256 && (audio.toString("ascii", 0, 3) === "ID3" || (audio[0] === 0xff && (audio[1] & 0xe0) === 0xe0));
    if (!validMp3) throw new ProviderError(providerId, 502, "EASYVOICE_AUDIO_INVALID", true);
    return { audio, contentType: "audio/mpeg", extension: "mp3" };
  });
}
async function synthesizeGemini({ text, voice, model, providerId, speed }, env, fetchImpl) {
  if (!env.GEMINI_API_KEY) throw new ProviderError(providerId, 503, "GEMINI_NOT_CONFIGURED", true);

  // Gemini 3.8 TTS uses prebuilt voice IDs, plus turn-level speech metadata.
  // Free-tier keys are sent in a header (never in the URL, where access logs can capture them).
  const voiceName = voice === "male" ? "Puck" : "Kore";
  const url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  const payload = {
    contents: [{
      role: "user",
      parts: [{
        text,
        speechMetadata: {
          style: "Read the exact transcript in clear, formal Modern Standard Arabic (Al-Fusha). Use careful articulation, natural pauses, and a speaking pace of " +
            String(Math.min(2, Math.max(0.5, Number(speed || 1)))) +
            " times normal speed. Do not add, omit, or paraphrase words."
        }
      }]
    }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: {
        voiceConfig: { prebuiltVoiceConfig: { voiceName } }
      }
    }
  };

  const response = await checkedFetch(fetchImpl, url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
      "x-goog-api-key": env.GEMINI_API_KEY
    },
    body: JSON.stringify(payload)
  }, providerId);
  const json = await response.json();
  const parts = json.candidates?.[0]?.content?.parts || [];
  const audioPart = parts.find(part => part.inlineData?.data);
  if (!audioPart) throw new ProviderError(providerId, 502, "GEMINI_AUDIO_MISSING", true);
  const audio = Buffer.from(audioPart.inlineData.data, "base64");
  if (audio.length < 512 || audio.toString("ascii", 0, 4) !== "RIFF") {
    throw new ProviderError(providerId, 502, "GEMINI_AUDIO_INVALID", true);
  }
  return { audio, contentType: "audio/wav", extension: "wav" };
}

async function synthesizeAzure({ text, voice, providerId }, env, fetchImpl) {
  const eligible = env.AZURE_SPEECH_KEY && env.AZURE_SPEECH_REGION &&
    String(env.AZURE_SPEECH_TIER).toUpperCase() === "F0" &&
    String(env.AZURE_SPEECH_FREE_TIER_CONFIRMED).toLowerCase() === "true";
  if (!eligible) throw new ProviderError(providerId, 503, "AZURE_FREE_TIER_NOT_CONFIRMED", true);
  const voiceName = voice === "male" ? "ar-YE-SalehNeural" : "ar-YE-MaryamNeural";
  const ssml = '<speak version="1.0" xml:lang="ar-YE"><voice name="' + voiceName +
    '"><prosody rate="0%">' + escapeXml(text) + "</prosody></voice></speak>";
  const url = "https://" + env.AZURE_SPEECH_REGION + ".tts.speech.microsoft.com/cognitiveservices/v1";
  const response = await checkedFetch(fetchImpl, url, {
    method: "POST",
    headers: {
      "Ocp-Apim-Subscription-Key": env.AZURE_SPEECH_KEY,
      "Content-Type": "application/ssml+xml",
      "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
      "User-Agent": "SAWT-AI"
    },
    body: ssml
  }, providerId);
  const audio = Buffer.from(await response.arrayBuffer());
  if (audio.length < 256) throw new ProviderError(providerId, 502, "AZURE_AUDIO_INVALID", true);
  return { audio, contentType: "audio/mpeg", extension: "mp3" };
}

async function synthesizeElevenLabs({ text, speed, providerId }, env, fetchImpl) {
  const personalOnly = String(env.SAWT_DISTRIBUTION_SCOPE || "").toLowerCase() === "personal" &&
    String(env.ELEVENLABS_FREE_PERSONAL_CONSENT || "").toLowerCase() === "true";
  if (!personalOnly || !env.ELEVENLABS_API_KEY || !env.ELEVENLABS_VOICE_ID) {
    throw new ProviderError(providerId, 403, "ELEVENLABS_FREE_PLAN_NOT_ELIGIBLE_FOR_PUBLIC_APP", false);
  }
  const model = env.ELEVENLABS_MODEL || "eleven_multilingual_v2";
  const url = "https://api.elevenlabs.io/v1/text-to-speech/" +
    encodeURIComponent(env.ELEVENLABS_VOICE_ID) + "?output_format=mp3_22050_32";
  const response = await checkedFetch(fetchImpl, url, {
    method: "POST",
    headers: {
      "xi-api-key": env.ELEVENLABS_API_KEY,
      "Content-Type": "application/json",
      Accept: "audio/mpeg"
    },
    body: JSON.stringify({
      text,
      model_id: model,
      language_code: "ar",
      voice_settings: { stability: 0.55, similarity_boost: 0.8, style: 0.15, use_speaker_boost: true, speed: Math.min(1.2, Math.max(0.7, Number(speed || 1))) }
    })
  }, providerId);
  const audio = Buffer.from(await response.arrayBuffer());
  if (audio.length < 256) throw new ProviderError(providerId, 502, "ELEVENLABS_AUDIO_INVALID", true);
  return { audio, contentType: "audio/mpeg", extension: "mp3" };
}

export function createProviderAdapters(env = process.env, fetchImpl = fetch) {
  return {
    "easyvoice-msa": args => synthesizeEasyVoice({ ...args, providerId: "easyvoice-msa" }, env, fetchImpl),
    "gemini-quality": args => synthesizeGemini({
      ...args, model: env.GEMINI_MODEL_QUALITY || "gemini-3.8-flash-tts", providerId: "gemini-quality"
    }, env, fetchImpl),
    "gemini-fast": args => synthesizeGemini({
      ...args, model: env.GEMINI_MODEL_FAST || "gemini-3.8-flash-lite-tts", providerId: "gemini-fast"
    }, env, fetchImpl),
    "azure-f0": args => synthesizeAzure({ ...args, providerId: "azure-f0" }, env, fetchImpl),
    "elevenlabs-personal": args => synthesizeElevenLabs({ ...args, providerId: "elevenlabs-personal" }, env, fetchImpl)
  };
}
