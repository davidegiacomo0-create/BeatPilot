package it.dave.beatpilot;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Uploads copied RGBA frames to the codec surface, only on the recording thread. */
final class EncoderSurface implements AutoCloseable {
    static final String VERTEX = "attribute vec2 position; attribute vec2 texCoord; varying vec2 uv;"
            + "void main(){ gl_Position=vec4(position,0.0,1.0); uv=texCoord; }";
    static final String FRAGMENT = "precision mediump float; varying vec2 uv; uniform sampler2D image;"
            + "void main(){ gl_FragColor=vec4(texture2D(image,uv).rgb,1.0); }";
    // ImageReader row zero is the top row. Flip texture Y when drawing onto EGL's bottom-origin surface.
    static final float[] QUAD = {-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0};
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface target = EGL14.EGL_NO_SURFACE;
    private int program, texture, position, texCoord;
    private final int width, height;
    private final FloatBuffer vertices;

    EncoderSurface(Surface surface, int width, int height) {
        this.width = width; this.height = height;
        vertices = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(QUAD).position(0);
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1))
                throw new IllegalStateException("EGL non disponibile");
            int[] attributes = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw new IllegalStateException("Configurazione video EGL non disponibile");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
            target = EGL14.eglCreateWindowSurface(display, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || target == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, target, target, context))
                throw new IllegalStateException("Impossibile collegare la superficie video");
            int v = shader(GLES20.GL_VERTEX_SHADER, VERTEX), f = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f);
            GLES20.glLinkProgram(program); GLES20.glDeleteShader(v); GLES20.glDeleteShader(f);
            int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException("Shader video: " + GLES20.glGetProgramInfoLog(program));
            position = GLES20.glGetAttribLocation(program, "position"); texCoord = GLES20.glGetAttribLocation(program, "texCoord");
            int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); texture = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,
                    GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            checkGl();
        } catch (RuntimeException e) { close(); throw e; }
    }

    void draw(ByteBuffer rgba, long presentationNs) {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        rgba.position(0);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
        vertices.position(2); GLES20.glVertexAttribPointer(texCoord,2,GLES20.GL_FLOAT,false,16,vertices);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(texCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); checkGl();
        if (!EGLExt.eglPresentationTimeANDROID(display,target,presentationNs) || !EGL14.eglSwapBuffers(display,target))
            throw new IllegalStateException("Invio fotogramma all’encoder non riuscito");
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
            throw new IllegalStateException(message);
        }
        return shader;
    }
    private static void checkGl() {
        int error = GLES20.glGetError(); if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("OpenGL " + error);
    }
    @Override public void close() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        if (context != EGL14.EGL_NO_CONTEXT && target != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display,target,target,context);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (texture != 0) GLES20.glDeleteTextures(1,new int[]{texture},0);
        }
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,target);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY;
    }
}
