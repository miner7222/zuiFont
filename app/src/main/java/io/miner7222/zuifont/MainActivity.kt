package io.miner7222.zuifont

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ZuiFontSwitch"

enum class BusyAction { APPLY, RESTORE }

data class UiState(
    val selectedName: String? = null,
    val currentFont: String? = null,
    val busyAction: BusyAction? = null,
    val noticeVisible: Boolean = false,
)

private object AppState {
    var ui: UiState by mutableStateOf(UiState())
    var pickedUri: Uri? = null
    var snackbarMessage = ""
    var snackbarId by mutableStateOf(0)
    var snackbarShownId = 0
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

private fun describe(context: Context, throwable: Throwable): String = when (throwable) {
    is FontSwitchException -> throwable.arg
        ?.let { context.getString(throwable.messageRes, it) }
        ?: context.getString(throwable.messageRes)
    else -> throwable.message ?: throwable.toString()
}

class MainActivity : ComponentActivity() {

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZuiFontTheme {
                ZuiFontScreen(
                    state = AppState.ui,
                    onPick = { pickFont.launch(arrayOf("*/*")) },
                    onApply = ::applyFont,
                    onRestore = ::restoreDefaultFont,
                )
            }
        }
        handleIntent(intent)
        refreshCurrentFont()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setPicked(uri)
    }

    private fun setPicked(uri: Uri) {
        AppState.pickedUri = uri
        AppState.ui = AppState.ui.copy(selectedName = FontSwitcher.queryDisplayName(contentResolver, uri))
    }

    private fun notify(message: String) {
        AppState.snackbarMessage = message
        AppState.snackbarId++
    }

    private fun refreshCurrentFont() {
        val appContext = applicationContext
        AppState.scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    FontSwitcher.requirePermission(appContext)
                    FontSwitcher.getSystemFont(FontSwitcher.themeService())
                }
            }.onSuccess { current ->
                AppState.ui = AppState.ui.copy(currentFont = current)
            }.onFailure {
                Log.w(TAG, "initial check failed", it)
            }
        }
    }

    private fun applyFont() {
        val uri = AppState.pickedUri ?: return
        val appContext = applicationContext
        val displayName = AppState.ui.selectedName ?: FontSwitcher.queryDisplayName(contentResolver, uri)
        AppState.scope.launch {
            AppState.ui = AppState.ui.copy(busyAction = BusyAction.APPLY)
            try {
                val result = withContext(Dispatchers.IO) {
                    FontSwitcher.requirePermission(appContext)
                    val extension = FontSwitcher.fontExtension(displayName)
                        ?: throw FontSwitchException(R.string.err_unsupported)
                    val sniffed = FontSwitcher.sniffFontType(appContext.contentResolver, uri)
                        ?: throw FontSwitchException(R.string.err_signature)
                    if (sniffed != extension) {
                        Log.i(TAG, "extension=$extension but signature=$sniffed, using $sniffed")
                    }
                    val targetName = "zfs_" + System.currentTimeMillis() + "." + sniffed
                    val fontName = "Zfs-" + FontSwitcher.sanitizeBaseName(displayName)
                    val bytes = FontSwitcher.uploadFont(appContext.contentResolver, uri, targetName)
                    val binder = FontSwitcher.themeService()
                    FontSwitcher.changeSystemFont(binder, targetName, fontName)
                    Log.i(TAG, "uploaded $bytes bytes, fontName=$fontName")
                    fontName to binder
                }
                val (fontName, binder) = result
                val current = withContext(Dispatchers.IO) { FontSwitcher.pollSystemFont(binder, fontName) }
                if (current == fontName) {
                    AppState.ui = AppState.ui.copy(currentFont = current, noticeVisible = true)
                    notify(appContext.getString(R.string.toast_apply_done))
                } else {
                    notify(appContext.getString(R.string.toast_error, "fontname=${current ?: "null"}"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "applyFont failed", t)
                notify(appContext.getString(R.string.toast_error, describe(appContext, t)))
            } finally {
                AppState.ui = AppState.ui.copy(busyAction = null)
            }
        }
    }

    private fun restoreDefaultFont() {
        val appContext = applicationContext
        AppState.scope.launch {
            AppState.ui = AppState.ui.copy(busyAction = BusyAction.RESTORE, noticeVisible = true)
            try {
                val binder = withContext(Dispatchers.IO) {
                    FontSwitcher.requirePermission(appContext)
                    FontSwitcher.themeService().also { FontSwitcher.changeSystemFont(it, null, "system") }
                }
                val current = withContext(Dispatchers.IO) { FontSwitcher.pollSystemFont(binder, "system") }
                if (current == "system") {
                    AppState.ui = AppState.ui.copy(currentFont = current, noticeVisible = true)
                    notify(appContext.getString(R.string.toast_restore_done))
                } else {
                    notify(appContext.getString(R.string.toast_error, "fontname=${current ?: "null"}"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "restoreDefaultFont failed", t)
                notify(appContext.getString(R.string.toast_error, describe(appContext, t)))
            } finally {
                AppState.ui = AppState.ui.copy(busyAction = null)
            }
        }
    }
}

@Composable
private fun ZuiFontTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
private fun ZuiFontScreen(
    state: UiState,
    onPick: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(AppState.snackbarId) {
        val target = AppState.snackbarId
        if (target != AppState.snackbarShownId) {
            snackbarHostState.showSnackbar(AppState.snackbarMessage)
            AppState.snackbarShownId = target
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { CompactSnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state)
                SelectionCard(state.selectedName, enabled = state.busyAction == null, onPick = onPick)
                PrimaryActions(state, onApply = onApply, onRestore = onRestore)
            }
        }
    }
}

@Composable
private fun CompactSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier.padding(bottom = 16.dp),
    ) { data ->
        Surface(
            shape = SnackbarDefaults.shape,
            color = SnackbarDefaults.color,
            contentColor = SnackbarDefaults.contentColor,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 600.dp),
        ) {
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "zuiFont",
            style = MaterialTheme.typography.headlineLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            val current = state.currentFont?.takeIf { it.isNotBlank() }
            Text(
                stringResource(R.string.current_font, current ?: stringResource(R.string.default_value)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SelectionCard(selectedName: String?, enabled: Boolean, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.font_file_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                selectedName ?: stringResource(R.string.no_file_selected),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (selectedName == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            FilledTonalButton(
                onClick = onPick,
                enabled = enabled,
                contentPadding = ButtonDefaults.SmallContentPadding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pick_font))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryActions(state: UiState, onApply: () -> Unit, onRestore: () -> Unit) {
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    var indicatorVisible by remember { mutableStateOf(false) }
    var longWait by remember { mutableStateOf(false) }
    LaunchedEffect(state.busyAction) {
        if (state.busyAction == null) {
            indicatorVisible = false
            longWait = false
            return@LaunchedEffect
        }
        delay(200)
        indicatorVisible = true
        delay(4800)
        longWait = true
    }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    val restoring = indicatorVisible && state.busyAction == BusyAction.RESTORE
    val applying = indicatorVisible && state.busyAction == BusyAction.APPLY
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RestoreButton(state, onRestore, restoring, longWait, effectsSpec, Modifier.weight(1f))
                ApplyButton(state, onApply, applying, longWait, effectsSpec, Modifier.weight(1f))
            }
        } else {
            RestoreButton(state, onRestore, restoring, longWait, effectsSpec, Modifier.fillMaxWidth())
            ApplyButton(state, onApply, applying, longWait, effectsSpec, Modifier.fillMaxWidth())
        }
        AnimatedVisibility(
            visible = state.noticeVisible,
            enter = fadeIn(animationSpec = effectsSpec),
            exit = fadeOut(animationSpec = effectsSpec),
        ) {
            Text(
                stringResource(R.string.notice_restart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RestoreButton(
    state: UiState,
    onRestore: () -> Unit,
    showIndicator: Boolean,
    longWait: Boolean,
    effectsSpec: FiniteAnimationSpec<Float>,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onRestore,
        enabled = state.busyAction == null,
        contentPadding = ButtonDefaults.SmallContentPadding,
        modifier = modifier,
    ) {
        ActionLabel(showIndicator, longWait, effectsSpec, R.string.restore_default, R.string.restoring)
    }
}

@Composable
private fun ApplyButton(
    state: UiState,
    onApply: () -> Unit,
    showIndicator: Boolean,
    longWait: Boolean,
    effectsSpec: FiniteAnimationSpec<Float>,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onApply,
        enabled = state.busyAction == null && state.selectedName != null,
        contentPadding = ButtonDefaults.SmallContentPadding,
        modifier = modifier,
    ) {
        ActionLabel(showIndicator, longWait, effectsSpec, R.string.apply_font, R.string.applying)
    }
}

@Composable
private fun ActionLabel(
    showIndicator: Boolean,
    longWait: Boolean,
    effectsSpec: FiniteAnimationSpec<Float>,
    idleTextRes: Int,
    busyTextRes: Int,
) {
    Crossfade(
        targetState = showIndicator,
        animationSpec = effectsSpec,
        label = "buttonLabel",
    ) { busy ->
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (longWait) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.SmallIconSize),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                } else {
                    LoadingIndicator(
                        modifier = Modifier.size(ButtonDefaults.SmallIconSize),
                        color = LocalContentColor.current,
                    )
                }
                Text(stringResource(busyTextRes))
            }
        } else {
            Text(stringResource(idleTextRes))
        }
    }
}
