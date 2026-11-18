# إشعارات وتراخيص المكونات الخارجية — SAWT AI 1.5.0

يحتوي التطبيق على مكونات خارجية. احتفظ بهذه الإشعارات مع النسخ الموزعة، وراجع النص الكامل للترخيص قبل إعادة توزيع التطبيق أو أوزان النموذج.

| المكوّن | الاستخدام | الترخيص | المصدر |
|---|---|---|---|
| Sherpa-ONNX Android AAR v2.0.1 | محرك الاستدلال الصوتي الأصلي لـ Android | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| Supertonic 3 INT8 model weights | نموذج تحويل النص إلى كلام، 31 لغة منها العربية والإنجليزية | OpenRAIL-M | https://huggingface.co/Supertone/supertonic-3 |
| Supertonic 3 quantized model package | نسخة INT8 المجهزة لـ Sherpa-ONNX | OpenRAIL-M (الأوزان) مع إشعارات التحويل | https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11 |
| Supertonic 3 mobile-optimized vector estimator | ملف متجهات محسّن لتسريع الاستدلال على CPU في الهواتف، مشتق من أوزان Supertonic 3 | OpenRAIL-M | https://huggingface.co/WilburDev/supertonic-3-mobile |
| Apache Commons Compress 1.27.1 | فك ضغط أرشيف النموذج BZip2/TAR | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Apache Commons IO 2.18.0 | مكتبة مساعدة لعمليات الملفات | Apache-2.0 | https://commons.apache.org/proper/commons-io/ |

## ملاحظات مهمة حول Supertonic 3

- حقوق النموذج © Supertone Inc.، 2026.
- أوزان النموذج ليست مرخصة بموجب MIT؛ الترخيص المطبق عليها هو BigScience OpenRAIL-M.
- يلزم إظهار الإسناد إلى Supertone والمحافظة على قيود الاستخدام المرفقة بالترخيص.
- تتضمن القيود حظر الاستخدام المخالف للقانون، وإيذاء الآخرين، وانتحال الأشخاص دون موافقتهم، وإنشاء معلومات مضللة أو نشر مخرجات مولدة آليًا دون الإفصاح المناسب في السياق الذي قد يسبب التضليل.
- لا يوفر التطبيق ميزة استنساخ صوت المستخدم أو تحميل عينات لتقليد أصوات أشخاص آخرين.
- يجب مراجعة النسخة الكاملة من الترخيص عند المصدر الرسمي: https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE
- قد تختلف شروط إعادة توزيع النموذج عن شروط إعادة توزيع كود المحرك. لا تخلط بين Apache-2.0 للمحرك وOpenRAIL-M للأوزان.

## مكونات المصدر القديمة غير المضمنة في إصدار 1.5.0

يحتفظ المستودع ببعض ملفات Nabra وIchkil وملفات العميل السحابي من النموذج السابق لأغراض التوثيق/التاريخ. لم تعد واجهة إصدار 1.5.0 تستخدم هذه الملفات أو تحزمها ضمن مسار توليد الصوت. إذا أُعيد تفعيلها في بناء لاحق، فيجب تطبيق إشعاراتها وتراخيصها الخاصة قبل التوزيع.

لا يعد هذا الملف استشارة قانونية.

## تحسين Supertonic للأجهزة المحمولة

يستخدم SAWT AI ملف `vector_estimator.int8.onnx` المحسّن من مشروع WilburDev/supertonic-3-mobile. يذكر صاحب المشروع أن التعديل يستبدل طبقات Conv1d النقطية بمسار Transpose/MatMul مع تكميم ديناميكي، ويعرض قياسات على Galaxy Note 8؛ هذه القياسات مرجعية وليست ضمانًا لأداء كل هاتف. بقية ملفات النموذج مستمدة من حزمة Supertonic 3 / sherpa-onnx المشار إليها أعلاه. راجع بطاقة النموذج والترخيص OpenRAIL-M قبل إعادة التوزيع.
