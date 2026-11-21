# إشعارات وتراخيص المكونات الخارجية — SAWT AI

يحتوي التطبيق على مكونات خارجية. احتفظ بهذه الإشعارات مع النسخ الموزعة، وراجع النص الكامل للترخيص قبل إعادة توزيع التطبيق أو أوزان النموذج.

| المكوّن | الاستخدام | الترخيص | المصدر |
|---|---|---|---|
| Sherpa-ONNX Android AAR v2.0.1 | محرك الاستدلال الصوتي الأصلي لـ Android | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| Supertonic 3 INT8 model weights | نموذج تحويل النص إلى كلام، 31 لغة منها العربية والإنجليزية | OpenRAIL-M | https://huggingface.co/Supertone/supertonic-3 |
| Supertonic 3 quantized model package | نسخة INT8 المجهزة لـ Sherpa-ONNX | OpenRAIL-M (الأوزان) مع إشعارات التحويل | https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11 |
| Supertonic 3 mobile-optimized vector estimator | ملف متجهات محسّن لتسريع الاستدلال على CPU في الهواتف، مشتق من أوزان Supertonic 3 | OpenRAIL-M | https://huggingface.co/WilburDev/supertonic-3-mobile |
| Apache Commons Compress 1.27.1 | فك ضغط أرشيف النموذج BZip2/TAR | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Apache Commons IO 2.18.0 | مكتبة مساعدة لعمليات الملفات | Apache-2.0 | https://commons.apache.org/proper/commons-io/ |
| Ichkil v0.3.0 ONNX model | إضافة التشكيل العربي تلقائيًا في مربع الكتابة | MIT | https://huggingface.co/ichkil/ichkil |
| ONNX Runtime Web 1.30.0 | تشغيل نموذج التشكيل داخل WebView عبر WebAssembly | MIT | https://github.com/microsoft/onnxruntime |
| AndroidX WebKit 1.18.0 | WebViewAssetLoader لتقديم ملفات التشكيل المحلية بأمان | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/webkit |

## ملاحظات مهمة حول Supertonic 3

- حقوق النموذج © Supertone Inc.، 2026.
- أوزان النموذج ليست مرخصة بموجب MIT؛ الترخيص المطبق عليها هو BigScience OpenRAIL-M.
- يلزم إظهار الإسناد إلى Supertone والمحافظة على قيود الاستخدام المرفقة بالترخيص.
- تتضمن القيود حظر الاستخدام المخالف للقانون، وإيذاء الآخرين، وانتحال الأشخاص دون موافقتهم، وإنشاء معلومات مضللة أو نشر مخرجات مولدة آليًا دون الإفصاح المناسب في السياق الذي قد يسبب التضليل.
- لا يوفر التطبيق ميزة استنساخ صوت المستخدم أو تحميل عينات لتقليد أصوات أشخاص آخرين.
- يجب مراجعة النسخة الكاملة من الترخيص عند المصدر الرسمي: https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE
- قد تختلف شروط إعادة توزيع النموذج عن شروط إعادة توزيع كود المحرك. لا تخلط بين Apache-2.0 للمحرك وOpenRAIL-M للأوزان.

## مكونات المصدر المضمنة أو الاختيارية داخل APK

- يضم APK صفحة Ichkil المحلية للتشكيل، كما يضم مصدر Nabra-7M-Distill/محركه وeSpeak NG العربي ضمن ملفات WebView المجمّعة.
- تُنزّل أوزان Nabra (ONNX FP16) وحزمة الصوت من مستودع Hugging Face عند اختيار الحزمة أول مرة، ثم تُخزّن في ذاكرة WebView المحلية. شيفرة المحرك وملفات WebAssembly التشغيلية مضمّنة داخل APK.
- لا يضمّن مسار Android خدمة Google Cloud أو مفاتيح API.
- يحوي `voice-engine/vendor/nabra-tts/` نسخة المصدر المثبتة على commit `16b5281ce61993df3526740990cf845a3f3036f4` مع ملفات الترخيص وإشعارات المكونات الخارجية.

