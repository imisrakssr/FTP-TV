package com.israk.ftptv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.israk.ftptv.FtpTvApp

/** TV opens straight into live TV (like switching a TV on); phones get the channel grid first. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (FtpTvApp.isTv(this)) PlayerActivity::class.java else PhoneHomeActivity::class.java
        startActivity(Intent(this, target))
        overridePendingTransition(0, 0)
        finish()
    }
}
