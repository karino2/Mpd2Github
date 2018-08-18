package io.github.karino2.mpd2github

import android.content.*
import android.widget.Toast

class CopyIdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val idString = intent.getStringExtra("postid")
        // ClipData clip = ClipData.newRawUri("result", Uri.parse(uriString));
        val clip = ClipData.newPlainText("result", idString)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "Copied: " + idString, Toast.LENGTH_SHORT).show()
    }

}