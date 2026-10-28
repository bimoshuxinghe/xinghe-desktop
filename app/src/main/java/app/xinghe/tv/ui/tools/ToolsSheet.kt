package app.xinghe.tv.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.xinghe.tv.R
import app.xinghe.tv.apps.AppCatalog
import app.xinghe.tv.system.MemCleaner
import app.xinghe.tv.system.NetSpeed
import app.xinghe.tv.ui.components.consumeTaps
import app.xinghe.tv.ui.components.dismissOnTap
import app.xinghe.tv.ui.components.liquidGlass
import app.xinghe.tv.ui.home.MenuRow
import app.xinghe.tv.ui.theme.Glyph
import app.xinghe.tv.ui.theme.Motion
import app.xinghe.tv.ui.theme.Palette
import app.xinghe.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The two things a person asks the TV when it feels slow: how fast the line is, and what is
 * running behind the screen. Both are answered by the TV itself — the speed against a mirror it
 * pulls a file from, the memory from the system's own counters — so nothing about the household
 * leaves the house.
 */

private sealed interface SpeedState {
    data object Idle : SpeedState
    data class Running(val step: NetSpeed.Step) : SpeedState
    data class Done(val result: NetSpeed.Result) : SpeedState
}

@Composable
fun SpeedTestSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SpeedState>(SpeedState.Idle) }
    BackHandler(enabled = visible, onBack = onDismiss)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible, exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
            // A press beside the panel closes it, as Back does.
            Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.25f)))
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.96f),
        ) {
            Column(
                Modifier
                    .width(520.dp)
                    .consumeTaps()
                    .liquidGlass(28.dp)
                    .padding(horizontal = 26.dp, vertical = 22.dp),
            ) {
                BasicText(stringResource(R.string.speed_title), style = Type.LargeTitle)
                BasicText(
                    stringResource(R.string.speed_hint),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )

                when (val s = state) {
                    SpeedState.Idle -> Unit
                    is SpeedState.Running -> BasicText(stepText(s.step), style = Type.Body)
                    is SpeedState.Done -> {
                        val r = s.result
                        if (!r.ok) {
                            BasicText(stringResource(R.string.speed_failed), style = Type.Body.copy(color = Palette.Orange))
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                                Figure(stringResource(R.string.speed_ping), "${r.pingMs}", stringResource(R.string.speed_ms))
                                Figure(stringResource(R.string.speed_down), String.format(Locale.US, "%.1f", r.downMbps), stringResource(R.string.speed_mbps))
                            }
                            BasicText(
                                stringResource(R.string.speed_node, r.node),
                                style = Type.Callout.copy(color = Palette.SecondaryLabel),
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (state is SpeedState.Running) {
                    BasicText(stringResource(R.string.speed_running), style = Type.Callout.copy(color = Palette.SecondaryLabel))
                } else {
                    MenuRow(Glyph.Download, stringResource(R.string.speed_start), onClick = {
                        state = SpeedState.Running(NetSpeed.Step(NetSpeed.Phase.Ping))
                        scope.launch {
                            val result = NetSpeed.measure { step ->
                                // The engine speaks from a worker thread; the panel speaks on the
                                // main one, like everything else on screen.
                                scope.launch { state = SpeedState.Running(step) }
                            }
                            state = SpeedState.Done(result)
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun CleanupSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var memory by remember { mutableStateOf(MemCleaner.Memory()) }
    var background by remember { mutableStateOf(0) }
    var cleaned by remember { mutableStateOf<MemCleaner.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    BackHandler(enabled = visible, onBack = onDismiss)

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        memory = withContext(Dispatchers.Default) { MemCleaner.memory(context) }
        background = withContext(Dispatchers.Default) { MemCleaner.backgroundCount(context) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible, exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.25f)))
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.96f),
        ) {
            Column(
                Modifier
                    .width(520.dp)
                    .consumeTaps()
                    .liquidGlass(28.dp)
                    .padding(horizontal = 26.dp, vertical = 22.dp),
            ) {
                BasicText(stringResource(R.string.clean_title), style = Type.LargeTitle)
                BasicText(
                    stringResource(R.string.clean_hint),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        stringResource(R.string.clean_used, memory.usedMb, memory.totalMb),
                        style = Type.Title,
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        stringResource(R.string.clean_free, memory.freeMb),
                        style = Type.Callout.copy(color = if (memory.low) Palette.Orange else Palette.SecondaryLabel),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // The bar: how much of the machine is taken.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Palette.Fill),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(memory.usedFraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (memory.low) Palette.Orange else Palette.Green),
                    )
                }
                Spacer(Modifier.height(10.dp))
                BasicText(
                    stringResource(R.string.clean_background, background),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                )

                cleaned?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        stringResource(R.string.clean_result, r.killed, r.cacheMb),
                        style = Type.Body.copy(color = Palette.Green),
                    )
                    if (r.refused > 0) {
                        BasicText(
                            stringResource(R.string.clean_refused, r.refused),
                            style = Type.Callout.copy(color = Palette.Orange),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (busy) {
                    BasicText(stringResource(R.string.clean_running), style = Type.Callout.copy(color = Palette.SecondaryLabel))
                } else {
                    MenuRow(Glyph.Trash, stringResource(R.string.clean_run), onClick = {
                        busy = true
                        cleaned = null
                        scope.launch {
                            val installed = AppCatalog.loaded.mapTo(HashSet()) { it.packageName }
                            val r = MemCleaner.clean(context, installed)
                            memory = withContext(Dispatchers.Default) { MemCleaner.memory(context) }
                            background = withContext(Dispatchers.Default) { MemCleaner.backgroundCount(context) }
                            cleaned = r
                            busy = false
                        }
                    })
                    // Where the system refused to let go of a process, it is the only one that can:
                    // it asks the user, once, in its own words.
                    if ((cleaned?.refused ?: 0) > 0) {
                        MenuRow(Glyph.Shield, stringResource(R.string.clean_system), onClick = {
                            runCatching { context.startActivity(MemCleaner.systemCleanupIntent()) }
                        })
                    }
                }
            }
        }
    }
}

/** What the test is doing right now, said in the TV's language. */
@Composable
private fun stepText(step: NetSpeed.Step): String = when {
    // Before the first mirror has answered there is no name to name.
    step.node.isEmpty() -> stringResource(R.string.speed_running)
    step.phase == NetSpeed.Phase.Ping -> stringResource(R.string.speed_step_ping, step.node)
    step.phase == NetSpeed.Phase.Download -> stringResource(R.string.speed_step_down, step.node)
    else -> stringResource(R.string.speed_step_pull, step.doneMb)
}

/** A number with a label over it: how the TV shows a measurement. */
@Composable
private fun Figure(label: String, value: String, unit: String) {    Column {
        BasicText(label, style = Type.Callout.copy(color = Palette.SecondaryLabel))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(value, style = Type.LargeTitle)
            Spacer(Modifier.width(4.dp))
            BasicText(unit, style = Type.Callout.copy(color = Palette.SecondaryLabel), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
