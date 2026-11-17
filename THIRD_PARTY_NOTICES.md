# إشعارات وتراخيص المكونات الخارجية — SAWT AI

يحتوي محرك الصوت المحلي على مكونات خارجية. يجب الاحتفاظ بهذه الإشعارات ومراجعة شروط كل مكوّن قبل توزيع التطبيق.

| المكوّن | الاستخدام | الترخيص | المصدر |
|---|---|---|---|
| Nabra-7M-Distill-ONNX | نموذج صوت عربي فصيح بصوت نسائي واحد | Apache-2.0 | https://huggingface.co/oddadmix/Nabra-7M-Distill-ONNX |
| nabra-tts | واجهة JavaScript ومحرك تشغيل النموذج | Apache-2.0 | https://github.com/Oddadmix/nabra-tts |
| eSpeak-NG 1.52 | التحويل من النص العربي إلى الفونيمات، مضمّن ضمن حزمة Nabra | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| منطق phonemizer المنقول | معالجة الفونيمات وعلامات الترقيم ضمن Nabra | GPL-3.0 | https://github.com/bootphon/phonemizer |
| Ichkil v0.3.0 | التشكيل الآلي للنص العربي | MIT | https://huggingface.co/ichkil/ichkil |
| ONNX Runtime Web | تنفيذ نماذج ONNX عبر WASM | MIT | https://github.com/microsoft/onnxruntime |

## ملاحظة مهمة حول eSpeak-NG

تتضمن حزمة Nabra ملف eSpeak-NG WASM ومنطقًا منقولًا من phonemizer، وكلاهما يتضمن مكونات GPL. راجع ملف THIRD_PARTY_NOTICES الخاص بالمصدر المثبّت هنا: https://github.com/Oddadmix/nabra-tts/tree/16b5281ce61993df3526740990cf845a3f3036f4 . عند توزيع التطبيق يجب مراجعة الالتزامات القانونية الخاصة بتوفير الشيفرة المصدرية المطابقة والإشعارات وشروط إعادة التوزيع. وجود هذه المكونات في APK يجعل مراجعة الترخيص شرطًا قبل إصدار عام أو تجاري. لا يُعد هذا الملف استشارة قانونية.

## تثبيت المصادر

- يبني GitHub Actions حزمة nabra-tts من مستودع المصدر الرسمي، ويجب تثبيت رقم commit محدد لضمان قابلية إعادة البناء.
- ملفات النموذج والتشكيل تُنزّل عند أول استخدام من مستودعاتها العامة وتُخزّن محليًا.
- راجع ملفات LICENSE وTHIRD_PARTY_NOTICES في المستودعات الأصلية للحصول على النصوص الكاملة للتراخيص.
