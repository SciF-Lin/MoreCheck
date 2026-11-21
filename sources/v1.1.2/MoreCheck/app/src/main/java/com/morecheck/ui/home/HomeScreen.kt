package com.morecheck.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.CheckTask
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.ConfettiEffect
import com.morecheck.util.fullLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val checked by viewModel.checkedToday.collectAsStateWithLifecycle()
    val doneCount by viewModel.doneCount.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<CheckTask?>(null) }
    var celebrateKey by remember { mutableIntStateOf(0) }

    val haptic = LocalHapticFeedback.current
    val progress = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                today.fullLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "今天打卡了吗？",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (tasks.isEmpty()) "先添加一个任务吧" else "已完成 $doneCount / ${tasks.size} 项",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(Modifier.height(28.dp))

            BigCheckButton(progress = progress, onClick = { showPicker = true })

            Spacer(Modifier.height(32.dp))

            if (tasks.isNotEmpty()) {
                Text(
                    "我的任务",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    tasks.forEach { task ->
                        val isChecked = task.id in checked
                        TaskCircle(
                            task = task,
                            checked = isChecked,
                            onClick = {
                                if (isChecked) {
                                    pendingUndo = task
                                } else {
                                    viewModel.checkIn(task.id)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    celebrateKey++
                                }
                            }
                        )
                    }
                }
            }
        }

        ConfettiEffect(celebrateKey, Modifier.fillMaxSize())

        pendingUndo?.let { task ->
            ConfirmDialog(
                title = "撤销打卡",
                text = "确定要撤销「${task.name}」今天的打卡吗？",
                confirmLabel = "撤销",
                onConfirm = {
                    viewModel.undo(task.id)
                    pendingUndo = null
                },
                onDismiss = { pendingUndo = null }
            )
        }

        if (showPicker) {
            TaskPickerSheet(
                tasks = tasks,
                checked = checked,
                onDismiss = { showPicker = false },
                onConfirm = { ids ->
                    if (ids.isNotEmpty()) {
                        viewModel.checkInMany(ids)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        celebrateKey++
                    }
                    showPicker = false
                }
            )
        }
    }
}

@Composable
private fun BigCheckButton(progress: Float, onClick: () -> Unit) {
    val ringColor = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(180.dp)) {
            val strokeWidth = 10.dp.toPx()
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = ringColor.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth, cap = StrokeCap.Round)
            )
        }
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(ringColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "打卡",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    "打卡",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun TaskCircle(task: CheckTask, checked: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(checked) {
        if (checked) {
            scale.snapTo(0.8f)
            scale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)
            )
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .clip(CircleShape)
                    .background(Color(task.color))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(task.icon, fontSize = 26.sp)
            }
            if (checked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已打卡",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            task.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPickerSheet(
    tasks: List<CheckTask>,
    checked: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "选择要打卡的任务",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "已选 ${selected.size} 项",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (tasks.isEmpty()) {
                Text(
                    "还没有任务，请先到「任务」页添加",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tasks.forEach { task ->
                    val done = task.id in checked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !done) {
                                if (task.id in selected) selected.remove(task.id) else selected.add(task.id)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(task.color)),
                            contentAlignment = Alignment.Center
                        ) { Text(task.icon, fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (done) {
                            Text("已打卡", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Checkbox(
                                checked = task.id in selected,
                                onCheckedChange = { if (it) selected.add(task.id) else selected.remove(task.id) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onConfirm(selected.toSet()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("打卡") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
