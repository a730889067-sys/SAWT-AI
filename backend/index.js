import express from "express";
import rateLimit from "express-rate-limit";
import textToSpeech from "@google-cloud/text-to-speech";

const app = express();
const client = new textToSpeech.TextToSpeechClient();
const PORT = Number(process.env.PORT || 8080);
const MAX_TEXT = 5000;

app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 30, standardHeaders: "draft-7", legacyHeaders: false }));

app.get("/health", (_req, res) => res.json({ ok: true, service: "SAWT AI Voice API" }));

app.get("/v1/voices", async (_req, res) => {
  try {
    const [result] = await client.listVoices({ languageCode: "ar-XA" });
    const voices = (result.voices || [])
      .filter(v => (v.languageCodes || []).includes("ar-XA"))
      .map(v => ({
        id: v.name,
        language: "ar-XA",
        gender: v.ssmlGender,
        sampleRate: v.naturalSampleRateHertz,
        quality: v.name.includes("Chirp3-HD") ? "premium-hd" :
          v.name.includes("Wavenet") ? "wavenet" : "standard"
      }));
    res.set("Cache-Control", "public, max-age=3600").json({ language: "ar-XA", voices });
  } catch (error) {
    console.error("voices.list failed", error.message);
    res.status(503).json({ error: "VOICE_CATALOG_UNAVAILABLE" });
  }
});

app.post("/v1/synthesize", async (req, res) => {
  const { text, voiceId, speakingRate = 1, pitch = 0 } = req.body || {};
  if (typeof text !== "string" || !text.trim() || text.length > MAX_TEXT) {
    return res.status(400).json({ error: "INVALID_TEXT", maxCharacters: MAX_TEXT });
  }
  if (typeof voiceId !== "string" || !voiceId.startsWith("ar-XA-")) {
    return res.status(400).json({ error: "ONLY_MODERN_STANDARD_ARABIC_VOICES_ALLOWED" });
  }
  if (!Number.isFinite(speakingRate) || speakingRate < 0.7 || speakingRate > 1.3 ||
      !Number.isFinite(pitch) || pitch < -5 || pitch > 5) {
    return res.status(400).json({ error: "INVALID_AUDIO_SETTINGS" });
  }

  try {
    const [catalog] = await client.listVoices({ languageCode: "ar-XA" });
    const selected = (catalog.voices || []).find(v =>
      v.name === voiceId && (v.languageCodes || []).includes("ar-XA"));
    if (!selected) return res.status(400).json({ error: "VOICE_NOT_SUPPORTED" });

    const audioConfig = { audioEncoding: "MP3" };
    // Chirp 3 HD currently does not accept speaking-rate/pitch controls.
    if (!voiceId.includes("Chirp3-HD")) {
      audioConfig.speakingRate = speakingRate;
      audioConfig.pitch = pitch;
    }
    const [result] = await client.synthesizeSpeech({
      input: { text },
      voice: { languageCode: "ar-XA", name: voiceId },
      audioConfig
    });
    res.set({
      "Content-Type": "audio/mpeg",
      "Content-Disposition": "attachment; filename=sawt-speech.mp3",
      "Cache-Control": "no-store"
    }).send(Buffer.from(result.audioContent));
  } catch (error) {
    console.error("synthesize failed", error.message);
    res.status(502).json({ error: "SYNTHESIS_FAILED" });
  }
});

app.listen(PORT, "0.0.0.0", () => console.log("SAWT Voice API listening on", PORT));
