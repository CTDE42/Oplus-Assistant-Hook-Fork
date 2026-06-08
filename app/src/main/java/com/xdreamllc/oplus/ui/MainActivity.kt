package com.xdreamllc.oplus.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.R
import io.github.libxposed.service.XposedService

class MainActivity : ComponentActivity() {

    private class SettingsStore(private val context: Context) {

        @Volatile
        private var remote: SharedPreferences? = null

        private val fallback: SharedPreferences =
            context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

        fun bind(service: XposedService?) {
            remote = try {
                service?.getRemotePreferences(Config.PREFS_NAME)
            } catch (_: Throwable) {
                null
            }
            if (remote != null) {
                migrateLocalToRemote()
            }
        }

        private fun current(): SharedPreferences = remote ?: fallback

        fun getInt(key: String, default: Int): Int = current().getInt(key, default)
        fun getBoolean(key: String, default: Boolean): Boolean = current().getBoolean(key, default)
        fun getString(key: String, default: String): String = current().getString(key, default) ?: default

        fun putInt(key: String, value: Int) { current().edit().putInt(key, value).apply() }
        fun putBoolean(key: String, value: Boolean) { current().edit().putBoolean(key, value).apply() }
        fun putString(key: String, value: String) { current().edit().putString(key, value).apply() }

