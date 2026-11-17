# خادم أصوات SAWT AI

خادم اختياري لتوليد ملفات MP3 بأصوات Google Cloud Text-to-Speech المخصصة للغة العربية الفصحى.

## شرط الفصحى
يستخدم الخادم رمز اللغة `ar-XA` فقط. توضح وثائق Google أن `ar-XA` هو رمز العربية الفصحى الحديثة (Modern Standard Arabic). لا يقبل الخادم أصوات اللهجات الأخرى.

## النشر
1. أنشئ مشروعًا في Google Cloud وفعّل Cloud Text-to-Speech API.
2. فعّل الفوترة وامنح حساب خدمة Cloud Run صلاحية استخدام Text-to-Speech.
3. انشر مجلد backend على Cloud Run باستخدام Dockerfile.
4. لا تضع مفاتيح الخدمة داخل تطبيق Android أو GitHub. استخدم هوية خدمة Cloud Run (Application Default Credentials).
5. اختبر /health ثم /v1/voices.

## نقاط API
- GET /health
- GET /v1/voices — يعرض الأصوات ar-XA المتاحة فقط.
- POST /v1/synthesize — يستقبل text وvoiceId وspeakingRate وpitch ويعيد MP3.

## حدود وحماية
- حد النص 5000 حرف.
- تحديد معدل الطلبات.
- لا يتم تسجيل النصوص في سجل التطبيق عمدًا.
- يلزم إضافة مصادقة المستخدم، حصص الاستخدام، ومراقبة التكلفة قبل الإطلاق العام.
- قد تختلف أسعار الخدمة حسب نوع الصوت والاستخدام؛ راجع صفحة الأسعار الرسمية.
