package com.danxing.spatialscene.wallpaper

/**
 * M0 阶段的程序化测试场景（无贴图、无模型）。
 *
 * 顶点着色器为每个顶点计算一个"深度"，并按深度做位移；
 * 片元着色器按同一套深度函数绘制分层风景。
 * M1/M2 会把 sceneDepth 换成真实照片 + 模型深度图。
 */
internal object SceneShaders {

    private const val COMMON = """
        float ridge(float x, float base, float amp, float freq, float seed) {
            return base + amp * (0.55 * sin(x * freq + seed)
                               + 0.30 * sin(x * freq * 2.17 + seed * 1.7)
                               + 0.15 * sin(x * freq * 4.31 + seed * 2.9));
        }

        float sceneDepth(vec2 uv) {
            float farRidge  = ridge(uv.x, 0.545, 0.075, 6.0, 1.3);
            float midRidge  = ridge(uv.x, 0.475, 0.095, 4.3, 4.1);
            float nearRidge = ridge(uv.x, 0.375, 0.130, 3.1, 7.7);
            float d = 0.10;
            d = mix(d, 0.36, 1.0 - smoothstep(farRidge - 0.008, farRidge + 0.008, uv.y));
            d = mix(d, 0.62, 1.0 - smoothstep(midRidge - 0.008, midRidge + 0.008, uv.y));
            d = mix(d, 0.92, 1.0 - smoothstep(nearRidge - 0.012, nearRidge + 0.012, uv.y));
            return d;
        }
    """

    val VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec2 aPos;

        uniform float uZoom;
        uniform vec2 uCam;
        uniform float uParallax;
        uniform float uRoll;

        out vec2 vUv;
        out float vDepth;

        $COMMON

        void main() {
            vec2 uv = aPos * 0.5 + 0.5;
            float d = sceneDepth(uv);

            vec2 p = aPos * uZoom;
            p += uCam * d * uParallax;

            float c = cos(uRoll);
            float s = sin(uRoll);
            p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);

            vUv = uv;
            vDepth = d;
            gl_Position = vec4(p, 0.0, 1.0);
        }
    """

    val FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec2 vUv;
        in float vDepth;

        out vec4 fragColor;

        $COMMON

        void main() {
            float farRidge  = ridge(vUv.x, 0.545, 0.075, 6.0, 1.3);
            float midRidge  = ridge(vUv.x, 0.475, 0.095, 4.3, 4.1);
            float nearRidge = ridge(vUv.x, 0.375, 0.130, 3.1, 7.7);

            vec3 skyTop = vec3(0.055, 0.075, 0.22);
            vec3 skyBottom = vec3(0.98, 0.52, 0.32);
            vec3 col = mix(skyBottom, skyTop, smoothstep(0.42, 1.0, vUv.y));

            // 星空
            vec2 cell = floor(vUv * 700.0);
            float rnd = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
            float star = step(0.9987, rnd) * smoothstep(0.55, 0.85, vUv.y);
            col += star * 0.9;

            // 太阳与光晕
            float sd = distance(vUv, vec2(0.72, 0.64));
            col = mix(col, vec3(1.0, 0.86, 0.58), smoothstep(0.055, 0.032, sd));
            col += smoothstep(0.38, 0.02, sd) * 0.22 * vec3(1.0, 0.45, 0.22);

            vec3 farCol  = vec3(0.30, 0.27, 0.46);
            vec3 midCol  = vec3(0.17, 0.16, 0.31);
            vec3 nearCol = vec3(0.055, 0.05, 0.11);

            float mFar  = 1.0 - smoothstep(farRidge - 0.008, farRidge + 0.008, vUv.y);
            float mMid  = 1.0 - smoothstep(midRidge - 0.008, midRidge + 0.008, vUv.y);
            float mNear = 1.0 - smoothstep(nearRidge - 0.012, nearRidge + 0.012, vUv.y);

            col = mix(col, farCol, mFar);
            col = mix(col, midCol, mMid);
            col = mix(col, nearCol, mNear);

            // 远处的轻微雾化
            col = mix(col, col * vec3(0.92, 0.94, 1.08), (1.0 - vDepth) * 0.25);

            // 暗角
            float vig = smoothstep(1.30, 0.50, length(vUv - 0.5));
            col *= mix(0.80, 1.0, vig);

            fragColor = vec4(col, 1.0);
        }
    """
}