## Nabra-7M-Distill والصوت الفصيح الإضافي

- المصدر: https://huggingface.co/oddadmix/Nabra-7M-Distill-ONNX، النموذج العربي MSA واحد ويعمل عبر ONNX Runtime Web.
- كود Nabra-tts المضمن من https://github.com/Oddadmix/nabra-tts، مثبت على commit `16b5281ce61993df3526740990cf845a3f3036f4`، برخصة Apache-2.0.
- يتضمن eSpeak NG Arabic WASM 1.52 بترخيص GPL-3.0-or-later. مصدره محفوظ في `voice-engine/vendor/nabra-tts/src/vendor/espeak-ng-ar.mjs`، مع إشعار الرخصة في `voice-engine/vendor/nabra-tts/THIRD_PARTY_NOTICES.md`.
- راجع ملفات المصدر والرخصة في المستودع واحتفظ بها عند إعادة التوزيع. Nabra يظل خيارًا تجريبيًا حتى اختبار النطق والأداء على Android فعلي.

## Ichkil والتشكيل العربي

- يستخدم SAWT AI 1.6.0 نموذج Ichkil v0.3.0 لإضافة الحركات العربية تلقائيًا.
- ينزل ملف النموذج (نحو 22.3 MB) من مستودع النموذج عند أول استخدام ويخزنه محليًا؛ لا يُضمّن وزن النموذج داخل APK.
- يعلن مستودع Ichkil عن ترخيص MIT للنموذج؛ احتفظ بإشعار الترخيص والرابط عند إعادة التوزيع.
- يستخدم التطبيق ONNX Runtime Web 1.30.0 برخصة MIT، وAndroidX WebKit 1.18.0 برخصة Apache-2.0.
- يعمل التشكيل محليًا بعد اكتمال التنزيل الأول ولا يرسل نص المستخدم إلى خدمة تشكيل سحابية.
- التشكيل الآلي احتمالي ولا يضمن صحة الإعراب أو الأسماء 100%. راجع نموذج Ichkil الأصلي وحدوده قبل استخدام النتائج الحساسة.

## تحسين Supertonic للأجهزة المحمولة

يستخدم SAWT AI ملف `vector_estimator.int8.onnx` المحسّن من مشروع WilburDev/supertonic-3-mobile. يذكر صاحب المشروع أن التعديل يستبدل طبقات Conv1d النقطية بمسار Transpose/MatMul مع تكميم ديناميكي، ويعرض قياسات على Galaxy Note 8؛ هذه القياسات مرجعية وليست ضمانًا لأداء كل هاتف. بقية ملفات النموذج مستمدة من حزمة Supertonic 3 / sherpa-onnx المشار إليها أعلاه. راجع بطاقة النموذج والترخيص OpenRAIL-M قبل إعادة التوزيع.


## Nabra-7M-Distill (اختياري، عبر مكتبة الأصوات)

- المصدر: https://huggingface.co/oddadmix/Nabra-7M-Distill-ONNX
- نموذج ONNX الفصيح الحديث والحزمة الصوتية: Apache-2.0.
- مكتبة `nabra-tts` الإصدار 0.1.0: https://github.com/Oddadmix/nabra-tts — Apache-2.0.
- تتضمن حزمة Nabra المكتبية eSpeak NG 1.52 وبيانات العربية والإنجليزية المجمّعة إلى WebAssembly؛ ترخيص هذا المكوّن GPL-3.0-or-later. المصدر: https://github.com/espeak-ng/espeak-ng/tree/1.52.0.
- ONNX Runtime Web: MIT.
- عند إعادة توزيع APK، احتفظ بهذه الإشعارات، ووفر المصدر الموافق للمكونات المرخصة بـ GPL-3.0-or-later وفقًا لشروطها.
- Nabra صوت واحد للفصحى الحديثة؛ ليس مجموعة أصوات رجالية/نسائية متعددة. يظل خيارًا تجريبيًا حتى اختبار النطق والأداء على Android فعلي.
