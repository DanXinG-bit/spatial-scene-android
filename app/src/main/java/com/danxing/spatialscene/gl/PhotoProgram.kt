package com.danxing.spatialscene.gl

import android.opengl.GLES30

/**
 * 照片 + 深度图的网格视差渲染。
 *
 * 顶点按深度图位移（近处位移大），片元直接用照片纹理；
 * 画面通过 uScale/uOffset 做「cover 填充 + 用户缩放/位移」，
 * uScale 同时提供安全边距，配合渲染器的相机 clamp 保证不露边。
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
    private val uVignette = GLES30.glGetUniformLocation(program, "uVignette")

    fun draw(
        grid: GlCore.Grid,
        imageTexture: Int,
        depthTexture: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        camX: Float,
        camY: Float,
        parallax: Float,
        depthGamma: Float,
        vignette: Float = 0f
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, offsetX, offsetY)
        GLES30.glUniform2f(uCam, camX, camY)
        GLES30.glUniform1f(uParallax, parallax)
        GLES30.glUniform1f(uGamma, depthGamma)
        GLES30.glUniform1f(uVignette, vignette)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLES30.glUniform1i(uImageTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glUniform1i(uDepthTex, 1)

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
            uniform vec2 uCam;
            uniform float uParallax;
            uniform float uGamma;

            uniform sampler2D uDepthTex;

            out vec2 vUv;

            void main() {
                vec2 uv = aPos * 0.5 + 0.5;
                float d = texture(uDepthTex, uv).r;
                d = pow(clamp(d, 0.0, 1.0), uGamma);

                vUv = uv;

                vec2 p = aPos * uScale + uOffset;
                p += uCam * d * uParallax;

                gl_Position = vec4(p, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """#version 300 es
            precision highp float;

            in vec2 vUv;
            uniform sampler2D uImageTex;
            uniform float uVignette;
            out vec4 fragColor;

            void main() {
                vec3 col = texture(uImageTex, vUv).rgb;
                float vig = smoothstep(1.30, 0.55, length(vUv - 0.5));
                col *= mix(1.0 - uVignette, 1.0, vig);
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
