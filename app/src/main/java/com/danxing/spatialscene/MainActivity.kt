package com.danxing.spatialscene

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.danxing.spatialscene.depth.DepthEstimator
import com.danxing.spatialscene.depth.DepthRefiner
import com.danxing.spatialscene.depth.SoftLayerDecomposer
import com.danxing.spatialscene.scene.AppSettings
import com.danxing.spatialscene.scene.Scene
import com.danxing.spatialscene.scene.SceneStore
import com.danxing.spatialscene.ui.theme.SpatialSceneTheme
import com.danxing.spatialscene.util.ImageLoader
import com.danxing.spatialscene.wallpaper.SpatialWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpatialSceneTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("空间场景") }) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("照片") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("场景库") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("设置") })
            }
            when (tab) {
                0 -> PhotoTab()
                1 -> LibraryTab()
                else -> SettingsTab()
            }
        }
    }
}

// ------------------------------------------------------------------ 照片

@Composable
private fun PhotoTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SceneStore.get(context) }

    var estimator by remember { mutableStateOf<DepthEstimator?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var depthBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var soft by remember { mutableStateOf<SoftLayerDecomposer.Result?>(null) }
    // 0=原图 1=深度 2=前景alpha 3=背景板(AI补全)
    var previewMode by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runMs by remember { mutableStateOf<Long?>(null) }
    var totalMs by remember { mutableStateOf<Long?>(null) }
    var savedName by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        depthBitmap = null
        soft = null
        previewMode = 0
        runMs = null
        totalMs = null
        savedName = null
        scope.launch {
            try {
                status = "读取照片…"
                val source = withContext(Dispatchers.IO) {
                    ImageLoader.load(context, uri, maxLongSide = 2048)
                }
                original = source

                status = "加载深度模型（首次约 1~2 秒）…"
                val depthEstimator = withContext(Dispatchers.Default) {
                    estimator ?: DepthEstimator(context).also { estimator = it }
                }

                status = "分析深度…"
                val start = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.Default) {
                    depthEstimator.estimate(source)
                }
                totalMs = SystemClock.elapsedRealtime() - start
                runMs = result.runMs

                status = "精修边缘…"
                val refined = withContext(Dispatchers.Default) {
                    DepthRefiner.refine(
                        result.depthValues, result.width, result.height, source,
                        targetLongSide = 1024
                    )
                }
                depthBitmap = refined.bitmap

                status = "软分层 · AI 补全被遮挡的背景…"
                soft = withContext(Dispatchers.Default) {
                    SoftLayerDecomposer.decompose(
                        context, source, refined.values, refined.width, refined.height
                    )
                }

                if (soft == null) {
                    error = "分层失败：" + (SoftLayerDecomposer.lastFailure ?: "未知原因")
                }
                status = null
            } catch (t: Throwable) {
                Log.e("SpatialScene", "深度估计失败", t)
                error = t.message ?: t.javaClass.simpleName
                status = null
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "M2 · 照片 → 空间场景",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "选一张照片，离线生成深度并精修边缘，保存后即可设为动态壁纸；" +
                "倾斜手机时近景移动更多，像一扇看向照片深处的窗户。",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        ) {
            Text(if (original == null) "选择一张照片" else "换一张照片")
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(status ?: "处理中…")
            }
        }

        error?.let {
            val clipboard = LocalClipboardManager.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "出错了：$it",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(it)) }) {
                        Text("复制错误信息")
                    }
                }
            }
        }

        val preview = when (previewMode) {
            1 -> depthBitmap ?: original
            2 -> soft?.foregroundAlpha ?: original
            3 -> soft?.backgroundImage ?: original
            4 -> soft?.holeMask ?: original
            5 -> soft?.maskOverlay ?: original
            else -> original
        }
        if (preview != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        contentScale = ContentScale.Fit
                    )
                    if (depthBitmap != null) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = previewMode == 0,
                                onClick = { previewMode = 0 },
                                label = { Text("原图") }
                            )
                            FilterChip(
                                selected = previewMode == 1,
                                onClick = { previewMode = 1 },
                                label = { Text("深度") }
                            )
                            FilterChip(
                                selected = previewMode == 2,
                                onClick = { if (soft != null) previewMode = 2 },
                                label = { Text("主体遮罩") }
                            )
                            FilterChip(
                                selected = previewMode == 3,
                                onClick = { if (soft != null) previewMode = 3 },
                                label = { Text("背景板/AI") }
                            )
                            FilterChip(
                                selected = previewMode == 4,
                                onClick = { if (soft != null) previewMode = 4 },
                                label = { Text("AI 洞") }
                            )
                            FilterChip(
                                selected = previewMode == 5,
                                onClick = { if (soft != null) previewMode = 5 },
                                label = { Text("遮罩叠加") }
                            )
                        }
                        if (soft == null) {
                            Text(
                                text = "（分层未生成，「主体遮罩 / 背景板」不可用）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (totalMs != null) {
                        Text(
                            text = "总计 ${totalMs} ms（模型运行 ${runMs} ms）" +
                                " · 深度 ${depthBitmap?.width}×${depthBitmap?.height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (depthBitmap != null && original != null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    val image = original ?: return@Button
                    val depth = depthBitmap ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            status = "保存场景…"
                            val name = "空间场景 " + SimpleDateFormat(
                                "MM-dd HH:mm", Locale.getDefault()
                            ).format(Date())
                            val thumb = withContext(Dispatchers.Default) {
                                val scale = 512f / max(image.width, image.height)
                                Bitmap.createScaledBitmap(
                                    image,
                                    (image.width * scale).roundToInt().coerceAtLeast(1),
                                    (image.height * scale).roundToInt().coerceAtLeast(1),
                                    true
                                )
                            }
                            withContext(Dispatchers.IO) {
                                store.save(name, image, depth, thumb, soft)
                            }
                            savedName = name
                            status = null
                        } catch (t: Throwable) {
                            Log.e("SpatialScene", "保存场景失败", t)
                            error = t.message ?: t.javaClass.simpleName
                            status = null
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("保存到场景库并应用")
            }
        }

        savedName?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val softNote = soft?.note
                    Text("已保存「$it」并设为当前场景。", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (softNote != null) {
                            "已保存软分层：$softNote"
                        } else {
                            "分层失败，已按单层视差保存。原因：" +
                                (SoftLayerDecomposer.lastFailure ?: "未知")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "如果动态壁纸已经设置过，回到桌面/锁屏即可看到；否则先设置一次。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OpenWallpaperListButton(context)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 场景库

@Composable
private fun LibraryTab() {
    val context = LocalContext.current
    val store = remember { SceneStore.get(context) }
    var scenes by remember { mutableStateOf(store.list()) }
    var activeId by remember { mutableStateOf(store.activeId()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "场景库",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "算好的场景都在这里，切换时不需要重新计算。",
            style = MaterialTheme.typography.bodyMedium
        )

        if (scenes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "还没有空间场景。去「照片」页选一张照片生成吧。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            scenes.forEach { scene ->
                SceneCard(
                    scene = scene,
                    active = scene.id == activeId,
                    onApply = {
                        store.setActive(scene.id)
                        activeId = scene.id
                        message = "已应用「${scene.name}」"
                    },
                    onDelete = {
                        store.delete(scene.id)
                        scenes = store.list()
                        activeId = store.activeId()
                        message = "已删除「${scene.name}」"
                    }
                )
            }
        }

        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "提示：切换场景后，若已设置过动态壁纸会立即生效；首次设置请点场景卡片上的「设为壁纸」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    active: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val thumb = remember(scene.id) { BitmapFactory.decodeFile(scene.thumbPath) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (active) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(scene.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${scene.imageWidth}×${scene.imageHeight} · " +
                            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(Date(scene.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (active) {
                        Text(
                            text = "使用中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = when {
                            scene.softLayered && scene.aiFilled -> "AI 软分层（主体 + 补全背景）"
                            scene.softLayered -> "软分层（几何补全）"
                            else -> "单层视差（旧场景，建议重新生成）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (scene.softLayered) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onApply, enabled = !active) { Text("应用") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = { setLiveWallpaper(context) }) { Text("设为壁纸") }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val store = remember { SceneStore.get(context) }
    val active = remember { store.activeScene() }

    var parallax by remember { mutableFloatStateOf(settings.parallax) }
    var gamma by remember { mutableFloatStateOf(settings.depthGamma) }
    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }

    var zoom by remember { mutableFloatStateOf(active?.zoom ?: 1.12f) }
    var offsetX by remember { mutableFloatStateOf(active?.offsetX ?: 0f) }
    var offsetY by remember { mutableFloatStateOf(active?.offsetY ?: 0f) }

    fun saveTransform() {
        active?.let {
            store.updateTransform(it.withTransform(zoom, offsetX, offsetY))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "修改后立即生效（动态壁纸正在运行时）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SliderRow(
            label = "视差强度",
            value = parallax,
            range = 0.4f..1.8f,
            hint = "越大倾斜时移动越明显（可能显得更“立体”），越小越接近 iOS 的克制感。",
            onValueChange = { parallax = it },
            onFinished = { settings.parallax = parallax }
        )

        SliderRow(
            label = "景深范围",
            value = gamma,
            range = 0.6f..1.4f,
            hint = "小 = 更多中景一起动（整体更活）；大 = 只有近景动（层次更强）。",
            onValueChange = { gamma = it },
            onFinished = { settings.depthGamma = gamma }
        )

        SliderRow(
            label = "跟随灵敏度",
            value = sensitivity,
            range = 0.4f..1.8f,
            hint = "手机倾斜同样的角度，相机移动多少。",
            onValueChange = { sensitivity = it },
            onFinished = { settings.sensitivity = sensitivity }
        )

        Text(
            text = "画面调整（当前场景）",
            style = MaterialTheme.typography.titleSmall
        )

        if (active == null) {
            Text(
                text = "还没有活动场景，先到「照片」页生成一个。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SliderRow(
                label = "缩放",
                value = zoom,
                range = 1.0f..2.0f,
                hint = "放大画面。值越大，安全边距越大，可支持的视差越强（建议 ≥1.12）。",
                onValueChange = { zoom = it },
                onFinished = { saveTransform() }
            )
            SliderRow(
                label = "水平位置",
                value = offsetX,
                range = -0.2f..0.2f,
                onValueChange = { offsetX = it },
                onFinished = { saveTransform() }
            )
            SliderRow(
                label = "垂直位置",
                value = offsetY,
                range = -0.2f..0.2f,
                onValueChange = { offsetY = it },
                onFinished = { saveTransform() }
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "动态壁纸的设置按钮在「场景库」每个场景卡片上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String? = null,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onFinished
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ 公共

/** 直接打开系统的动态壁纸设置（不需要再滚到页面底部）。 */
private fun setLiveWallpaper(context: Context) {
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, SpatialWallpaperService::class.java)
        )
    context.startActivity(intent)
}

@Composable
private fun OpenWallpaperListButton(context: Context) {
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    ) {
        Text("打开系统动态壁纸列表")
    }
}
