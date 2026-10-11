/* Modified for AEmulator Sunset, 2026-10-03: VM export, motion and host resolution.
 * Modified for AEmulator Super Plus, 2026-10-11: fake SIM card section.
 * GPL-3.0; upstream attribution retained in NOTICE.md. */
package app.aemu.ui

import app.aemu.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aemu.core.Engine
import app.aemu.core.GuestImage
import app.aemu.core.GyroLock
import app.aemu.core.NetworkType
import app.aemu.core.VmSettings
import app.aemu.core.WifiEmu
import app.aemu.core.VmStorageLease
import app.aemu.core.VmDataReset
import app.aemu.core.ImageStore
import app.aemu.core.ResolutionPreset
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RESOLUTIONS = ResolutionPreset.entries

private val RAM_STEPS = listOf(0, 256, 512, 768, 1024, 1536, 2048, 3072, 4096)

@Composable
fun SettingsSheet(img: GuestImage, onDismiss: () -> Unit, onSave: (VmSettings) -> Unit) {
    var s by remember { mutableStateOf(img.settings) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var resetConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!resetting) onDismiss() }, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Text(img.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_screen), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s.legacyEngine && img.api < 14) {
                Text(stringResource(R.string.vs_fixed_2x), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
                    RESOLUTIONS.forEachIndexed { i, r ->
                        ToggleButton(
                            checked = r.selected(s),
                            onCheckedChange = { s = r.apply(s) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                RESOLUTIONS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) { Text(if (r == ResolutionPreset.HOST) stringResource(R.string.vs_resolution_host)
                            else "${r.width}×${r.height}", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumField(stringResource(R.string.vs_width), s.width, Modifier.weight(1f)) { s = s.copy(width = it.coerceIn(240, 2160) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_height), s.height, Modifier.weight(1f)) { s = s.copy(height = it.coerceIn(320, 3840) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_dpi), s.density, Modifier.weight(1f)) { s = s.copy(density = it.coerceIn(96, 640), hostResolution = false) }
                }
                Text(stringResource(R.string.vs_screen_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (s.hostResolution) Text(stringResource(R.string.vs_host_resolution_sub),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_perf), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), s.gpu) { s = s.copy(gpu = it) }
            if (img.api >= 14) Toggle(stringResource(R.string.vs_hwui), stringResource(R.string.vs_hwui_sub), s.hwui) { s = s.copy(hwui = it) }
            Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), s.jit) { s = s.copy(jit = it) }
            if (img.api >= 21) Toggle(stringResource(R.string.vs_fulldex), stringResource(R.string.vs_fulldex_sub), s.fullDexopt) { s = s.copy(fullDexopt = it) }
            if (img.api < 14) Toggle(stringResource(R.string.vs_legacy), stringResource(R.string.vs_legacy_sub), s.legacyEngine) { s = s.copy(legacyEngine = it) }
            val ramIdx = RAM_STEPS.indexOf(s.ramMb).coerceAtLeast(0)
            ListItem(
                headlineContent = { Text(stringResource(R.string.vs_ram)) },
                supportingContent = {
                    Column {
                        Text(if (s.ramMb == 0) stringResource(R.string.vs_ram_auto) else "${s.ramMb} MB")
                        Slider(value = ramIdx.toFloat(), onValueChange = { s = s.copy(ramMb = RAM_STEPS[it.toInt()]) },
                            valueRange = 0f..RAM_STEPS.lastIndex.toFloat(), steps = RAM_STEPS.size - 2)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Toggle(stringResource(R.string.vs_lowram), stringResource(R.string.vs_lowram_sub), s.lowRam) { s = s.copy(lowRam = it) }
            Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), s.netProxy) { s = s.copy(netProxy = it) }
            Toggle(stringResource(R.string.vs_vibration), stringResource(R.string.vs_vibration_sub), s.vibration) { s = s.copy(vibration = it) }
            Toggle(stringResource(R.string.vs_host_battery), stringResource(R.string.vs_host_battery_sub), s.hostBattery) { s = s.copy(hostBattery = it) }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_motion), stringResource(R.string.vs_motion_sub), s.motionSensors) { s = s.copy(motionSensors = it) }
            if (img.api in 9..25 && s.motionSensors) GyroLockPicker(s.gyroLock) { s = s.copy(gyroLock = it) }
            if (img.api in 16..25) Toggle(stringResource(R.string.vs_skip_setup), stringResource(R.string.vs_skip_setup_sub), s.skipSetupWizard) { s = s.copy(skipSetupWizard = it) }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_disable_google), stringResource(R.string.vs_disable_google_sub), s.disableGoogleApps) { s = s.copy(disableGoogleApps = it) }
            if (img.api in 9..25) {
                Toggle(stringResource(R.string.vs_bluetooth), stringResource(R.string.vs_bluetooth_sub), s.bluetooth) { s = s.copy(bluetooth = it) }
            }
            if (WifiEmu.supported(img.engine, img.api, false, false)) {
                Toggle(stringResource(R.string.vs_wifi), stringResource(R.string.vs_wifi_sub), s.wifi) { s = s.copy(wifi = it) }
                if (s.wifi) WifiFields(s) { s = it }
            }
            if (img.api in 14..25) {
                Toggle(stringResource(R.string.vs_camera), stringResource(R.string.vs_camera_sub), s.camera) { s = s.copy(camera = it) }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_radio_title), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_radio), stringResource(R.string.vs_radio_sub), s.radio) { s = s.copy(radio = it) }
            if (s.radio) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = s.imei, onValueChange = { v -> s = s.copy(imei = v.filter(Char::isDigit).take(15)) },
                    label = { Text("IMEI") }, placeholder = { Text(VmSettings.DEFAULT_IMEI) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                TextButton(onClick = { s = s.copy(imei = VmSettings.randomImei()) }) { Text(stringResource(R.string.vs_random)) }
            }
            if (s.radio) NetworkTypePicker(s.networkType, img.api) { s = s.copy(networkType = it) }
            if (s.radio) SimSection(img, s) { s = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_device_id), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.serial, onValueChange = { v -> s = s.copy(serial = VmSettings.cleanSerial(v)) },
                    label = { Text(stringResource(R.string.vs_serial)) }, placeholder = { Text(VmSettings.DEFAULT_SERIAL) },
                    supportingText = { Text(stringResource(R.string.vs_serial_sub)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                TextButton(onClick = { s = s.copy(serial = VmSettings.randomSerial()) }) { Text(stringResource(R.string.vs_random)) }
            }
            OutlinedTextField(value = s.kernel, onValueChange = { v -> s = s.copy(kernel = VmSettings.cleanKernel(v)) },
                label = { Text(stringResource(R.string.vs_kernel)) }, placeholder = { Text(VmSettings.DEFAULT_KERNEL) },
                supportingText = { Text(stringResource(R.string.vs_kernel_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedTextField(value = s.baseband, onValueChange = { v -> s = s.copy(baseband = VmSettings.cleanBaseband(v)) },
                label = { Text(stringResource(R.string.vs_baseband)) }, placeholder = { Text(VmSettings.DEFAULT_BASEBAND) },
                supportingText = { Text(stringResource(R.string.vs_baseband_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_advanced), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = s.qemuArgs, onValueChange = { s = s.copy(qemuArgs = it) },
                label = { Text(stringResource(R.string.vs_qemu_args)) }, supportingText = { Text(stringResource(R.string.vs_qemu_args_sub)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(value = s.straceServices, onValueChange = { s = s.copy(straceServices = it) },
                label = { Text(stringResource(R.string.vs_strace)) }, placeholder = { Text("mediaserver,zygote") },
                supportingText = { Text(stringResource(R.string.vs_strace_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            HardwarePicker(s.hardware) { s = s.copy(hardware = it) }

            Spacer(Modifier.height(8.dp))
            RecoverySection(img)
            OemImportSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            OtaSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_controls), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), s.showNavBar) { s = s.copy(showNavBar = it) }
            Toggle(stringResource(R.string.vs_hide_menu), stringResource(R.string.vs_hide_menu_sub), s.hideMenuButton) { s = s.copy(hideMenuButton = it) }
            NavigationSettings(s) { s = it }
            Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), s.keepScreenOn) { s = s.copy(keepScreenOn = it) }
            Toggle(stringResource(R.string.vs_single), stringResource(R.string.vs_single_sub), s.mtMode == 4) { s = s.copy(mtMode = if (it) 4 else 0) }

            Spacer(Modifier.height(16.dp))
            VmExportSection(img, s, resetting) { resetting = it }
            VmImportSection(img, resetting, { resetting = it }) { s = it }
            TextButton(enabled = !resetting, onClick = { resetConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vm_reset_data), color = MaterialTheme.colorScheme.error)
            }
            resetMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val wifiOk = !s.wifi || (WifiEmu.cleanSsid(s.wifiSsid).isNotEmpty() && WifiEmu.passwordValid(s.wifiPassword))
            Button(enabled = !resetting && wifiOk, onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (resetConfirm) AlertDialog(
        onDismissRequest = { resetConfirm = false },
        title = { Text(stringResource(R.string.vm_reset_data)) },
        text = { Text(stringResource(R.string.vm_reset_warning)) },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            resetConfirm = false
            resetting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VmStorageLease(ctx.filesDir).use { lease ->
                            lease.acquire()
                            app.aemu.core.GuestStorageWriters.requireIdle(ctx)
                            VmDataReset.reset(ImageStore.imagesDir(ctx), img.id) { file ->
                                val stat = android.system.Os.lstat(file.path)
                                String.format(java.util.Locale.ROOT, "%016x %016x", stat.st_dev, stat.st_ino)
                            }
                        }
                    }
                }
                resetting = false
                resetMessage = if (result.isSuccess) ctx.getString(R.string.vm_reset_done)
                    else ctx.getString(R.string.vm_reset_failed)
            }
        }) { Text(stringResource(R.string.vm_reset_data)) } },
    )
}

@Composable
internal fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = on, onCheckedChange = set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Name and optional password of the emulated Wi-Fi network (blank password = open network). */
@Composable
private fun WifiFields(s: VmSettings, set: (VmSettings) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value = s.wifiSsid, onValueChange = { set(s.copy(wifiSsid = WifiEmu.cleanSsid(it))) },
        label = { Text(stringResource(R.string.vs_wifi_ssid)) }, singleLine = true,
        isError = s.wifiSsid.isEmpty(),
        supportingText = { Text(stringResource(if (s.wifiSsid.isEmpty()) R.string.vs_wifi_ssid_error else R.string.vs_wifi_ssid_sub)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = s.wifiPassword, onValueChange = { set(s.copy(wifiPassword = WifiEmu.cleanPassword(it))) },
        label = { Text(stringResource(R.string.vs_wifi_password)) }, singleLine = true,
        isError = !WifiEmu.passwordValid(s.wifiPassword),
        supportingText = { Text(stringResource(if (WifiEmu.passwordValid(s.wifiPassword)) R.string.vs_wifi_password_sub else R.string.vs_wifi_password_error)) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.vs_wifi_hide else R.string.vs_wifi_show)) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

/** Disabled / 0° / 180° / no-gyroscope list for the host motion sensors. */
@Composable
private fun GyroLockPicker(mode: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = listOf(R.string.vs_gyro_disabled, R.string.vs_gyro_0, R.string.vs_gyro_180, R.string.vs_gyro_none)
        .map { stringResource(it) }
    val current = GyroLock.sanitize(mode)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_gyro_lock)) },
        supportingContent = { Text(stringResource(R.string.vs_gyro_lock_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(labels[current]) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    labels.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; set(i) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Radio technology the fake modem reports (2G ... LTE); only the values the firmware's API level knows are offered. */
@Composable
private fun NetworkTypePicker(tech: Int, api: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_network)) },
        supportingContent = { Text(stringResource(R.string.vs_network_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(NetworkType.label(NetworkType.sanitize(tech, api))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    NetworkType.available(api).forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; set(e.tech) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** ro.hardware given to the guest: free text plus a menu of common values; blank = the firmware's own. */
@Composable
private fun HardwarePicker(value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value = value, onValueChange = { set(VmSettings.cleanHardware(it)) },
            label = { Text(stringResource(R.string.vs_hardware)) }, placeholder = { Text(stringResource(R.string.vs_hardware_default)) },
            supportingText = { Text(stringResource(R.string.vs_hardware_sub)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.vs_hardware_pick)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.vs_hardware_default)) }, onClick = { open = false; set("") })
                VmSettings.HARDWARE_PRESETS.forEach { hw ->
                    DropdownMenuItem(text = { Text(hw) }, onClick = { open = false; set(hw) })
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: Int, modifier: Modifier, set: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(set) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** Recovery of this firmware: stock one from the import, or a custom recovery.img (TWRP, OrangeFox, CWM…). */
@Composable
private fun RecoverySection(img: GuestImage) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val paths = remember(img.id) { app.aemu.core.VmPaths(ctx, img.id) }
    var status by remember { mutableStateOf(recoveryStatus(paths)) }
    var busy by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.vs_recovery_failed)
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        Thread {
            val ok = runCatching {
                val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                app.aemu.core.RecoveryImage.install(paths, data) {}
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                status = recoveryStatus(paths)
                if (!ok) android.widget.Toast.makeText(ctx, failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    Text(stringResource(R.string.vs_recovery), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(status ?: stringResource(R.string.vs_recovery_none)) },
        supportingContent = { Text(stringResource(R.string.vs_recovery_sub)) },
        trailingContent = {
            TextButton(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.vs_recovery_install)) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}


/** Delta (incremental) OTA zips applied on top of this VM's system image. */
@Composable
private fun OtaSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<app.aemu.core.DeltaOta.Prepared?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { prepared?.close() } }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri == null) return@rememberLauncherForActivityResult
        setBusy(true)
        message = ctx.getString(R.string.vs_ota_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.aemu.core.DeltaOta.prepare(ctx, img.id, uri) } }
            setBusy(false)
            result.onSuccess { prepared?.close(); prepared = it; message = null }
                .onFailure { message = ctx.getString(R.string.vs_ota_failed, it.message ?: it.javaClass.simpleName) }
        }
    }
    Text(stringResource(R.string.vs_ota), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_ota_install)) },
        supportingContent = { Text(message ?: stringResource(R.string.vs_ota_sub)) },
        trailingContent = {
            TextButton(enabled = !busy && !picking, onClick = { picking = true; pick.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.vs_ota_button))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    prepared?.let { p ->
        AlertDialog(
            onDismissRequest = { p.close(); prepared = null },
            title = { Text(stringResource(R.string.vs_ota_confirm_title)) },
            text = {
                val base = stringResource(R.string.vs_ota_confirm, p.fromBuild, p.toBuild)
                Text(if (p.partial) base + "\n\n" + stringResource(R.string.vs_ota_partial) else base)
            },
            dismissButton = { TextButton(onClick = { p.close(); prepared = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                prepared = null
                setBusy(true)
                message = ctx.getString(R.string.vs_ota_working, "")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            p.use { app.aemu.core.DeltaOta.install(ctx, img.id, it) { stage ->
                                message = ctx.getString(R.string.vs_ota_working, stage)
                            } }
                        }
                    }
                    setBusy(false)
                    message = result.fold(
                        { ctx.getString(R.string.vs_ota_done, it.toBuild, it.patched, it.added, it.removed) +
                            it.notes.joinToString("") { n -> "\n$n" } },
                        { ctx.getString(R.string.vs_ota_failed, it.message ?: it.javaClass.simpleName) },
                    )
                }
            }) { Text(stringResource(R.string.vs_ota_confirm_ok)) } },
        )
    }
}

private fun recoveryStatus(paths: app.aemu.core.VmPaths): String? {
    if (!app.aemu.core.RecoveryImage.installed(paths)) return null
    val dir = app.aemu.core.RecoveryImage.dir(paths)
    val prop = runCatching { java.io.File(dir, "default.prop").readText() }.getOrDefault("")
    val twrp = Regex("""ro\.twrp\.version=(\S+)""").find(prop)?.groupValues?.get(1)
    return when {
        twrp != null -> "TWRP $twrp"
        java.io.File(dir, "twres").isDirectory -> "TWRP"
        java.io.File(dir, "sbin/orangefox.sh").exists() || prop.contains("orangefox", true) -> "OrangeFox"
        prop.contains("cwm", true) || java.io.File(dir, "res/images/icon_clockwork.png").exists() -> "ClockworkMod"
        else -> "Stock recovery"
    }
}
