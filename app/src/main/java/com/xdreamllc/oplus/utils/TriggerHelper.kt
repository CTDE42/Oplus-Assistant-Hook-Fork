package com.xdreamllc.oplus.utils

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.xdreamllc.oplus.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object TriggerHelper {

    private const val WARMUP_TIMEOUT_MS = 600L
    private const val WARMUP_TIMEOUT_AGGRESSIVE_MS = 1000L
    private const val POST_CONNECT_SETTLE_MS = 120L
    private const val POST_CONNECT_SETTLE_AGGRESSIVE_MS = 250L
    private const val SHOW_SESSION_RETRY_DELAY_MS = 80L
    private const val FORCE_STOP_SETTLE_MS = 100L
    private const val VERDICT_DELAY_MS = 250L

    fun performHapticFeedback(context: Context) {
        try {
            val vibrator = context.getSystemService("vibrator") as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                XLog.error("Haptic: vibrator is null or has no vibrator")
            }
        } catch (e: Throwable) {
            XLog.error("Haptic feedback failed: ${e.message}")
        }
    }

    fun triggerGemini(context: Context) {
        val token = Binder.clearCallingIdentity()
        try {
            warmUpGoogleApp(context, aggressive = false)
            if (!tryShowSessionViaVims(attempt = 1)) {
                XLog.debug("First showSession reported failure; force-stopping GSA and retrying with aggressive warmup")
                forceStopGoogleApp(context)
                try { Thread.sleep(SHOW_SESSION_RETRY_DELAY_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                warmUpGoogleApp(context, aggressive = true)
                if (!tryShowSessionViaVims(attempt = 2)) {
                    val voiceCommand = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(Config.PKG_GOOGLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (!tryStart(context, voiceCommand, "VOICE_COMMAND(framework-resolved)")) {
                        XLog.error("All Gemini paths failed; falling back to shell")
                        triggerGeminiFallbackByShell()
                    }
                    return
                }
            }
            scheduleAssistantVerdictCheck(context)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    // ========== Custom assistant ==========

    fun triggerCustomAssistant(context: Context, packageName: String) {
        val token = Binder.clearCallingIdentity()
        try {
            // Skip VIMS.showSessionForActiveService — it only activates the system default assistant.
            // Use direct Intent chain instead: ACTION_VOICE_COMMAND → ACTION_ASSIST → Launcher

            val voiceCommand = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (tryStart(context, voiceCommand, "ACTION_VOICE_COMMAND")) {
                XLog.debug("Custom assistant triggered via ACTION_VOICE_COMMAND for $packageName")
                return
            }

            // Pre-warm: bind the VIS to ensure the process is alive before we try ACTION_ASSIST
            val component = findVoiceInteractionService(context, packageName)
            if (component != null) {
                warmUpApp(context, component)
                try { Thread.sleep(POST_CONNECT_SETTLE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }

            val assist = Intent(Intent.ACTION_ASSIST).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (tryStart(context, assist, "ACTION_ASSIST")) {
                XLog.debug("Custom assistant triggered via ACTION_ASSIST for $packageName")
                return
            }

            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    XLog.debug("Custom assistant launched via main activity for $packageName")
                    return
                }
            } catch (e: Throwable) {
                XLog.error("Launch intent failed for $packageName: ${e.message}")
            }

            XLog.error("All custom assistant paths failed for $packageName")
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun findVoiceInteractionService(context: Context, packageName: String): ComponentName? {
        return try {
            val intent = Intent("android.service.voice.VoiceInteractionService").apply {
                setPackage(packageName)
            }
            val services = context.packageManager.queryIntentServices(intent, 0)
            val service = services.firstOrNull { info ->
                info.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION
            }?.serviceInfo ?: services.firstOrNull()?.serviceInfo ?: return null
            ComponentName(service.packageName, service.name)
        } catch (e: Throwable) {
            XLog.error("findVoiceInteractionService failed for $packageName: ${e.message}")
            null
        }
    }

    private fun warmUpApp(context: Context, component: ComponentName) {
        val intent = Intent("android.service.voice.VoiceInteractionService").apply {
            this.component = component
        }
        val latch = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                XLog.debug("warmUpApp: connected to ${name.shortClassName}")
                connected.set(true)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onBindingDied(name: ComponentName) { latch.countDown() }
            override fun onNullBinding(name: ComponentName) { connected.set(true); latch.countDown() }
        }
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val bound = try {
            context.bindService(intent, connection, flags)
        } catch (e: Throwable) {
            XLog.error("warmUpApp: bindService failed: ${e.message}")
            false
        }
        if (!bound) {
            try { context.unbindService(connection) } catch (_: Throwable) {}
            return
        }
        try { latch.await(WARMUP_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { try { context.unbindService(connection) } catch (_: Throwable) {} }
    }

    // ========== Gemini-specific helpers ==========

    private fun scheduleAssistantVerdictCheck(context: Context) {
        Thread {
            try { Thread.sleep(VERDICT_DELAY_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
            if (didAssistantWindowAppear(context)) {
                XLog.debug("Verdict: GSA reached foreground after showSession; assistant rendered")
                return@Thread
            }
            XLog.error("Verdict: rejection inferred after ${VERDICT_DELAY_MS}ms; force-stopping ${Config.PKG_GOOGLE} and re-dispatching")
            val recoveryToken = Binder.clearCallingIdentity()
            try {
                forceStopGoogleApp(context)
                warmUpGoogleApp(context, aggressive = true)
                if (tryShowSessionViaVims(attempt = 3)) XLog.debug("Verdict-recovery showSession dispatched")
                else XLog.error("Verdict-recovery showSession also failed; giving up silently")
            } finally { Binder.restoreCallingIdentity(recoveryToken) }
        }.apply { name = "OplusHook-AssistantVerdict"; isDaemon = true; start() }
    }

    private fun didAssistantWindowAppear(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val processes = am.runningAppProcesses ?: return false
            val gsaProcesses = processes.filter { info -> info.pkgList?.any { it == Config.PKG_GOOGLE } == true }
            if (gsaProcesses.isEmpty()) { XLog.error("Verdict: ${Config.PKG_GOOGLE} has no running process"); return false }
            val best = gsaProcesses.minBy { it.importance }
            val ok = best.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            if (!ok) { val summary = gsaProcesses.joinToString { "${it.processName}=${it.importance}" }; XLog.error("Verdict: ok=false (best=${best.processName} importance=${best.importance}); all GSA procs: $summary") }
            ok
        } catch (e: Throwable) { XLog.error("Verdict probe failed: ${e.message}"); false }
    }

    private fun forceStopGoogleApp(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: run { XLog.error("forceStopGoogleApp: ActivityManager service unavailable"); return }
            val method = ActivityManager::class.java.getDeclaredMethod("forceStopPackage", String::class.java)
            method.isAccessible = true
            method.invoke(am, Config.PKG_GOOGLE)
            XLog.debug("forceStopGoogleApp: kill request issued for ${Config.PKG_GOOGLE}")
            Thread.sleep(FORCE_STOP_SETTLE_MS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (e: Throwable) { XLog.error("forceStopGoogleApp failed: ${e.message}") }
    }

    private fun warmUpGoogleApp(context: Context, aggressive: Boolean) {
        val component = findGoogleVoiceInteractionService(context) ?: run { XLog.error("warmUpGoogleApp: no Google VoiceInteractionService component"); return }
        val intent = Intent("android.service.voice.VoiceInteractionService").apply { this.component = component }
        val latch = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { XLog.debug("warmUpGoogleApp: connected to ${name.shortClassName}"); connected.set(true); latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onBindingDied(name: ComponentName) { XLog.error("warmUpGoogleApp: binding died for ${name.shortClassName}"); latch.countDown() }
            override fun onNullBinding(name: ComponentName) { XLog.debug("warmUpGoogleApp: null binding for ${name.shortClassName} (process is alive though)"); connected.set(true); latch.countDown() }
        }
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val bound = try { context.bindService(intent, connection, flags) } catch (e: Throwable) { XLog.error("warmUpGoogleApp: bindService threw: ${e.message}"); false }
        if (!bound) { try { context.unbindService(connection) } catch (_: Throwable) {}; return }
        val timeoutMs = if (aggressive) WARMUP_TIMEOUT_AGGRESSIVE_MS else WARMUP_TIMEOUT_MS
        val settleMs = if (aggressive) POST_CONNECT_SETTLE_AGGRESSIVE_MS else POST_CONNECT_SETTLE_MS
        try {
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) XLog.debug("warmUpGoogleApp: timeout after ${timeoutMs}ms (proceeding anyway, aggressive=$aggressive)")
            else if (connected.get()) {
                val sleepDeadline = SystemClock.uptimeMillis() + settleMs
                while (true) { val remaining = sleepDeadline - SystemClock.uptimeMillis(); if (remaining <= 0) break; try { Thread.sleep(remaining) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break } }
            }
        } finally { try { context.unbindService(connection) } catch (_: Throwable) {} }
    }

    private fun findGoogleVoiceInteractionService(context: Context): ComponentName? {
        return try {
            val intent = Intent("android.service.voice.VoiceInteractionService").setPackage(Config.PKG_GOOGLE)
            val services = context.packageManager.queryIntentServices(intent, 0)
            val service = services.firstOrNull { info -> info.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION }?.serviceInfo ?: services.firstOrNull()?.serviceInfo ?: return null
            ComponentName(service.packageName, service.name)
        } catch (e: Throwable) { XLog.error("findGoogleVoiceInteractionService failed: ${e.message}"); null }
    }

    private fun tryShowSessionViaVims(attempt: Int): Boolean {
        return try {
            val binder = getService("voiceinteraction") ?: run { XLog.error("VIMS: voiceinteraction binder is null (attempt=$attempt)"); return false }
            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return false
            val bundle = newAssistantInvocationBundle()
            val ok = invokeVoiceInteractionService(service, bundle)
            if (ok) XLog.debug("Triggered Gemini via VIMS.showSession (attempt=$attempt)")
            else XLog.error("VIMS.showSession returned non-true on attempt=$attempt; will fall back")
            ok
        } catch (e: Throwable) { XLog.error("VIMS path failed (attempt=$attempt): ${e.message}"); false }
    }

    private fun tryStart(context: Context, intent: Intent, label: String): Boolean {
        return try { context.startActivity(intent); XLog.debug("Triggered Gemini via $label"); true }
        catch (e: android.content.ActivityNotFoundException) { XLog.error("$label: no matching activity"); false }
        catch (e: Throwable) { XLog.error("$label failed: ${e.message}"); false }
    }

    private fun invokeVoiceInteractionService(service: Any, bundle: Bundle): Boolean {
        val methods = service.javaClass.methods
        methods.firstOrNull { it.name == "showSessionForActiveService" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type -> when { type == Bundle::class.java -> bundle; type == Integer.TYPE -> SHOW_SOURCE_ASSIST_GESTURE; type == java.lang.Boolean.TYPE -> true; IBinder::class.java.isAssignableFrom(type) -> null; else -> null } }.toTypedArray()
            val result = method.invoke(service, *args)
            return interpretShowSessionResult(method.returnType, result)
        }
        methods.firstOrNull { it.name == "showSessionFromSession" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type -> when { IBinder::class.java.isAssignableFrom(type) -> null; type == Bundle::class.java -> bundle; type == Integer.TYPE -> SHOW_SOURCE_ASSIST_GESTURE; type == String::class.java -> null; else -> null } }.toTypedArray()
            val result = method.invoke(service, *args)
            return interpretShowSessionResult(method.returnType, result)
        }
        return false
    }

    private fun interpretShowSessionResult(returnType: Class<*>, result: Any?): Boolean {
        if (returnType == Void.TYPE) return true
        if (returnType == java.lang.Boolean.TYPE) return result == true
        return result != java.lang.Boolean.FALSE
    }

    private const val SHOW_SOURCE_ASSIST_GESTURE = 4
    private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6

    private fun newAssistantInvocationBundle(): Bundle {
        return Bundle().apply {
            putInt("invocation_type", INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
            putLong("invocation_time_ms", SystemClock.uptimeMillis())
            putInt("invocation_phone_state", 0)
            putLong(Intent.EXTRA_TIME, SystemClock.uptimeMillis())
            putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, KEYBOARD_DEVICE_ID_SYSTEM)
            putBoolean("xiaobu_trigger", true)
        }
    }

    private const val KEYBOARD_DEVICE_ID_SYSTEM = -1

    fun triggerGeminiFallbackByShell() {
        try { Runtime.getRuntime().exec(arrayOf("am", "start", "-a", Intent.ACTION_VOICE_COMMAND, "-p", Config.PKG_GOOGLE)) }
        catch (e: Throwable) { XLog.error("Gemini shell fallback failed: ${e.message}") }
    }

    fun triggerCircleToSearch() {
        try {
            val binder = getService("contextual_search") ?: run { XLog.error("ContextualSearchService binder is null"); return }
            val iface = Class.forName("android.app.contextualsearch.IContextualSearchManager")
            val stubClass = Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
            val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val startMethod = iface.getDeclaredMethod("startContextualSearch", Integer.TYPE)
            startMethod.invoke(service, 2)
            XLog.debug("Triggered Circle to Search")
        } catch (e: Throwable) { XLog.error("Failed to trigger Circle to Search", e) }
    }

    private fun getService(name: String): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        return serviceManager.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }
}

object ResourceHookState {
    @Volatile
    var isTempHookEnabled = false
}
