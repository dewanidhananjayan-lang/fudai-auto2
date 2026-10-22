package com.fudai.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 自动参与直播间福袋的无障碍服务 v2
 *
 * v2 新增：
 *  1. 盲点模式：直播界面多为自绘控件，读不到文字时改用屏幕坐标点击
 *  2. 前台服务常驻通知：防止被系统杀后台
 *  3. 调试 Toast：每一步在屏幕上提示，方便定位问题
 *  4. 关键词扩充：参与 / 参与抽奖 / 发表评论 等
 */
class FudaiService : AccessibilityService() {

    companion object {
        const val ACTION_START = "com.fudai.auto.START"
        const val ACTION_STOP = "com.fudai.auto.STOP"

        private const val TICK_MS = 1200L
        private const val STEP_TIMEOUT = 12_000L
        private const val SWIPE_DELAY = 2_500L
        private const val CHANNEL_ID = "fudai_channel"

        private const val STATE_IDLE = 0
        private const val STATE_BAG_CLICKED = 1
        private const val STATE_JOIN_CLICKED = 2
        private const val STATE_SENT = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var state = STATE_IDLE
    private var stateTime = 0L
    private var commentIndex = 0
    private var failCount = 0
    private var blindJoinTried = false
    private var lastToast = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_START -> start()
                ACTION_STOP -> stop()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("running", false)) start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun start() {
        if (running) return
        running = true
        setState(STATE_IDLE)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        try {
            startForeground(1, buildNotification())
        } catch (_: Exception) {
        }
        toast("自动参与已启动")
        handler.postDelayed(tick, 500)
    }

    private fun stop() {
        running = false
        setState(STATE_IDLE)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        handler.removeCallbacks(tick)
        try { stopForeground(true) } catch (_: Exception) {}
        toast("已停止")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { step() } catch (_: Exception) {}
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val debug = p.getBoolean("debug", true)
        val blind = p.getBoolean("blind", true)
        val bagX = p.getFloat("bagX", 10f)
        val bagY = p.getFloat("bagY", 12f)
        val joinX = p.getFloat("joinX", 50f)
        val joinY = p.getFloat("joinY", 62f)
        val sendX = p.getFloat("sendX", 85f)
        val sendY = p.getFloat("sendY", 88f)

        val root = rootInActiveWindow
        val now = System.currentTimeMillis()

        when (state) {

            // ---------- 第一步：点击福袋 ----------
            STATE_IDLE -> {
                val bag = if (root != null) findBagNode(root) else null
                if (bag != null) {
                    clickNode(bag)
                    failCount = 0
                    blindJoinTried = false
                    if (debug) toast("① 已点击福袋")
                    setState(STATE_BAG_CLICKED)
                } else {
                    failCount++
                    if (blind && failCount >= 3) {
                        tapPct(bagX, bagY)
                        failCount = 0
                        blindJoinTried = false
                        if (debug) toast("① 按坐标点击福袋($bagX%,$bagY%)")
                        setState(STATE_BAG_CLICKED)
                    } else if (debug && failCount % 8 == 0) {
                        toast("未发现福袋，请确认坐标或开启盲点模式")
                    }
                }
            }

            // ---------- 第二步：点击参与 ----------
            STATE_BAG_CLICKED -> {
                if (now - stateTime > STEP_TIMEOUT) {
                    if (blind && !blindJoinTried) {
                        tapPct(joinX, joinY)
                        blindJoinTried = true
                        if (debug) toast("② 按坐标点击参与($joinX%,$joinY%)")
                        stateTime = now
                        return
                    }
                    if (debug) toast("未找到参与按钮，回到第一步")
                    blindJoinTried = false
                    setState(STATE_IDLE)
                    return
                }
                val join = if (root == null) null else
                    (findByText(root, "发表评论后参与")
                        ?: findExactClickable(root, "参与")
                        ?: findByText(root, "参与抽奖")
                        ?: findByText(root, "发表评论")
                        ?: findByText(root, "领取"))
                if (join != null) {
                    clickNode(join)
                    if (debug) toast("② 已点击参与")
                    setState(STATE_JOIN_CLICKED)
                }
            }

            // ---------- 第三步：填评论并发送 ----------
            STATE_JOIN_CLICKED -> {
                if (now - stateTime > STEP_TIMEOUT) {
                    if (blind) {
                        tapPct(sendX, sendY)
                        if (debug) toast("③ 按坐标点击发送($sendX%,$sendY%)")
                        setState(STATE_SENT)
                        return
                    }
                    setState(STATE_IDLE)
                    return
                }
                if (root != null) {
                    ensureCommentText(root)
                    val send = findSendButton(root)
                    if (send != null) {
                        clickNode(send)
                        commentIndex++
                        if (debug) toast("③ 已发送评论")
                        setState(STATE_SENT)
                    }
                }
            }

            // ---------- 第四步：上滑切换直播间 ----------
            STATE_SENT -> {
                if (now - stateTime > SWIPE_DELAY) {
                    swipeToNextLive()
                    blindJoinTried = false
                    if (debug) toast("④ 上滑，切换下一个直播间")
                    setState(STATE_IDLE)
                }
            }
        }
    }

    private fun setState(s: Int) {
        state = s
        stateTime = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------
    //                          控件查找
    // ------------------------------------------------------------------

    private fun findBagNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        var fallback: AccessibilityNodeInfo? = null
        for (keyword in arrayOf("福袋", "领取福袋", "开奖")) {
            for (n in root.findAccessibilityNodeInfosByText(keyword)) {
                if (!n.isVisibleToUser) continue
                val txt = (n.text ?: n.contentDescription)?.toString() ?: ""
                if (txt.contains("明细") || txt.contains("规则") || txt.contains("中奖")
                    || txt.contains("已参与") || txt.contains("待开奖")
                ) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.top < screenH * 0.5f) return n
                if (fallback == null) fallback = n
            }
        }
        return fallback
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            if (n.isVisibleToUser) return n
        }
        return null
    }

    private fun findExactClickable(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            if (n.isVisibleToUser && n.text?.toString() == text) return n
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText("发送")) {
            if (n.isVisibleToUser && n.text?.toString() == "发送") return n
        }
        return null
    }

    private fun ensureCommentText(root: AccessibilityNodeInfo): Boolean {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val custom = p.getString("comment", null)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 500) {
            guard++
            val n = queue.removeFirst() ?: continue
            if (n.className == android.widget.EditText::class.java.name) {
                val t = n.text?.toString()?.trim()
                if (t.isNullOrEmpty()) {
                    val msg = if (custom.isNullOrEmpty()) {
                        val arr = resources.getStringArray(R.array.default_comments)
                        arr[commentIndex % arr.size]
                    } else {
                        custom
                    }
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        msg
                    )
                    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    // ------------------------------------------------------------------
    //                       点击 / 滑动 / 提示
    // ------------------------------------------------------------------

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** 按屏幕百分比点击（盲点模式） */
    private fun tapPct(xPct: Float, yPct: Float) {
        val dm = resources.displayMetrics
        tap(dm.widthPixels * xPct / 100f, dm.heightPixels * yPct / 100f)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun swipeToNextLive() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2, h * 0.82f)
            lineTo(w / 2, h * 0.22f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun toast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToast < 700) return
        lastToast = now
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "自动福袋助手", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return b.setContentTitle("自动福袋助手运行中")
            .setContentText("正在自动参与直播间福袋，可在本 App 内停止")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build()
    }
}
