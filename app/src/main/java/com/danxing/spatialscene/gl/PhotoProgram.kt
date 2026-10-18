package com.danxing.spatialscene.gl

import android.opengl.GLES30

/**
 * 照片 + 深度图的网格视差渲染。
 *
 * 三种用法：
 * - 单层：照片纹理 + 深度纹理（旧场景的兼容路径）
 * - 背景层：补全后的背景图 + 背景深度（普通不透明绘制）
 * - 前景层：[constantDepth] 常数深度 + 羽化 alpha（刚性整体平移，不产生波浪纹）
 */
class PhotoProgram {

    private val program = GlCore.compile(VERTEX, FRAGMENT)

    private val uScale = GLES30.glGetUniformLocation(program, "uScale")
    private val uOffset = GLES30.glGetUniformLocation(program, "uOffset")
    private val uCam = GLES30.glGetUniformLocation(program, "uCam")
    private val uParallax = GLES30.glGetUniformLocation(program, "uParallax")
    private val uGamma = GLES30.glGetUniformLocation(program, "uGamma")
    private val uImageTex = GLES30.glGetUniformLocation(program, "uImageTex")
    private val uDepthTex = GLES30.glGetUniformLocation(program, "uDepthTex")
    private val uAlphaTex = GLES30.glGetUniformLocation(program, "uAlphaTex")
    private val uVignette = GLES30.glGetUniformLocation(program, "uVignette")
    private val uUseDepthValue = GLES30.glGetUniformLocation(program, "uUseDepthValue")
    private val uDepthValue = GLES30.glGetUniformLocation(program, "uDepthValue")
    private val uUseAlpha = GLES30.glGetUniformLocation(program, "uUseAlpha")

    fun draw(
        grid: GlCore.Grid,
        imageTexture: Int,
        depthTexture: Int,
        alphaTexture: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        camX: Float,
        camY: Float,
        parallax: Float,
        depthGamma: Float,
        constantDepth: Float? = null,
        useAlpha: Boolean = false,
        vignette: Float = 0f
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, offsetX, offsetY)
        GLES30.glUniform2f(uCam, camX, camY)
        GLES30.glUniform1f(uParallax, parallax)
        GLES30.glUniform1f(uGamma, depthGamma)
        GLES30.glUniform1f(uVignette, vignette)
        GLES30.glUniform1f(uUseDepthValue, if (constantDepth != null) 1f else 0f)
        GLES30.glUniform1f(uDepthValue, constantDepth ?: 0f)
        GLES30.glUniform1f(uUseAlpha, if (useAlpha) 1f else 0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLES30.glUniform1i(uImageTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glUniform1i(uDepthTex, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTexture)
        GLES30.glUniform1i(uAlphaTex, 2)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, grid.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, grid.ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, grid.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }

    companion object {
        private const val VERTEX = """#version 300 es
            precision highp float;
            layout(location = 0) in vec2 aPos;

            uniform vec2 uScale;
            uniform vec2 uOffset;

            out vec2 vNdc;

            void main() {
                // 只做 cover 缩放/平移；视差位移全部放到片元着色器里逐像素计算，
                // 避免三角形跨越深度边界时把边缘"拉"出波浪（网格插值伪影）。
                vec2 p = aPos * uScale + uOffset;
                vNdc = p;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """#version 300 es
            precision highp float;

            in vec2 vNdc;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            uniform vec2 uCam;
            uniform float uParallax;
            uniform float uGamma;
            uniform float uUseDepthValue;
            uniform float uDepthValue;

            uniform sampler2D uImageTex;
            uniform sampler2D uDepthTex;
            uniform sampler2D uAlphaTex;
            uniform float uUseAlpha;
            uniform float uVignette;
            out vec4 fragColor;

            vec2 ndcToTex(vec2 ndc) {
                vec2 aPos = (ndc - uOffset) / uScale;
                vec2 uv = aPos * 0.5 + 0.5;
                // Bitmap 第 0 行上传后在纹理 v=0（OpenGL 底部），需要翻转 v
                return vec2(uv.x, 1.0 - uv.y);
            }

            void main() {
                // 1) 先用"未位移"的纹理坐标取深度
                vec2 t0 = ndcToTex(vNdc);
                vec2 t0c = clamp(t0, vec2(0.0), vec2(1.0));
                float d = (uUseDepthValue > 0.5)
                    ? uDepthValue
                    : texture(uDepthTex, t0c).r;
                d = pow(clamp(d, 0.0, 1.0), uGamma);

                // 2) 逐像素位移：屏幕位移 = uCam * d * uParallax
                vec2 tuv = ndcToTex(vNdc - uCam * d * uParallax);
                tuv = clamp(tuv, vec2(0.0), vec2(1.0));

                vec3 col = texture(uImageTex, tuv).rgb;
                float a = 1.0;
                if (uUseAlpha > 0.5) {
                    a = texture(uAlphaTex, tuv).r;
                }
                float vig = smoothstep(1.30, 0.55, length(tuv - 0.5));
                col *= mix(1.0 - uVignette, 1.0, vig);
                fragColor = vec4(col, a);
            }
        """
    }
}
