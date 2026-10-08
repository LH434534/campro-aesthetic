package com.thiairo.campro;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CamPro - interface construida inteiramente em codigo para controle total
 * do desenho. Paleta quente, espaco generoso, tipografia com respiro.
 */
public class MainActivity extends Activity implements CameraEngine.Listener {

    // ---- paleta ----
    static final int CREME   = 0xFFFDFBF7;
    static final int CREME_2 = 0xFFF5EFE6;
    static final int TINTA   = 0xFF2C2723;
    static final int SUAVE   = 0xFF8C8378;
    static final int LINHA   = 0xFFE3DACE;
    static final int ACENTO  = 0xFFC4714F;
    static final int VIDRO   = 0xD9FDFBF7;

    private GLSurfaceView gl;
    private GLRenderer renderer;
    private CameraEngine cam;

    private FrameLayout raiz;
    private LinearLayout barraTopo, painelInferior, painelPro;
    private HorizontalScrollView tiraFiltros;
    private LinearLayout linhaFiltros;
    private ShutterButton obturador;
    private ImageView miniGaleria;
    private TextView txtStatus;
    private View retGrade, retFoco;
    private Histograma histograma;

    private int filtroAtual = 0;
    private boolean proAtivo = false;
    private boolean gradeAtiva = false;
    private int modoFlash = 0;
    private String ultimoCaminho;

