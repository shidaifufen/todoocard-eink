package com.shidaifufen.todocard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PlanReadiness(ctx: Context) {
    var exact by remember { mutableStateOf(SchedulerWorker.exactAllowed(ctx)) }
    var ready by remember { mutableStateOf(planPermissions().all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }) }
    var message by remember { mutableStateOf("") }
    val power = ctx.getSystemService(android.os.PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val lifecycle = (ctx as androidx.activity.ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exact = SchedulerWorker.exactAllowed(ctx)
                unrestricted = power.isIgnoringBatteryOptimizations(ctx.packageName)
                ready = planPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                SchedulerWorker.schedule(ctx)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        ready = grants.values.all { it }
        message = if (ready) "蓝牙和通知已就绪" else "计划需要蓝牙权限，建议同时允许通知以查看状态"
        SchedulerWorker.schedule(ctx)
    }
    OutlinedCard(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("计划权限")
            Text(if (exact && ready && unrestricted) "已就绪 ›" else "查看设置 ›", color = MaterialTheme.colorScheme.primary)
        }
    }
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false },
        title = { Text("计划权限") }, text = {
        Column {
            Text(if (exact) "精确定时已开启" else "需要开启“闹钟与提醒”以准点执行")
            Text("到点开始查找卡片，锁屏时也会等待广播，最多 120 秒。")
            if (!unrestricted) Text("系统仍在优化后台耗电。荣耀可在「应用启动管理」允许土豆片自启动和后台活动。")
            if (!exact) Text("未授权时只能使用系统宽松定时，可能延迟。短间隔在深度休眠下也受系统频率限制。")
            TextButton(onClick = { permission.launch(planPermissions()) }) {
                Text(if (ready) "检查蓝牙与通知权限" else "允许蓝牙与推送通知")
            }
            if (!exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${ctx.packageName}"))) }.onFailure { message = "请在系统设置中为土豆片开启闹钟与提醒" }
            }) { Text("开启准点执行") }
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("管理后台耗电与休眠限制") }
            if (message.isNotBlank()) Text(message)
        }
    }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } })
}

private fun planPermissions(): Array<String> {
    val permissions = if (Build.VERSION.SDK_INT >= 31) mutableListOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    return permissions.toTypedArray()
}
