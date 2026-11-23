const enabled = value => String(value || "").trim().toLowerCase() === "true";

export function getProviderCatalog(env = process.env) {
  const geminiConfigured = Boolean(env.GEMINI_API_KEY) && enabled(env.GEMINI_FREE_TIER_CONFIRMED);
  const easyVoiceConfigured = Boolean(env.EASYVOICE_API_KEY) && enabled(env.EASYVOICE_FREE_API_CONFIRMED);
  const azureFreeConfigured = Boolean(
    env.AZURE_SPEECH_KEY &&
    env.AZURE_SPEECH_REGION &&
    String(env.AZURE_SPEECH_TIER || "").toUpperCase() === "F0" &&
    enabled(env.AZURE_SPEECH_FREE_TIER_CONFIRMED)
  );
  const elevenLabsPersonalConfigured = Boolean(
    env.ELEVENLABS_API_KEY &&
    env.ELEVENLABS_VOICE_ID &&
    enabled(env.ELEVENLABS_FREE_PERSONAL_CONSENT) &&
    String(env.SAWT_DISTRIBUTION_SCOPE || "").toLowerCase() === "personal"
  );

  return [
    {
      id: "easyvoice-msa",
      company: "EasyVoice",
      name: "Arabic MSA · Kokoro-82M",
      model: "Kokoro-82M (ar_m1 / ar_f1)",
      class: "cloud",
      status: easyVoiceConfigured ? "available" : "needs_setup",
      freeTier: true,
      freeTierNote: "5,000 حرف يوميًا على المفتاح المجاني، مشتركة مع موقع EasyVoice؛ صوتان فصحى ar_m1 وar_f1. الحقوق التجارية متاحة على كل الخطط، والعربية beta وتحتاج اختبارًا سمعيًا.",
      dataConsentRequired: true,
      priority: 10,
      contentType: "audio/mpeg"
    },
    {
      id: "gemini-quality",
      company: "Google",
      name: "Gemini 3.8 Flash TTS",
      model: env.GEMINI_MODEL_QUALITY || "gemini-3.8-flash-tts",
      class: "cloud",
      status: geminiConfigured ? "available" : "needs_setup",
      freeTier: true,
      freeTierNote: "فعّل فقط مع مشروع Gemini Developer API غير مربوط بالفوترة؛ الحصة المجانية محدودة وتتغير وقد تستخدم Google الطلبات لتحسين منتجاتها.",
      dataConsentRequired: true,
      priority: 20,
      contentType: "audio/wav"
    },
    {
      id: "gemini-fast",
      company: "Google",
      name: "Gemini 3.8 Flash-Lite TTS",
      model: env.GEMINI_MODEL_FAST || "gemini-3.8-flash-lite-tts",
      class: "cloud",
      status: geminiConfigured ? "available" : "needs_setup",
      freeTier: true,
      freeTierNote: "خيار أسرع، ضمن الحصة المجانية وحدود الطلبات.",
      dataConsentRequired: true,
      priority: 30,
      contentType: "audio/wav"
    },
    {
      id: "azure-f0",
      company: "Microsoft",
      name: "Azure Speech F0",
      model: "Neural TTS",
      class: "cloud",
      status: azureFreeConfigured ? "available" : "needs_setup",
      freeTier: true,
      freeTierNote: "يُفعّل فقط عند تأكيد أن مورد Azure مضبوط على F0؛ الحصة المنشورة 500,000 حرف شهريًا.",
      dataConsentRequired: false,
      priority: 40,
      contentType: "audio/mpeg"
    },
    {
      id: "elevenlabs-personal",
      company: "ElevenLabs",
      name: "ElevenLabs Free API (تجريبي شخصي)",
      model: env.ELEVENLABS_MODEL || "eleven_multilingual_v2",
      class: "cloud",
      status: elevenLabsPersonalConfigured ? "available_personal_only" : "blocked_for_public_app",
      freeTier: true,
      freeTierNote: "الخطة المجانية محدودة، مكتبة الأصوات غير متاحة عبر API للمجاني، والاستخدام التجاري يتطلب خطة مرخصة. لا يُستخدم في نسخة عامة.",
      dataConsentRequired: false,
      priority: 90,
      contentType: "audio/mpeg"
    }
  ];
}

export function getEligibleProviderIds(env = process.env) {
  return getProviderCatalog(env)
    .filter(provider => provider.status === "available" || provider.status === "available_personal_only")
    .sort((a, b) => a.priority - b.priority)
    .map(provider => provider.id);
}
