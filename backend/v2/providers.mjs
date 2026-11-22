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
  return [408, 429, 500, 502, 503, 504].includes(status);
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

async function synthesizeGemini({ text, voice, model, providerId }, env, fetchImpl) {
  if (!env.GEMINI_API_KEY) throw new ProviderError(providerId, 503, "GEMINI_NOT_CONFIGURED", true);
  const voiceName = voice === "male" ? "Puck" : "Kore";
  const url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent?key=" + encodeURIComponent(env.GEMINI_API_KEY);
  const payload = {
    contents: [{
      role: "user",
      parts: [{
        text,
        speechMetadata: {
          style: "Read the exact text in clear, formal Modern Standard Arabic (Al-Fusha). Use careful Arabic articulation, neutral delivery, natural pauses, and do not add or omit words."
        }
      }]
    }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: {
        voiceConfig: { prebuiltVoiceConfig: { voiceName } },
        languageCode: "ar"
      }
    }
  };
  const response = await checkedFetch(fetchImpl, url, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
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

async function synthesizeElevenLabs({ text, providerId }, env, fetchImpl) {
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
      voice_settings: { stability: 0.55, similarity_boost: 0.8, style: 0.15, use_speaker_boost: true }
    })
  }, providerId);
  const audio = Buffer.from(await response.arrayBuffer());
  if (audio.length < 256) throw new ProviderError(providerId, 502, "ELEVENLABS_AUDIO_INVALID", true);
  return { audio, contentType: "audio/mpeg", extension: "mp3" };
}

export function createProviderAdapters(env = process.env, fetchImpl = fetch) {
  return {
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
