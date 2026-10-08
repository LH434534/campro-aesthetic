package com.thiairo.campro;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Viewfinder em OpenGL ES 2.0. A camera escreve numa textura OES e o
 * fragment shader aplica o filtro por GPU - preview a 60fps sem tocar
 * na CPU. Nenhuma dependencia externa: GLES20 e parte do framework.
 */
public class GLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface Ready {
        void onSurfaceTexture(SurfaceTexture st, int w, int h);
    }

    private static final String VERT =
        "attribute vec2 aPos;" +
        "attribute vec2 aTex;" +
        "varying vec2 vTex;" +
        "uniform mat4 uTexMatrix;" +
        "uniform mat4 uMVP;" +
        "void main(){" +
        "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;" +
        "  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);" +
        "}";

    private static final String FRAG =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;" +
        "uniform samplerExternalOES uTexture;" +
        "varying vec2 vTex;" +
        "uniform mat3 uMatriz;" +
        "uniform vec3 uLift;" +
        "uniform vec3 uGain;" +
        "uniform vec3 uGammaInv;" +
        "uniform float uContraste;" +
        "uniform float uVinheta;" +
        "uniform float uGrao;" +
        "uniform float uSemente;" +
        "uniform float uFade;" +
        "float hash(vec2 p){" +
        "  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);" +
        "}" +
        "void main(){" +
        "  vec3 c = texture2D(uTexture, vTex).rgb;" +
        "  c = uMatriz * c;" +
        "  c = uLift + c * (uGain - uLift);" +
        "  c = pow(max(c, vec3(0.0)), uGammaInv);" +
        "  c = (c - 0.5) * uContraste + 0.5;" +
        "  vec2 d = vTex - vec2(0.5);" +
        "  float v = 1.0 - uVinheta * dot(d, d) * 2.6;" +
        "  c *= clamp(v, 0.0, 1.0);" +
        "  float g = (hash(vTex * 512.0 + uSemente) - 0.5) * uGrao;" +
        "  c += g;" +
        "  c = mix(c, vec3(0.976, 0.960, 0.937), uFade);" +
        "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);" +
        "}";

    private static final float[] POS = { -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f };
    private static final float[] TEX = {  0f,  1f,  1f,  1f,   0f, 0f,  1f, 0f };

    private final Ready ready;
    private FloatBuffer posBuf, texBuf;

    private int programa = 0;
    private int uTexMatrix, uMVP, uMatriz, uLift, uGain, uGammaInv;
    private int uContraste, uVinheta, uGrao, uSemente, uFade, uTexture;
    private int aPos, aTex;

    private int texOes = 0;
    private SurfaceTexture superficie;
    private final float[] texMatrix = new float[16];
    private final float[] mvp = new float[16];

    private volatile boolean novoQuadro = false;
    private volatile FilmFilter filtro = FilmFilter.TODOS[0];
    private volatile float fade = 0f;
    private float semente = 0f;

    private int viewW = 1, viewH = 1;
    private int sensorW = 4, sensorH = 3;

    public GLRenderer(Ready r) { this.ready = r; }

    public void setFiltro(FilmFilter f) { filtro = (f == null) ? FilmFilter.TODOS[0] : f; }

    /** Pode devolver null antes de onSurfaceCreated. */
    public SurfaceTexture getSurfaceTexture() { return superficie; }
    public void setFade(float f) { fade = f; }
    public void setAspecto(int sw, int sh) { if (sw > 0 && sh > 0) { sensorW = sw; sensorH = sh; } }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        posBuf = ByteBuffer.allocateDirect(POS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        posBuf.put(POS).position(0);
        texBuf = ByteBuffer.allocateDirect(TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuf.put(TEX).position(0);

        programa = criarPrograma(VERT, FRAG);
        if (programa == 0) return;

        aPos = GLES20.glGetAttribLocation(programa, "aPos");
        aTex = GLES20.glGetAttribLocation(programa, "aTex");
        uTexMatrix = GLES20.glGetUniformLocation(programa, "uTexMatrix");
        uMVP = GLES20.glGetUniformLocation(programa, "uMVP");
        uMatriz = GLES20.glGetUniformLocation(programa, "uMatriz");
        uLift = GLES20.glGetUniformLocation(programa, "uLift");
        uGain = GLES20.glGetUniformLocation(programa, "uGain");
        uGammaInv = GLES20.glGetUniformLocation(programa, "uGammaInv");
        uContraste = GLES20.glGetUniformLocation(programa, "uContraste");
        uVinheta = GLES20.glGetUniformLocation(programa, "uVinheta");
        uGrao = GLES20.glGetUniformLocation(programa, "uGrao");
        uSemente = GLES20.glGetUniformLocation(programa, "uSemente");
        uFade = GLES20.glGetUniformLocation(programa, "uFade");
        uTexture = GLES20.glGetUniformLocation(programa, "uTexture");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texOes = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        superficie = new SurfaceTexture(texOes);
        superficie.setOnFrameAvailableListener(this);
        if (ready != null) ready.onSurfaceTexture(superficie, 0, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        viewW = Math.max(w, 1); viewH = Math.max(h, 1);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (programa == 0 || superficie == null) return;

        if (novoQuadro) {
            try { superficie.updateTexImage(); } catch (Exception e) { return; }
            superficie.getTransformMatrix(texMatrix);
            novoQuadro = false;
        }

        semente += 0.0137f;
        if (semente > 1000f) semente -= 1000f;

        // letterbox respeitando a proporcao do sensor
        float escalaSensor = sensorW / (float) sensorH;
        float escalaView = viewW / (float) viewH;
        float sx = 1f, sy = 1f;
        if (escalaSensor > escalaView) sy = escalaView / escalaSensor;
        else sx = escalaSensor / escalaView;
        for (int i = 0; i < 16; i++) mvp[i] = 0f;
        mvp[0] = sx; mvp[5] = sy; mvp[10] = 1f; mvp[15] = 1f;

        FilmFilter f = filtro;

        GLES20.glClearColor(0.988f, 0.973f, 0.945f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(programa);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix3fv(uMatriz, 1, false, f.matriz(), 0);
        GLES20.glUniform3f(uLift, f.lift, f.lift, f.lift);
        GLES20.glUniform3f(uGain, f.ganho, f.ganho, f.ganho);
        float gi = 1f / Math.max(f.gama, 0.05f);
        GLES20.glUniform3f(uGammaInv, gi, gi, gi);
        GLES20.glUniform1f(uContraste, f.contraste);
        GLES20.glUniform1f(uVinheta, f.vinheta);
        GLES20.glUniform1f(uGrao, f.grao * 0.22f);
        GLES20.glUniform1f(uSemente, semente);
        GLES20.glUniform1f(uFade, fade);

        GLES20.glEnableVertexAttribArray(aPos);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(aTex);
        texBuf.position(0);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) { novoQuadro = true; }

    private static int criarPrograma(String vs, String fs) {
        int v = compilar(GLES20.GL_VERTEX_SHADER, vs);
        int f = compilar(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compilar(int tipo, String src) {
        int s = GLES20.glCreateShader(tipo);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
