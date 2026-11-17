import express from "express";
import rateLimit from "express-rate-limit";
import textToSpeech from "@google-cloud/text-to-speech";

const app = express();
const client = new textToSpeech.TextToSpeechClient();
const PORT = Number(process.env.PORT || 8080);
const MAX_TEXT_CHARACTERS = 5000;
const MAX_TEXT_BYTES = 5000;
const VOICE_CACHE_MS = 60 * 60 * 1000;

let cachedVoices = null;
let voicesCachedAt = 0;

app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));
app.use(rateLimit({
  windowMs: 60_000,
  limit: 20,
  standardHeaders: "draft-7",
  legacyHeaders: false
}));

async function getArabicVoices() {
  if (cachedVoices && Date.now() - voicesCachedAt < VOICE_CACHE_MS) {
    return cachedVoices;
  }

  const [result] = await client.listVoices({ languageCode: "ar-XA" });
  cachedVoices = (result.voices || [])
    .filter(voice => (voice.languageCodes || []).includes("ar-XA"))
    .map(voice => ({
      id: voice.name,
      language: "ar-XA",
      gender: voice.ssmlGender,
      sampleRate: voice.naturalSampleRateHertz,
      quality: voice.name.includes("Chirp3-HD") ? "premium-hd" :
        voice.name.includes("Wavenet") ? "wavenet" : "standard"
    }))
    .filter(voice => voice.id && ["MALE", "FEMALE"].includes(voice.gender));

  voicesCachedAt = Date.now();
  return cachedVoices;
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "SAWT AI Voice API" });
});

app.get("/v1/voices", async (_req, res) => {
  try {
    const voices = await getArabicVoices();
    res.set("Cache-Control", "public, max-age=3600");
    res.json({ language: "ar-XA", voices });
  } catch (error) {
    console.error("voices.list failed", error.message);
    res.status(503).json({ error: "VOICE_CATALOG_UNAVAILABLE" });
  }
});

app.post("/v1/synthesize", async (req, res) => {
  const { text, voiceId, speakingRate = 1, pitch = 0 } = req.body || {};

  if (
    typeof text !== "string" ||
    !text.trim() ||
    text.length > MAX_TEXT_CHARACTERS ||
    Buffer.byteLength(text, "utf8") > MAX_TEXT_BYTES
  ) {
    return res.status(400).json({
      error: "INVALID_TEXT",
      maxCharacters: MAX_TEXT_CHARACTERS,
      maxBytes: MAX_TEXT_BYTES
    });
  }

  if (typeof voiceId !== "string" || !/^ar-XA-[A-Za-z0-9-]+$/.test(voiceId)) {
    return res.status(400).json({ error: "ONLY_MODERN_STANDARD_ARABIC_VOICES_ALLOWED" });
  }

  if (
    !Number.isFinite(speakingRate) || speakingRate < 0.7 || speakingRate > 1.3 ||
    !Number.isFinite(pitch) || pitch < -5 || pitch > 5
  ) {
    return res.status(400).json({ error: "INVALID_AUDIO_SETTINGS" });
  }

  try {
    const voices = await getArabicVoices();
    const selected = voices.find(voice => voice.id === voiceId);
    if (!selected) return res.status(400).json({ error: "VOICE_NOT_SUPPORTED" });

    const audioConfig = { audioEncoding: "MP3" };
    // Chirp 3 HD does not currently accept speaking-rate or pitch controls.
    if (!voiceId.includes("Chirp3-HD")) {
      audioConfig.speakingRate = speakingRate;
      audioConfig.pitch = pitch;
    }

    const [result] = await client.synthesizeSpeech({
      input: { text },
      voice: { languageCode: "ar-XA", name: voiceId },
      audioConfig
    });

    const audio = Buffer.from(result.audioContent || []);
    if (audio.length < 256) {
      throw new Error("Provider returned empty audio");
    }

    res.set({
      "Content-Type": "audio/mpeg",
      "Content-Disposition": "attachment; filename=sawt-speech.mp3",
      "Cache-Control": "no-store"
    }).send(audio);
  } catch (error) {
    console.error("synthesize failed", error.message);
    res.status(502).json({ error: "SYNTHESIS_FAILED" });
  }
});

app.listen(PORT, "0.0.0.0", () => console.log("SAWT Voice API listening on", PORT));
