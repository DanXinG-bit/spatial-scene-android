package com.danxing.spatialscene.gl

import android.opengl.GLES30
import com.danxing.spatialscene.wallpaper.SceneShaders

/**
 * M0 的程序化测试场景程序（无场景时的兜底画面）。
 */
class ProceduralProgram {

    private val program = GlCore.compile(SceneShaders.VERTEX, SceneShaders.FRAGMENT)

    private val uZoom = GLES30.glGetUniformLocation(program, "uZoom")
    private val uCam = GLES30.glGetUniformLocation(program, "uCam")
    private val uParallax = GLES30.glGetUniformLocation(program, "uParallax")
    private val uRoll = GLES30.glGetUniformLocation(program, "uRoll")

    fun draw(grid: GlCore.Grid, camX: Float, camY: Float, roll: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uZoom, 1.10f)
        GLES30.glUniform2f(uCam, camX, camY)
        GLES30.glUniform1f(uParallax, 1f)
        GLES30.glUniform1f(uRoll, roll)

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
}