        private fun migrateLocalToRemote() {
            val target = remote ?: return
            val all = fallback.all
            if (all.isEmpty()) return
            val editor = target.edit()
            for ((key, value) in all) {
                when (value) {
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> Unit
                }
            }
            editor.apply()
        }
    }

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = SettingsStore(applicationContext)
        settings.bind(App.service)
        setContent { LightTheme { MainScreen() } }
    }

    data class AssistantInfo(val name: String, val packageName: String, val icon: Bitmap? = null)
    data class AppOption(val name: String, val packageName: String, val icon: Bitmap? = null)

    private fun getCurrentAssistantInfo(): AssistantInfo? {
        return try {
            val assistantStr = Settings.Secure.getString(contentResolver, "assistant")
            if (assistantStr.isNullOrEmpty()) return null
            val component = ComponentName.unflattenFromString(assistantStr) ?: return null
            val appInfo = packageManager.getApplicationInfo(component.packageName, 0)
            AssistantInfo(
                name = appInfo.loadLabel(packageManager).toString(),
                packageName = component.packageName,
                icon = drawableToBitmap(appInfo.loadIcon(packageManager))
            )
        } catch (_: Exception) { null }
    }

    private fun getAppName(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.loadLabel(packageManager).toString()
        } catch (_: Exception) { null }
    }

    private fun getAppIcon(packageName: String): Bitmap? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            drawableToBitmap(appInfo.loadIcon(packageManager))
        } catch (_: Exception) { null }
    }

    private fun queryVoiceInteractionApps(): List<AppOption> {
        val intent = Intent("android.service.voice.VoiceInteractionService")
        val services = packageManager.queryIntentServices(intent, 0)
        val seen = mutableSetOf<String>()
        return services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!seen.add(si.packageName)) return@mapNotNull null
            try {
                val ai = packageManager.getApplicationInfo(si.packageName, 0)
                AppOption(ai.loadLabel(packageManager).toString(), si.packageName,
                    drawableToBitmap(ai.loadIcon(packageManager)))
            } catch (_: Exception) { null }
        }.sortedBy { it.name }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable) return d.bitmap
        val w = maxOf(d.intrinsicWidth, 1)
        val h = maxOf(d.intrinsicHeight, 1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    private fun openDefaultAssistantSettings() {
        try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        catch (_: Throwable) { try { startActivity(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")) } catch (_: Throwable) {} }
    }

    private fun setGoogleAsDefaultAssistantOrOpenSettings(): Boolean {
        val c = findGoogleVoiceInteractionService() ?: run { openDefaultAssistantSettings(); return false }
        val v = c.flattenToString()
        return try {
            Settings.Secure.putString(contentResolver, "assistant", v)
            Settings.Secure.putString(contentResolver, "voice_interaction_service", v)
            true
        } catch (_: SecurityException) { openDefaultAssistantSettings(); false }
        catch (_: Throwable) { openDefaultAssistantSettings(); false }
    }

    private fun findGoogleVoiceInteractionService(): ComponentName? {
        val i = Intent("android.service.voice.VoiceInteractionService").setPackage(Config.PKG_GOOGLE)
        val s = packageManager.queryIntentServices(i, 0)
        val sv = s.firstOrNull { it.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION }?.serviceInfo
            ?: s.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(sv.packageName, sv.name)
    }

    @Composable
    fun MainScreen() {
        val ctx = LocalContext.current
        var service by remember { mutableStateOf(App.service) }
        DisposableEffect(Unit) {
            val l = object : App.ServiceStateListener {
                override fun onServiceStateChanged(s: XposedService?) { service = s; settings.bind(s) }
            }
            App.addServiceStateListener(l, true)
            onDispose { App.removeServiceStateListener(l) }
        }

        var powerMode by remember(service) { mutableIntStateOf(settings.getInt(Config.KEY_POWER_MODE, Config.DEFAULT_POWER_MODE)) }
        var gestureBarEnabled by remember(service) { mutableStateOf(settings.getBoolean(Config.KEY_GESTURE_BAR_ENABLED, Config.DEFAULT_GESTURE_BAR_ENABLED)) }
        var customPackage by remember(service) { mutableStateOf(settings.getString(Config.KEY_CUSTOM_PACKAGE, Config.DEFAULT_CUSTOM_PACKAGE)) }
        var assistantInfo by remember { mutableStateOf(getCurrentAssistantInfo()) }

        DisposableEffect(ctx) {
            val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) assistantInfo = getCurrentAssistantInfo() }
            (ctx as? LifecycleOwner)?.lifecycle?.addObserver(o)
            onDispose { (ctx as? LifecycleOwner)?.lifecycle?.removeObserver(o) }
        }

        var showAppPicker by remember { mutableStateOf(false) }

        Scaffold(containerColor = Color.White) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                Text(stringResource(R.string.app_subtitle), fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(20.dp))
                ModuleStatusCard(service)
                Spacer(Modifier.height(16.dp))
                DefaultAssistantCard(assistantInfo) { assistantInfo = getCurrentAssistantInfo() }
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.section_power_title), stringResource(R.string.section_power_subtitle))
                Spacer(Modifier.height(10.dp))
                RadioOptionCard(stringResource(R.string.option_gemini_title), stringResource(R.string.option_gemini_subtitle), R.drawable.gemini, powerMode == Config.POWER_MODE_GEMINI, Color(0xFF4285F4)) {
                    powerMode = Config.POWER_MODE_GEMINI; settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_GEMINI)
                }
                Spacer(Modifier.height(8.dp))
                RadioOptionCard(stringResource(R.string.option_circle_title), stringResource(R.string.option_circle_subtitle), R.drawable.google, powerMode == Config.POWER_MODE_CIRCLE, Color(0xFF34A853)) {
                    powerMode = Config.POWER_MODE_CIRCLE; settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_CIRCLE)
                }
                Spacer(Modifier.height(8.dp))
                // === Custom Assistant ===
                RadioOptionCard(stringResource(R.string.option_custom_title), stringResource(R.string.option_custom_subtitle), R.drawable.ic_custom_assistant, powerMode == Config.POWER_MODE_CUSTOM, Color(0xFF8E24AA)) {
                    powerMode = Config.POWER_MODE_CUSTOM; settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_CUSTOM)
                }
                AnimatedVisibility(visible = powerMode == Config.POWER_MODE_CUSTOM) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        CustomAssistantSection(customPackage, { showAppPicker = true }) { pkg ->
                            customPackage = pkg; settings.putString(Config.KEY_CUSTOM_PACKAGE, pkg)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RadioOptionCard(stringResource(R.string.option_none_title), stringResource(R.string.option_none_subtitle), null, powerMode == Config.POWER_MODE_NONE, Color(0xFF999999)) {
                    powerMode = Config.POWER_MODE_NONE; settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_NONE)
                }
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.section_gesture_title), stringResource(R.string.section_gesture_subtitle))
                Spacer(Modifier.height(10.dp))
                ToggleCard(stringResource(R.string.toggle_gesture_title), stringResource(R.string.toggle_gesture_subtitle), gestureBarEnabled) {
                    gestureBarEnabled = it; settings.putBoolean(Config.KEY_GESTURE_BAR_ENABLED, it)
                }
                Spacer(Modifier.height(10.dp))
                InfoCard(stringResource(R.string.gesture_requirement))
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = Color(0xFFEEEEEE))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.footer_effective_hint), fontSize = 12.sp, color = Color(0xFFFF9500), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showAppPicker) {
            AppPickerDialog(remember { queryVoiceInteractionApps() }, customPackage, { pkg ->
                customPackage = pkg; settings.putString(Config.KEY_CUSTOM_PACKAGE, pkg); showAppPicker = false
            }, { showAppPicker = false })
        }
    }

    @Composable
    fun CustomAssistantSection(customPackage: String, onSelectApp: () -> Unit, onPackageChanged: (String) -> Unit) {
        val appName = remember(customPackage) { if (customPackage.isNotBlank()) getAppName(customPackage) else null }
        val appIcon = remember(customPackage) { if (customPackage.isNotBlank()) getAppIcon(customPackage) else null }

        Card(Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F0FF)), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                if (customPackage.isNotBlank() && appName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSelectApp() }.padding(vertical = 8.dp)) {
                        if (appIcon != null) Image(bitmap = appIcon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text("?", fontSize = 18.sp, color = Color.Gray) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(appName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A))
                            Text(customPackage, fontSize = 12.sp, color = Color(0xFF888888))
                        }
                        TextButton(onClick = { onPackageChanged("") }) { Text("清除", fontSize = 13.sp, color = Color(0xFFE53935)) }
                    }
                } else {
                    Text("未选择应用", fontSize = 14.sp, color = Color(0xFF999999), modifier = Modifier.padding(vertical = 8.dp))
                }
                TextButton(onClick = onSelectApp, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF8E24AA))
                    Spacer(Modifier.width(8.dp))
                    Text("从已安装的助理应用中选取", fontSize = 14.sp, color = Color(0xFF8E24AA))
                }
                OutlinedTextField(value = customPackage, onValueChange = onPackageChanged, label = { Text("或手动输入包名") }, placeholder = { Text("com.example.assistant") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            }
        }
    }

    @Composable
    fun AppPickerDialog(apps: List<AppOption>, currentPkg: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Card(Modifier.fillMaxWidth().height(500.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("选择助理应用", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("以下应用已注册 VoiceInteractionService:", fontSize = 13.sp, color = Color(0xFF888888))
                    Spacer(Modifier.height(12.dp))
                    if (apps.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("未找到支持 VoiceInteractionService 的应用", color = Color(0xFF999999))
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(apps) { app ->
                                val bg = if (app.packageName == currentPkg) Color(0xFFF3E5F5) else Color.Transparent
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSelect(app.packageName) }.padding(12.dp)) {
                                    if (app.icon != null) Image(bitmap = app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                                    else Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(app.name.take(1), fontSize = 16.sp, color = Color.Gray) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A))
                                        Text(app.packageName, fontSize = 12.sp, color = Color(0xFF888888))
                                    }
                                    if (app.packageName == currentPkg) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF8E24AA), modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ModuleStatusCard(service: XposedService?) {
        val active = service != null
        val fn = remember(service) { try { service?.frameworkName } catch (_: Throwable) { null } }
        val av = remember(service) { try { service?.apiVersion ?: 0 } catch (_: Throwable) { 0 } }
        val sc = if (active) Color(0xFF34A853) else Color(0xFFE53935)
        val bg = if (active) Color(0xFFEFFAEF) else Color(0xFFFDECEA)
        val t = if (active) stringResource(R.string.module_status_active_title) else stringResource(R.string.module_status_inactive_title)
        val d = if (active) { val n = fn ?: "libxposed"; if (av > 0) stringResource(R.string.module_status_active_desc_with_api, n, av) else stringResource(R.string.module_status_active_desc, n) }
        else stringResource(R.string.module_status_inactive_desc)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, sc.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(sc))
                Spacer(Modifier.width(12.dp))
                Column { Text(t, 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A)); Text(d, 12.sp, color = Color(0xFF666666)) }
            }
        }
    }

    @Composable
    fun DefaultAssistantCard(assistantInfo: AssistantInfo?, onRefresh: () -> Unit) {
        val isGoogle = assistantInfo?.packageName == Config.PKG_GOOGLE
        val bg = if (isGoogle) Color(0xFFF0FAF0) else Color(0xFFFFF8E1)
        val bc = if (isGoogle) Color(0xFF34A853).copy(alpha = 0.3f) else Color(0xFFFFA000).copy(alpha = 0.3f)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, bc, RoundedCornerShape(14.dp)).clickable {
            if (isGoogle) openDefaultAssistantSettings()
            else if (setGoogleAsDefaultAssistantOrOpenSettings()) onRefresh()
        }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (assistantInfo?.icon != null) Image(bitmap = assistantInfo.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                else Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.default_assistant_label), fontSize = 12.sp, color = Color(0xFF888888))
                    Text(assistantInfo?.name ?: stringResource(R.string.default_assistant_unset), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A))
                    if (!isGoogle) Text(stringResource(R.string.default_assistant_warning), fontSize = 11.sp, color = Color(0xFFFFA000))
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.open_settings), tint = Color(0xFFBBBBBB), modifier = Modifier.size(20.dp))
            }
        }
    }

    @Composable
    fun SectionHeader(title: String, subtitle: String) {
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 2.dp))
        }
    }

    @Composable
    fun RadioOptionCard(title: String, subtitle: String, iconResId: Int?, selected: Boolean, accentColor: Color, onClick: () -> Unit) {
        val bc by animateColorAsState(if (selected) accentColor else Color(0xFFE0E0E0), tween(250), label = "b")
        val bg by animateColorAsState(if (selected) accentColor.copy(alpha = 0.06f) else Color(0xFFFAFAFA), tween(250), label = "bg")
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.5.dp, bc, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconResId != null) Image(painterResource(iconResId), contentDescription = title, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                else Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) { Text("X", fontSize = 14.sp, color = Color(0xFF999999), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A)); Text(subtitle, fontSize = 12.sp, color = Color(0xFF888888)) }
                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
                else Box(Modifier.size(20.dp).border(2.dp, Color(0xFFCCCCCC), CircleShape))
            }
        }
    }

    @Composable
    fun ToggleCard(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        val bc by animateColorAsState(if (checked) Color(0xFF34A853) else Color(0xFFE0E0E0), tween(250), label = "tb")
        val bg by animateColorAsState(if (checked) Color(0xFFF0FAF0) else Color(0xFFFAFAFA), tween(250), label = "tbg")
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.5.dp, bc, RoundedCornerShape(12.dp)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(painterResource(R.drawable.google), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A)); Text(subtitle, fontSize = 12.sp, color = Color(0xFF888888)) }
                Switch(checked, onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34A853), uncheckedThumbColor = Color(0xFFBBBBBB), uncheckedTrackColor = Color(0xFFE0E0E0)))
            }
        }
    }

    @Composable
    fun InfoCard(text: String) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF5F5F5)).padding(14.dp)) {
            Row { Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text, fontSize = 12.sp, color = Color(0xFF666666), lineHeight = 18.sp) }
        }
    }
}

@Composable
fun LightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4285F4), onPrimary = Color.White, surface = Color.White, onSurface = Color(0xFF1A1A1A), background = Color.White, onBackground = Color(0xFF1A1A1A)), content = content)
}
