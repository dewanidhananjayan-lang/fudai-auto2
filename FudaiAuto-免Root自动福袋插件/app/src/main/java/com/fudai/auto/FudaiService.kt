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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 自动参与直播间福袋的无障碍服务 v3
 *
 * v3 关键修复：
 *  1. 【根本问题】服务配置补上 android:canPerformGestures="true"
 *     没有这一条，dispatchGesture() 会静默失败 —— 表现就是"一直弹坐标但屏幕上毫无反应"
 *  2. 点击手势时长 60ms → 130ms，并补 lineTo，兼容更多 ROM
 *  3. 每次 dispatchGesture 都检查返回值与结果回调，失败时明确提示原因
 *  4. 新增自检（手势能力 / 窗口读取 / 当前前台包名）与"测试点击"功能
 *  5. 盲点模式不再傻等 12 秒，1.5 秒找不到控件就立刻按坐标点
 */
class FudaiService : AccessibilityService() {

    companion object {
        const val ACTION_START = "com.fudai.auto.START"
        const val ACTION_STOP = "com.fudai.auto.STOP"
        const val ACTION_SELFTEST = "com.fudai.auto.SELFTEST"
        const val ACTION_TEST_TAP = "com.fudai.auto.TEST_TAP"

        private const val TICK_MS = 1200L
        private const val STEP_TIMEOUT = 9_000L
        private const val BLIND_DELAY = 1_500L
        private const val SWIPE_DELAY = 2_500L
        private const val TEST_TAP_DELAY = 3_000L

        private const val TAP_MS = 130L
        private const val SWIPE_MS = 450L

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
    private var blindSendTried = false
    private var lastToast = 0L

    /** 手势注入连续失败次数，用于判断"点击到底有没有生效" */
    private var gestureFail = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_START -> start()
                ACTION_STOP -> stop()
                ACTION_SELFTEST -> handler.post { selfTest() }
                ACTION_TEST_TAP -> {
                    toast("3 秒后按当前坐标点一次，请立刻切到抖音直播间", true)
                    handler.postDelayed({
                        val p = getSharedPreferences("cfg", MODE_PRIVATE)
                        tapPct(p.getFloat("bagX", 10f), p.getFloat("bagY", 12f))
                        toast("已测试点击福袋位置，看屏幕是否有反应", true)
                    }, TEST_TAP_DELAY)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_SELFTEST)
            addAction(ACTION_TEST_TAP)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun start() {
        if (running) return
        running = true
        setState(STATE_IDLE)
        gestureFail = 0
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        try {
            startForeground(1, buildNotification())
        } catch (_: Exception) {
        }
        toast("自动参与已启动", true)
        handler.postDelayed(tick, 500)
    }

    private fun stop() {
        running = false
        setState(STATE_IDLE)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        handler.removeCallbacks(tick)
        try { stopForeground(true) } catch (_: Exception) {}
        toast("已停止", true)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { step() } catch (_: Exception) {}
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------
    //                            主状态机
    // ------------------------------------------------------------------

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

            // ---------- 第一步：点击福袋挂件 ----------
            STATE_IDLE -> {
                val bag = if (root != null) findBagNode(root) else null
                if (bag != null) {
                    clickNode(bag)
                    failCount = 0
                    blindJoinTried = false
                    blindSendTried = false
                    if (debug) toast("① 已点击福袋")
                    setState(STATE_BAG_CLICKED)
                    return
                }
                failCount++
                if (blind && failCount >= 2) {
                    tapPct(bagX, bagY)
                    failCount = 0
                    blindJoinTried = false
                    blindSendTried = false
                    if (debug) toast("① 坐标点击福袋($bagX%,$bagY%)")
                    setState(STATE_BAG_CLICKED)
                } else if (debug && failCount % 6 == 0) {
                    toast("未识别到福袋，将按坐标点击，请确认坐标是否正确")
                }
            }

            // ---------- 第二步：点击「发表评论后参与」 ----------
            STATE_BAG_CLICKED -> {
                val join = if (root != null) findJoinNode(root) else null
                if (join != null) {
                    clickNode(join)
                    blindSendTried = false
                    if (debug) toast("② 已点击参与")
                    setState(STATE_JOIN_CLICKED)
                    return
                }
                val waited = now - stateTime
                if (blind && !blindJoinTried && waited > BLIND_DELAY) {
                    tapPct(joinX, joinY)
                    blindJoinTried = true
                    if (debug) toast("② 坐标点击参与($joinX%,$joinY%)")
                    return
                }
                if (waited > STEP_TIMEOUT) {
                    if (debug) toast("没找到参与按钮，回到第一步")
                    blindJoinTried = false
                    setState(STATE_IDLE)
                }
            }

            // ---------- 第三步：填评论并点击发送 ----------
            STATE_JOIN_CLICKED -> {
                if (root != null) {
                    ensureCommentText(root)
                    val send = findSendButton(root)
                    if (send != null) {
                        clickNode(send)
                        commentIndex++
                        if (debug) toast("③ 已发送评论")
                        setState(STATE_SENT)
                        return
                    }
                }
                val waited = now - stateTime
                if (blind && !blindSendTried && waited > BLIND_DELAY) {
                    tapPct(sendX, sendY)
                    blindSendTried = true
                    if (debug) toast("③ 坐标点击发送($sendX%,$sendY%)")
                    setState(STATE_SENT)
                    return
                }
                if (waited > STEP_TIMEOUT) {
                    if (debug) toast("没找到发送按钮，回到第一步")
                    blindSendTried = false
                    setState(STATE_IDLE)
                }
            }

            // ---------- 第四步：上滑切换直播间 ----------
            STATE_SENT -> {
                if (now - stateTime > SWIPE_DELAY) {
                    swipeToNextLive()
                    blindJoinTried = false
                    blindSendTried = false
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
            val list = try {
                root.findAccessibilityNodeInfosByText(keyword)
            } catch (_: Exception) {
                emptyList<AccessibilityNodeInfo>()
            }
            for (n in list) {
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

    private fun findJoinNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findByText(root, "发表评论后参与")
            ?: findByText(root, "参与抽奖")
            ?: findByText(root, "发表评论")
            ?: findExactClickable(root, "参与")
            ?: findByText(root, "领取")
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Exception) {
            emptyList<AccessibilityNodeInfo>()
        }
        for (n in list) {
            if (n.isVisibleToUser) return n
        }
        return null
    }

    private fun findExactClickable(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Exception) {
            emptyList<AccessibilityNodeInfo>()
        }
        for (n in list) {
            if (n.isVisibleToUser && n.text?.toString() == text) return n
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val list = try {
            root.findAccessibilityNodeInfosByText("发送")
        } catch (_: Exception) {
            emptyList<AccessibilityNodeInfo>()
        }
        for (n in list) {
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
            val n = queue.removeFirst()
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
    //                    手势注入（点击 / 滑动）
    // ------------------------------------------------------------------

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable) {
                try {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } catch (_: Exception) {
                }
            }
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
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatch(gesture, "点击")
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
            .build()
        dispatch(gesture, "上滑")
    }

    /**
     * 统一的手势发送入口：
     *  - 返回 false：服务没有手势权限（配置缺 canPerformGestures），必须重装 APK
     *  - 回调 onCancelled：手势被系统/ROM 拦截
     */
    private fun dispatch(gesture: GestureDescription, what: String): Boolean {
        var started = false
        try {
            started = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureFail = 0
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureFail++
                    if (gestureFail == 2 || gestureFail % 6 == 0) {
                        toast(
                            "⚠️ ${what}被系统拦截(第 $gestureFail 次)：\n" +
                                "请检查开发者选项是否开了「禁止模拟点击」，" +
                                "并在无障碍设置里重新开关一次本服务",
                            true
                        )
                    }
                }
            }, null)
        } catch (_: Exception) {
            started = false
        }
        if (!started) {
            gestureFail++
            if (gestureFail == 1 || gestureFail % 6 == 0) {
                toast("⚠️ 无法${what}：本服务未获得手势权限，请安装最新版 APK", true)
            }
        }
        return started
    }

