package com.danxing.spatialscene

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danxing.spatialscene.depth.DepthEstimator
import com.danxing.spatialscene.ui.theme.SpatialSceneTheme
import com.danxing.spatialscene.util.ImageLoader
import com.danxing.spatialscene.wallpaper.SpatialWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpatialSceneTheme {
                HomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("空间场景") }) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("照片 → 深度") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("壁纸") }
                )
            }
            when (selectedTab) {
                0 -> PhotoTab()
                else -> WallpaperTab()
            }
        }
    }
}

// ------------------------------------------------------------------ 照片 Tab

@Composable
private fun PhotoTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var estimator by remember { mutableStateOf<DepthEstimator?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var depthBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDepth by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runMs by remember { mutableStateOf<Long?>(null) }
    var totalMs by remember { mutableStateOf<Long?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        depthBitmap = null
        runMs = null
        totalMs = null
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

                status = "估计深度…"
                val start = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.Default) {
                    depthEstimator.estimate(source)
                }
                totalMs = SystemClock.elapsedRealtime() - start
                runMs = result.runMs
                depthBitmap = result.depthBitmap
                showDepth = true
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
            text = "M1 · 照片 → 深度图",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "选一张照片，会在这台手机上离线运行深度模型（Depth Anything V2 Small）。M2 会把深度图变成真正会动的空间壁纸。",
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
            Text(
                text = "出错了：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val preview = if (showDepth) depthBitmap ?: original else original
        if (preview != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentScale = ContentScale.Fit
                    )
                    if (depthBitmap != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showDepth,
                                onClick = { showDepth = false },
                                label = { Text("原图") }
                            )
                            FilterChip(
                                selected = showDepth,
                                onClick = { showDepth = true },
                                label = { Text("深度图") }
                            )
                        }
                    }
                    if (totalMs != null) {
                        Text(
                            text = "总计 ${totalMs} ms（模型运行 ${runMs} ms）" +
                                " · 输出 ${depthBitmap?.width}×${depthBitmap?.height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 壁纸 Tab

@Composable
private fun WallpaperTab() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "M0 地基 · 陀螺仪视差动态壁纸",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("这一版用来验证渲染地基：", style = MaterialTheme.typography.titleSmall)
                Text("1. 倾斜手机时，近景/中景/远景分层移动；")
                Text("2. 锁屏上动态壁纸是否正常运行（ColorOS 已确认可用）；")
                Text("3. 边缘不露底、手感跟手。")
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(context, SpatialWallpaperService::class.java)
                    )
                context.startActivity(intent)
            }
        ) {
            Text("设置为动态壁纸")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        ) {
            Text("打开系统动态壁纸列表")
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "提示：系统选择器里建议选「主屏幕和锁屏」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
