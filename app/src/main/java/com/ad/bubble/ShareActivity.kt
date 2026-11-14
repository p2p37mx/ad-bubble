package com.ad.bubble

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** من يوتيوب: مشاركة ← «مشغّل عائم» → يفتح الفيديو بنافذة صغيرة فوگ كل التطبيقات */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val id = MiniPlayerService.videoId(text)
        when {
            id == null -> Toast.makeText(this, "ما لگيت رابط فيديو يوتيوب", Toast.LENGTH_LONG).show()
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "اسمح للتطبيق «بالظهور فوق التطبيقات» وعيد المشاركة", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> startForegroundService(
                Intent(this, MiniPlayerService::class.java).putExtra(MiniPlayerService.EXTRA_ID, id)
            )
        }
        finish()
    }
}