    // ------------------------------------------------------------------
    //                            自检
    // ------------------------------------------------------------------

    private fun selfTest() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: "未知"
        val count = countNodes(root)
        toast(
            "① 窗口读取：" + (if (root != null) "正常 ✅" else "失败 ❌") + "\n" +
                "   当前前台应用：$pkg\n" +
                "   可读控件数：$count",
            true
        )

        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels - 4f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels - 4f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()

        var started = false
        try {
            started = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureFail = 0
                    toast("② 模拟点击：可用 ✅ 现在可以正常自动参与了", true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    toast(
                        "② 模拟点击：被系统拒绝 ❌\n" +
                            "请检查开发者选项里的「禁止模拟点击」，" +
                            "或到无障碍设置里关闭再重新开启本服务",
                        true
                    )
                }
            }, null)
        } catch (_: Exception) {
            started = false
        }
        if (!started) {
            toast(
                "② 模拟点击：不可用 ❌\n" +
                    "服务未声明手势权限（canPerformGestures），\n" +
                    "说明当前安装的是旧版 APK，请安装最新版",
                true
            )
        }
    }

    private fun countNodes(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        var guard = 0
        while (queue.isNotEmpty() && guard < 800) {
            guard++
            val n = queue.removeFirst()
            count++
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null }
                if (c != null && guard + count < 800) queue.add(c)
            }
        }
        return count
    }

    // ------------------------------------------------------------------
    //                          提示 / 通知
    // ------------------------------------------------------------------

    /** important=true 用于自检等重要提示，不受节流限制且显示更久 */
    private fun toast(msg: String, important: Boolean = false) {
        if (!important) {
            val now = System.currentTimeMillis()
            if (now - lastToast < 800) return
            lastToast = now
        }
        handler.post {
            try {
                Toast.makeText(
                    this,
                    msg,
                    if (important) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
            }
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