    private final long[] VELOCIDADES = {
        1000000000L/8000, 1000000000L/4000, 1000000000L/2000, 1000000000L/1000,
        1000000000L/500, 1000000000L/250, 1000000000L/125, 1000000000L/60,
        1000000000L/30, 1000000000L/15, 1000000000L/8, 1000000000L/4,
    };
    private final int[] ISOS = { 50, 100, 200, 400, 800, 1600, 3200, 6400 };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(CREME);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(CREME);
        setContentView(raiz);
        montar();
        pedirPermissao();
    }

    // ---------------- montagem da interface ----------------

    private void montar() {
        renderer = new GLRenderer(new GLRenderer.Ready() {
            public void onSurfaceTexture(android.graphics.SurfaceTexture st, int w, int h) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (cam == null) {
                            cam = new CameraEngine(MainActivity.this, MainActivity.this);
                            cam.iniciar();
                        }
                    }
                });
            }
        });
        renderer.setFiltro(FilmFilter.TODOS[0]);

        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        raiz.addView(gl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // textura pronta -> abrir camera na mesma surface
        gl.post(new Runnable() {
            public void run() {
                gl.queueEvent(new Runnable() {
                    public void run() { /* a textura nasce em onSurfaceCreated */ }
                });
            }
        });

        retGrade = new GradeView(this);
        retGrade.setVisibility(View.GONE);
        raiz.addView(retGrade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        retFoco = new FocoView(this);
        retFoco.setVisibility(View.GONE);
        raiz.addView(retFoco, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        histograma = new Histograma(this);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(dp(120), dp(44));
        lh.gravity = Gravity.TOP | Gravity.END;
        lh.setMargins(0, dp(74), dp(16), 0);
        histograma.setLayoutParams(lh);
        raiz.addView(histograma);

        barraTopo = barraSuperior();
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lt.gravity = Gravity.TOP;
        raiz.addView(barraTopo, lt);

        painelInferior = painelBase();
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lb.gravity = Gravity.BOTTOM;
        raiz.addView(painelInferior, lb);

        gl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* toque tratado em GradeView */ }
        });
    }

    private LinearLayout barraSuperior() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(18), dp(30), dp(18), dp(12));
        l.setBackground(fundoSuperior());

        TextView marca = rotulo("CAMPRO", 13f, 0.24f);
        marca.setTextColor(TINTA);
        l.addView(marca);

        View esp = new View(this);
        l.addView(esp, new LinearLayout.LayoutParams(0, 1, 1f));

        l.addView(pilula("FLASH", new Runnable() { public void run() { alternarFlash(); } }));
        l.addView(espaco(dp(8)));
        l.addView(pilula("GRADE", new Runnable() { public void run() {
            gradeAtiva = !gradeAtiva;
            retGrade.setVisibility(gradeAtiva ? View.VISIBLE : View.GONE);
        }}));
        l.addView(espaco(dp(8)));
        l.addView(pilula("PRO", new Runnable() { public void run() { alternarPro(); } }));
        return l;
    }

    private LinearLayout painelBase() {
        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        base.setBackground(fundoInferior());
        base.setPadding(0, dp(10), 0, dp(26));

        painelPro = painelPro();
        painelPro.setVisibility(View.GONE);
        base.addView(painelPro);

        tiraFiltros = new HorizontalScrollView(this);
        tiraFiltros.setHorizontalScrollBarEnabled(false);
        tiraFiltros.setOverScrollMode(View.OVER_SCROLL_NEVER);
        linhaFiltros = new LinearLayout(this);
        linhaFiltros.setOrientation(LinearLayout.HORIZONTAL);
        linhaFiltros.setPadding(dp(16), dp(4), dp(16), dp(10));
        for (int i = 0; i < FilmFilter.TODOS.length; i++) {
            linhaFiltros.addView(chipFiltro(i));
        }
        tiraFiltros.addView(linhaFiltros);
        base.addView(tiraFiltros);

        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER_VERTICAL);
        linha.setPadding(dp(26), dp(6), dp(26), 0);

        miniGaleria = new ImageView(this);
        miniGaleria.setImageDrawable(cantos(dp(42), dp(42), CREME_2, 14));
        miniGaleria.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniGaleria.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ultimoCaminho != null) {
                    Toast.makeText(MainActivity.this, ultimoCaminho, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Nenhuma foto ainda", Toast.LENGTH_SHORT).show();
                }
            }
        });
        linha.addView(miniGaleria);

        View e1 = new View(this);
        linha.addView(e1, new LinearLayout.LayoutParams(0, 1, 1f));

        obturador = new ShutterButton(this);
        obturador.setAcao(new ShutterButton.Acao() {
            public void disparar() {
                if (cam != null) cam.capturar();
            }
        });
        linha.addView(obturador);

        View e2 = new View(this);
        linha.addView(e2, new LinearLayout.LayoutParams(0, 1, 1f));

        View inversor = caixa(dp(42), dp(42), CREME_2, 21);
        inversor.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Sensor traseiro", Toast.LENGTH_SHORT).show();
            }
        });
        linha.addView(inversor);

        base.addView(linha);

        txtStatus = rotulo("PRONTO", 9.5f, 0.18f);
        txtStatus.setTextColor(SUAVE);
        txtStatus.setGravity(Gravity.CENTER);
        txtStatus.setPadding(0, dp(10), 0, 0);
        base.addView(txtStatus);

        return base;
    }

    private LinearLayout painelPro() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(6), dp(16), dp(6));

        p.addView(linhaControle("ISO", new String[] { "50", "100", "200", "400", "800", "1600", "3200", "6400" },
            new AoEscolher() { public void escolher(int i) { if (cam != null) cam.setIso(ISOS[i]); } }));
        p.addView(linhaControle("VEL", new String[] { "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250",
                "1/125", "1/60", "1/30", "1/15", "1/8", "1/4" },
            new AoEscolher() { public void escolher(int i) { if (cam != null) cam.setExposicao(VELOCIDADES[i]); } }));
        p.addView(linhaControle("EXP", new String[] { "-3", "-2", "-1", "0", "+1", "+2", "+3" },
            new AoEscolher() { public void escolher(int i) { if (cam != null) cam.setEv(i - 3); } }));
        p.addView(linhaControle("FOCO", new String[] { "0.3", "0.5", "1", "2", "3", "5", "10", "∞" },
            new AoEscolher() { public void escolher(int i) {
                if (cam == null) return;
                float d = i == 7 ? 20f : Float.parseFloat(new String[] { "0.3","0.5","1","2","3","5","10" }[i]);
                cam.setFoco(d);
            }}));
        return p;
    }

    interface AoEscolher { void escolher(int indice); }

    private LinearLayout linhaControle(final String nome, final String[] valores, final AoEscolher cb) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(3), 0, dp(3));

        TextView t = rotulo(nome, 9f, 0.16f);
        t.setTextColor(SUAVE);
        t.setWidth(dp(38));
        l.addView(t);

        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] vistos = new TextView[valores.length];
        for (int i = 0; i < valores.length; i++) {
            final int idx = i;
            TextView v = rotulo(valores[i], 11.5f, 0.04f);
            v.setPadding(dp(11), dp(5), dp(11), dp(5));
            v.setBackground(cantos(dp(11), dp(11), CREME_2, 11));
            v.setTextColor(SUAVE);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    for (TextView o : vistos) {
                        o.setTextColor(SUAVE);
                        o.setBackground(cantos(dp(11), dp(11), CREME_2, 11));
                    }
                    v.setTextColor(TINTA);
                    GradientDrawable g = cantos(dp(11), dp(11), CREME_2, 11);
                    g.setStroke(Math.max(1, dp(1)), ACENTO);
                    v.setBackground(g);
                    cb.escolher(idx);
                }
            });
            vistos[i] = v;
            inner.addView(v);
            View sp = new View(this);
            inner.addView(sp, new LinearLayout.LayoutParams(dp(6), 1));
        }
        hs.addView(inner);
        l.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return l;
    }

    private TextView chipFiltro(final int idx) {
        final TextView t = rotulo(FilmFilter.TODOS[idx].nome.toUpperCase(Locale.US), 10f, 0.14f);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setTextColor(idx == 0 ? TINTA : SUAVE);
        GradientDrawable g = cantos(dp(14), dp(14), idx == 0 ? CREME_2 : Color.TRANSPARENT, 14);
        if (idx == 0) g.setStroke(Math.max(1, dp(1)), ACENTO);
        t.setBackground(g);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                filtroAtual = idx;
                renderer.setFiltro(FilmFilter.TODOS[idx]);
                for (int i = 0; i < linhaFiltros.getChildCount(); i++) {
                    TextView o = (TextView) linhaFiltros.getChildAt(i);
                    o.setTextColor(i == idx ? TINTA : SUAVE);
                    GradientDrawable gg = cantos(dp(14), dp(14),
                            i == idx ? CREME_2 : Color.TRANSPARENT, 14);
                    if (i == idx) gg.setStroke(Math.max(1, dp(1)), ACENTO);
                    o.setBackground(gg);
                }
            }
        });
        return t;
    }

    // ---------------- acoes ----------------

    private void alternarFlash() {
        modoFlash = (modoFlash + 1) % 3;
        if (cam != null) cam.setFlash(modoFlash == 0 ? 0 : (modoFlash == 1 ? 2 : 1));
        txtStatus.setText("FLASH " + (modoFlash == 0 ? "DESL" : modoFlash == 1 ? "AUTO" : "ON"));
    }

    private void alternarPro() {
        proAtivo = !proAtivo;
        painelPro.setVisibility(proAtivo ? View.VISIBLE : View.GONE);
        if (cam != null) cam.setManual(proAtivo);
        txtStatus.setText(proAtivo ? "MODO MANUAL" : "MODO AUTO");
    }

    private void pedirPermissao() {
        if (Build.VERSION.SDK_INT < 23) { abrirCamera(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            abrirCamera();
        } else {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 7);
        }
    }

    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (c == 7 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) abrirCamera();
        else txtStatus.setText("PERMISSAO NEGADA");
    }

    private void abrirCamera() {
        if (cam == null) {
            cam = new CameraEngine(this, this);
            cam.iniciar();
        }
        // a surface chega quando a textura GL existir
        aguardarTextura();
    }

    private void aguardarTextura() {
        gl.queueEvent(new Runnable() {
            public void run() {
                final android.graphics.SurfaceTexture st = renderer.getSurfaceTexture();
                if (st == null) {
                    gl.postDelayed(new Runnable() { public void run() { aguardarTextura(); } }, 60);
                    return;
                }
                st.setDefaultBufferSize(1280, 960);
                final Surface s = new Surface(st);
                runOnUiThread(new Runnable() {
                    public void run() { if (cam != null) cam.abrir(s); }
                });
            }
        });
    }

    // ---------------- CameraEngine.Listener ----------------

    public void onAberta(CameraEngine.Caps c) {
        renderer.setAspecto(c.sensorW, c.sensorH);
        runOnUiThread(new Runnable() {
            public void run() {
                txtStatus.setText(c.manual ? "MANUAL DISPONIVEL" : "AUTO");
            }
        });
    }

    public void onErro(final String m) {
        runOnUiThread(new Runnable() {
            public void run() { txtStatus.setText(m.toUpperCase(Locale.US)); }
        });
    }

    public void onFoco(boolean travado) { /* indicador visual opcional */ }

    public void onJpeg(byte[] dados) {
        new Thread(new Runnable() {
            public void run() {
                processar(dados);
            }
        }).start();
    }

    private void processar(byte[] dados) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(dados, 0, dados.length);
            if (bmp == null) { falharNaThread("Falha ao decodificar"); return; }
            Bitmap out = ImageProcessor.aplicar(bmp, FilmFilter.TODOS[filtroAtual]);

            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getFilesDir();
            File pasta = new File(dir, "CamPro");
            if (!pasta.exists() && !pasta.mkdirs()) { falharNaThread("Sem pasta"); return; }

            String nome = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            File arq = new File(pasta, nome);
            FileOutputStream fo = new FileOutputStream(arq);
            out.compress(Bitmap.CompressFormat.JPEG, 94, fo);
            fo.flush();
            fo.close();
            ultimoCaminho = arq.getAbsolutePath();

            final Bitmap mini = Bitmap.createScaledBitmap(out, 96, 96, true);
            runOnUiThread(new Runnable() {
                public void run() {
                    miniGaleria.setImageBitmap(mini);
                    txtStatus.setText("SALVO");
                    obturador.setGravando(false);
                }
            });
        } catch (Exception e) {
            falharNaThread(e.getMessage() == null ? "Erro" : e.getMessage());
        }
    }

    private void falharNaThread(final String m) {
        runOnUiThread(new Runnable() {
            public void run() {
                txtStatus.setText(m.toUpperCase(Locale.US));
                obturador.setGravando(false);
            }
        });
    }

    // ---------------- desenho ----------------

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    TextView rotulo(String txt, float sp, float espaco) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(espaco);
        t.setAllCaps(false);
        t.setPadding(0, 0, 0, 0);
        return t;
    }

    View espaco(int px) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(px, 1)); return v; }

    GradientDrawable cantos(int w, int h, int cor, float raio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(raio));
        return g;
    }

    View caixa(int w, int h, int cor, float raio) {
        View v = new View(this);
        v.setBackground(cantos(w, h, cor, raio));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return v;
    }

    View pilula(String txt, final Runnable acao) {
        TextView t = rotulo(txt, 9.5f, 0.16f);
        t.setTextColor(SUAVE);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = cantos(0, 0, VIDRO, 12);
        g.setStroke(Math.max(1, dp(1)), LINHA);
        t.setBackground(g);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { acao.run(); }
        });
        return t;
    }

    android.graphics.drawable.Drawable fundoSuperior() {
        ShapeDrawable d = new ShapeDrawable(new RoundRectShape(
                new float[] { 0, 0, 0, 0, 0, 0, 16, 16, 16, 16, 0, 0, 0, 0 }, null, null));
        d.getPaint().setShader(new LinearGradient(0, 0, 0, dp(90), 0xE6FDFBF7, 0x00FDFBF7, Shader.TileMode.CLAMP));
        return d;
    }

    android.graphics.drawable.Drawable fundoInferior() {
        ShapeDrawable d = new ShapeDrawable(new RoundRectShape(
                new float[] { 22, 22, 22, 22, 22, 22, 0, 0, 0, 0, 22, 22, 22, 22 }, null, null));
        d.getPaint().setShader(new LinearGradient(0, 0, 0, dp(260), 0x00FDFBF7, 0xF2FDFBF7, Shader.TileMode.CLAMP));
        return d;
    }

    // ---------------- visoes auxiliares ----------------

    class GradeView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GradeView(android.content.Context c) { super(c); p.setColor(0x55FFFFFF); p.setStrokeWidth(1f); }
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            for (int i = 1; i <= 2; i++) {
                float x = w * i / 3f; c.drawLine(x, 0, x, h, p);
                float y = h * i / 3f; c.drawLine(0, y, w, y, p);
            }
        }
    }

    class FocoView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x = -1, y = -1;
        FocoView(android.content.Context c) { super(c); p.setColor(0xFFFFFFFF); p.setStrokeWidth(2f); p.setStyle(Paint.Style.STROKE); }
        protected void onDraw(Canvas c) {
            if (x < 0) return;
            float r = dp(30);
            c.drawCircle(x, y, r, p);
            c.drawLine(x - r, y, x - r + dp(8), y, p);
            c.drawLine(x + r - dp(8), y, x + r, y, p);
            c.drawLine(x, y - r, x, y - r + dp(8), p);
            c.drawLine(x, y + r - dp(8), x, y + r, p);
        }
    }

    class Histograma extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] dados = new float[64];
        Histograma(android.content.Context c) {
            super(c);
            for (int i = 0; i < 64; i++) dados[i] = (float) (Math.sin(i / 64.0 * Math.PI) * 0.7);
        }
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float bw = w / 64f;
            p.setColor(0x33FFFFFF);
            RectF bg = new RectF(0, 0, w, h);
            c.drawRoundRect(bg, dp(6), dp(6), p);
            p.setColor(0x992C2723);
            for (int i = 0; i < 64; i++) {
                float bh = dados[i] * h * 0.8f;
                c.drawRect(i * bw, h - bh, (i + 1) * bw - 0.6f, h, p);
            }
        }
    }

    @Override
    protected void onResume() { super.onResume(); if (gl != null) gl.onResume(); }
    @Override
    protected void onPause() { super.onPause(); if (gl != null) gl.onPause(); if (cam != null) cam.fechar(); }
}
