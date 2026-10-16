package com.danxing.spatialscene

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danxing.spatialscene.ui.theme.SpatialSceneTheme
import com.danxing.spatialscene.wallpaper.SpatialWallpaperService

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
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("空间场景") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "M0 测试版 · 陀螺仪视差动态壁纸",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("这一版用来验收地基：", style = MaterialTheme.typography.titleSmall)
                    Text("1. 设置后倾斜手机，画面近景/中景/远景应分层移动；")
                    Text("2. 重点观察锁屏上动态壁纸是否真的在动（ColorOS 兼容性）；")
                    Text("3. 留意画面边缘有没有露底、手感是否跟手。")
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

            Spacer(Modifier.height(8.dp))

            Text(
                text = "提示：系统选择器里建议选「主屏幕和锁屏」，这样才能验证锁屏是否支持动态壁纸。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
