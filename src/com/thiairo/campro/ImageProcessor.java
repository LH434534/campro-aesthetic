package com.thiairo.campro;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Aplica o mesmo pipeline do shader no bitmap final.
 * Ordem: matriz de cor -> curva por canal (lift/gamma/gain) -> contraste
 *        -> vinheta -> grao.
 * A curva e resolvida em LUT de 256 por canal: o custo por pixel cai de
 * tres pow() para tres leituras de array.
 */
public final class ImageProcessor {

    public static Bitmap aplicar(Bitmap src, FilmFilter f) {
        if (f == null) return src;
        final int w = src.getWidth(), h = src.getHeight();
        final int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        final float[] m = f.matriz();
        final int[] lut = construirLut(f);

        final float cx = (w - 1) * 0.5f, cy = (h - 1) * 0.5f;
        final float maxD2 = cx * cx + cy * cy;
        final float graoAmp = f.grao * 26f;
        final float vinheta = f.vinheta;

        int semente = 0x2f6e2b1;
        for (int y = 0; y < h; y++) {
            final int linha = y * w;
            final float dy = y - cy;
            for (int x = 0; x < w; x++) {
                final int c = px[linha + x];
                float r = Color.red(c)   / 255f;
                float g = Color.green(c) / 255f;
                float b = Color.blue(c)  / 255f;

                float nr = m[0] * r + m[3] * g + m[6] * b;
                float ng = m[1] * r + m[4] * g + m[7] * b;
                float nb = m[2] * r + m[5] * g + m[8] * b;

                nr = lut[(int) (limitar(nr) * 255f)] / 255f;
                ng = lut[(int) (limitar(ng) * 255f)] / 255f;
                nb = lut[(int) (limitar(nb) * 255f)] / 255f;

                if (graoAmp > 0.01f) {
                    semente = semente * 1103515245 + 12345;
                    float n = ((semente >> 16) & 0x7fff) / 32767.5f - 0.5f;
                    float a = graoAmp * n;
                    nr += a; ng += a; nb += a;
                }

                if (vinheta > 0.001f) {
                    final float dx = x - cx;
                    final float d2 = (dx * dx + dy * dy) / maxD2;
                    float v = 1f - vinheta * d2 * d2 * 1.35f;
                    if (v < 0f) v = 0f;
                    nr *= v; ng *= v; nb *= v;
                }

                px[linha + x] = 0xFF000000
                    | ((int) (limitar(nr) * 255f) << 16)
                    | ((int) (limitar(ng) * 255f) << 8)
                    |  (int) (limitar(nb) * 255f);
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int[] construirLut(FilmFilter f) {
        final int[] lut = new int[256];
        final float lift = f.lift, ganho = f.ganho;
        final float gamaInv = 1f / Math.max(f.gama, 0.05f);
        final float contraste = f.contraste;
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            v = lift + v * (ganho - lift);
            if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
            v = (float) Math.pow(v, gamaInv);
            v = (v - 0.5f) * contraste + 0.5f;
            if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
            lut[i] = (int) (v * 255f + 0.5f);
        }
        return lut;
    }

    private static float limitar(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
