# Pool Tracker (Android)

مشروع أندرويد متكامل بلغة Kotlin ومكتبة OpenCV لتتبع مسار الكرات في ألعاب البلياردو مع مراعاة الـ Spin.

## خطوات التثبيت والتشغيل:
1. فك الضغط عن الملف `PoolTrackerAndroid.zip`.
2. افتح برنامج **Android Studio** واختر **Open** ثم حدد مجلد المشروع `PoolTrackerAndroid`.
3. انتظر حتى ينتهي Gradle من تحميل مكتبة OpenCV تلقائياً.
4. وصّل هاتفك الأندرويد (مع تفعيل USB Debugging) واضغط على **Run** أو اختر **Build > Build Bundle(s) / APK(s) > Build APK(s)** لاستخراج ملف الـ APK وتثبيته على هاتفك.

## المعايرة حسب شاشة هاتفك:
- في ملف `OverlayService.kt`، يمكنك تعديل نسب `tableRect` و `spinRect` لتطابق حدود الطاولة الداخلية وأيقونة الـ Spin على شاشة هاتفك بدقة.
- في ملف `PhysicsEngine.kt`، يمكنك تعديل `ballRadius` ليتطابق مع نصف قطر الكرة بالبكسل على شاشتك.
