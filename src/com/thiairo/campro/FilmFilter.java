package com.thiairo.campro;

/**
 * Simulacoes de filme. Cada filtro e um conjunto de parametros numericos,
 * nao um lookup table - o mesmo conjunto alimenta o shader GLSL (preview)
 * e o processador CPU (captura full-res). Uma fonte de verdade, dois caminhos.
 */
public class FilmFilter {

    public final String nome;
    public final float saturacao;   // 0 = mono, 1 = neutro, >1 = vivo
    public final float contraste;   // 1 = neutro
    public final float calor;       // -1 frio .. +1 quente
    public final float tintura;     // -1 verde .. +1 magenta
    public final float lift;        // eleva sombras (fade)
    public final float ganho;       // escala altas luzes
    public final float gama;        // <1 clareia meio-tom
    public final float vinheta;     // 0..1
    public final float grao;        // 0..1

    public FilmFilter(String nome, float s, float c, float w, float t,
                      float l, float g, float gm, float v, float gr) {
        this.nome = nome; this.saturacao = s; this.contraste = c; this.calor = w;
        this.tintura = t; this.lift = l; this.ganho = g; this.gama = gm;
        this.vinheta = v; this.grao = gr;
    }

    public static final FilmFilter[] TODOS = new FilmFilter[] {
        new FilmFilter("Original", 1.00f, 1.00f, 0.00f, 0.00f, 0.000f, 1.00f, 1.00f, 0.00f, 0.000f),
        new FilmFilter("Creme",    0.92f, 0.94f, 0.35f, 0.06f, 0.045f, 0.98f, 0.92f, 0.16f, 0.020f),
        new FilmFilter("Filme",    1.06f, 1.08f, 0.14f, -0.08f, 0.020f, 1.02f, 1.04f, 0.22f, 0.035f),
        new FilmFilter("Mono",     0.00f, 1.10f, 0.10f, 0.00f, 0.015f, 1.00f, 1.00f, 0.26f, 0.045f),
        new FilmFilter("Fade",     0.74f, 0.88f, 0.22f, 0.04f, 0.085f, 0.94f, 0.90f, 0.14f, 0.028f),
        new FilmFilter("Vintage",  0.80f, 1.04f, 0.48f, 0.12f, 0.070f, 0.96f, 0.96f, 0.40f, 0.055f),
        new FilmFilter("Frio",     0.90f, 1.02f, -0.38f, -0.05f, 0.030f, 1.00f, 0.98f, 0.18f, 0.022f),
        new FilmFilter("Rosa",     0.96f, 0.92f, 0.28f, 0.30f, 0.060f, 0.99f, 0.88f, 0.20f, 0.026f),
    };

    /**
     * Matriz de cor 3x3 em ordem de coluna (como o GLSL espera).
     * Combina saturacao (em torno da luminancia) com balanco de canal.
     */
    public float[] matriz() {
        float lr = 0.2126f, lg = 0.7152f, lb = 0.0722f;
        float s = saturacao;
        float m00 = lr + (1f - lr) * s, m01 = lg - lg * s, m02 = lb - lb * s;
        float m10 = lr - lr * s,        m11 = lg + (1f - lg) * s, m12 = lb - lb * s;
        float m20 = lr - lr * s,        m21 = lg - lg * s,        m22 = lb + (1f - lb) * s;

        float wr = 1f + calor * 0.16f;
        float wb = 1f - calor * 0.16f;
        float wg = 1f + tintura * 0.10f;

        return new float[] {
            m00 * wr, m10 * wg, m20 * wb,
            m01 * wr, m11 * wg, m21 * wb,
            m02 * wr, m12 * wg, m22 * wb,
        };
    }
}
