import { getEligibleProviderIds } from "./provider-catalog.mjs";

export async function synthesizeWithFallback(request, env, adapters) {
  const eligible = getEligibleProviderIds(env);
  const sequence = request.provider === "auto"
    ? eligible
    : eligible.filter(id => id === request.provider);

  if (sequence.length === 0) {
    const error = new Error("لا يوجد محرك مجاني مهيأ ومتاح حاليًا.");
    error.code = "NO_FREE_PROVIDER_AVAILABLE";
    throw error;
  }

  const failures = [];
  for (const providerId of sequence) {
    const adapter = adapters[providerId];
    if (typeof adapter !== "function") continue;
    try {
      const result = await adapter(request);
      return { ...result, providerId, fallbackCount: failures.length };
    } catch (error) {
      failures.push({ providerId, code: error.status || error.code || "PROVIDER_ERROR" });
      if (request.provider !== "auto" || error.retryable === false) break;
    }
  }
  const error = new Error("تعذر إنشاء الصوت من المحركات المجانية المتاحة الآن.");
  error.code = "ALL_FREE_PROVIDERS_FAILED";
  error.failures = failures;
  throw error;
}
