package se.svenska.trainer.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * PackageInstaller reports back here. The common case is STATUS_PENDING_USER_ACTION:
 * Android wants explicit confirmation, and the intent it hands back must be
 * launched to show that prompt.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The process is usually replaced before this is seen.
                Toast.makeText(context, "Svenska updated", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Update failed: ${msg ?: "unknown error"}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
