package com.fudai.auto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面 v3：
 *  1. 引导开启无障碍服务
 *  2. 开始 / 停止
 *  3. 盲点模式坐标设置（识别不到控件时用）
 *  4. 排障自检：验证「能不能模拟点击」+ 测一次真实点击
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var tvPixel: TextView
    private lateinit var etBagX: EditText
    private lateinit var etBagY: EditText
    private lateinit var etJoinX: EditText
    private lateinit var etJoinY: EditText
    private lateinit var etSendX: EditText
    private lateinit var etSendY: EditText
    private lateinit var etComment: EditText
    private lateinit var cbBlind: CheckBox
    private lateinit var cbDebug: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        status = findViewById(R.id.status)
        tvPixel = findViewById(R.id.tvPixel)
        etBagX = findViewById(R.id.etBagX)
        etBagY = findViewById(R.id.etBagY)
        etJoinX = findViewById(R.id.etJoinX)
        etJoinY = findViewById(R.id.etJoinY)
        etSendX = findViewById(R.id.etSendX)
        etSendY = findViewById(R.id.etSendY)
        etComment = findViewById(R.id.etComment)
        cbBlind = findViewById(R.id.cbBlind)
        cbDebug = findViewById(R.id.cbDebug)

        etBagX.setText(prefs.getFloat("bagX", 10f).toString())
        etBagY.setText(prefs.getFloat("bagY", 12f).toString())
        etJoinX.setText(prefs.getFloat("joinX", 50f).toString())
        etJoinY.setText(prefs.getFloat("joinY", 62f).toString())
        etSendX.setText(prefs.getFloat("sendX", 85f).toString())
        etSendY.setText(prefs.getFloat("sendY", 88f).toString())
        etComment.setText(prefs.getString("comment", "") ?: "")
        cbBlind.isChecked = prefs.getBoolean("blind", true)
        cbDebug.isChecked = prefs.getBoolean("debug", true)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "设置已保存，服务每 1.2 秒读取一次，立即生效", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先在无障碍设置中启用【自动福袋助手】", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            askNotificationPermission()
            saveSettings()
            sendBroadcast(Intent(FudaiService.ACTION_START).setPackage(packageName))
            status.text = "当前状态：运行中 ✅\n打开直播间即可自动循环参与"
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendBroadcast(Intent(FudaiService.ACTION_STOP).setPackage(packageName))
            status.text = "当前状态：已停止 ⏸"
        }

        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务再自检", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            saveSettings()
            sendBroadcast(Intent(FudaiService.ACTION_SELFTEST).setPackage(packageName))
            Toast.makeText(this, "请在屏幕上方查看两条自检结果", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnTestTap).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            saveSettings()
            sendBroadcast(Intent(FudaiService.ACTION_TEST_TAP).setPackage(packageName))
            Toast.makeText(this, "3 秒内请切到抖音直播间，看福袋挂件是否被点开", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = isAccessibilityEnabled()
        val running = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("running", false)
        status.text = when {
            !ok -> "当前状态：无障碍服务未开启 ⚠️\n请先点上方第 1 步开启"
            running -> "当前状态：运行中 ✅\n打开直播间即可自动循环参与"
            else -> "当前状态：已停止 ⏸"
        }

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        tvPixel.text = "本机分辨率 ${dm.widthPixels}×${dm.heightPixels}；" +
            "福袋坐标 = (${(dm.widthPixels * p.getFloat("bagX", 10f) / 100).toInt()}, " +
            "${(dm.heightPixels * p.getFloat("bagY", 12f) / 100).toInt()}) 像素，保存后刷新"
    }

    private fun saveSettings() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putFloat("bagX", etBagX.text.toString().toFloatOrNull() ?: 10f)
            .putFloat("bagY", etBagY.text.toString().toFloatOrNull() ?: 12f)
            .putFloat("joinX", etJoinX.text.toString().toFloatOrNull() ?: 50f)
            .putFloat("joinY", etJoinY.text.toString().toFloatOrNull() ?: 62f)
            .putFloat("sendX", etSendX.text.toString().toFloatOrNull() ?: 85f)
            .putFloat("sendY", etSendY.text.toString().toFloatOrNull() ?: 88f)
            .putString("comment", etComment.text.toString().trim())
            .putBoolean("blind", cbBlind.isChecked)
            .putBoolean("debug", cbDebug.isChecked)
            .apply()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s?.contains(packageName) == true
    }
}
