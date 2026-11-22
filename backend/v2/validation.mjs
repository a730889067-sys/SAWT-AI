export const MAX_TEXT_CHARACTERS = 3000;
export const MAX_TEXT_BYTES = 12_000;

export function validateSynthesisRequest(body, env = process.env) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return { ok: false, code: "INVALID_BODY", message: "بيانات الطلب غير صالحة." };
  }
  if (typeof body.text !== "string" || !body.text.trim()) {
    return { ok: false, code: "EMPTY_TEXT", message: "اكتب نصًا قبل التحويل." };
  }
  const text = body.text.normalize("NFC").trim();
  if (text.length > MAX_TEXT_CHARACTERS || Buffer.byteLength(text, "utf8") > MAX_TEXT_BYTES) {
    return {
      ok: false,
      code: "TEXT_TOO_LONG",
      message: "الحد الحالي " + MAX_TEXT_CHARACTERS + " حرفًا في الطلب الواحد.",
      maxCharacters: MAX_TEXT_CHARACTERS
    };
  }
  const provider = body.provider === undefined ? "auto" : body.provider;
  const allowedProviders = new Set(["auto", "easyvoice-msa", "gemini-quality", "gemini-fast", "azure-f0", "elevenlabs-personal"]);
  if (typeof provider !== "string" || !allowedProviders.has(provider)) {
    return { ok: false, code: "INVALID_PROVIDER", message: "محرك الصوت المحدد غير معروف." };
  }
  const voice = body.voice === undefined ? "female" : body.voice;
  if (!new Set(["male", "female"]).has(voice)) {
    return { ok: false, code: "INVALID_VOICE", message: "اختر صوتًا رجاليًا أو نسائيًا." };
  }
  const speed = body.speed === undefined ? 1 : Number(body.speed);
  if (!Number.isFinite(speed) || speed < 0.5 || speed > 2.0) {
    return { ok: false, code: "INVALID_SPEED", message: "سرعة النطق يجب أن تكون بين 0.5 و2.0." };
  }
  if (body.cloudDataConsent !== true) {
    return {
      ok: false,
      code: "CLOUD_DATA_CONSENT_REQUIRED",
      message: "يلزم تأكيد موافقتك على إرسال النص إلى مزود الصوت الخارجي."
    };
  }
  return { ok: true, value: { text, provider, voice, speed, cloudDataConsent: true } };
}

export function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}
