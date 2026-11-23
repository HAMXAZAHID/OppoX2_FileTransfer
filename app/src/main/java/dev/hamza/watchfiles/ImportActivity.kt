package dev.hamza.watchfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.hamza.watchfiles.data.ServerStore
import dev.hamza.watchfiles.smb.SmbServer

/**
 * Adds a server from adb, for watches where typing is awkward:
 *
 *   adb shell am start -n dev.hamza.watchfiles/.ImportActivity \
 *     --es host 192.168.1.10 --es share Media --es user me --es pass secret --es name "My PC"
 *
 * Optional: --ei port 445, --es domain WORKGROUP. Leave out user/pass for guest access.
 */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra("host")?.trim().orEmpty()
        val share = intent.getStringExtra("share")?.trim()?.trim('\\', '/').orEmpty()
        if (host.isEmpty() || share.isEmpty()) {
            Toast.makeText(this, "Import needs host and share", Toast.LENGTH_LONG).show()
        } else {
            val name = intent.getStringExtra("name")?.trim().orEmpty()
            ServerStore(this).upsert(
                SmbServer(
                    id = "",
                    name = name.ifEmpty { "$host/$share" },
                    host = host,
                    share = share,
                    port = intent.getIntExtra("port", 445),
                    username = intent.getStringExtra("user")?.trim().orEmpty(),
                    password = intent.getStringExtra("pass").orEmpty(),
                    domain = intent.getStringExtra("domain")?.trim().orEmpty(),
                )
            )
            Toast.makeText(this, "Added ${name.ifEmpty { host }}", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
    }
}
