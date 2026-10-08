package com.thiairo.campro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Obturador desenhado a mao: anel externo fino + circulo interno. */
public class ShutterButton extends View {

    public interface Acao { void disparar(); }

    private static final int CREME = 0xFFFDFBF7;
    private static final int TINTA = 0xFF2C2723;
    private static final int ACENTO = 0xFFC4714F;

    private final Paint pAnel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDisco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRisco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private float pressao = 0f;
    private boolean gravando = false;
    private Acao acao;

    public ShutterButton(Context c) {
        super(c);
        pAnel.setStyle(Paint.Style.STROKE);
        pAnel.setColor(ACENTO);
        pDisco.setStyle(Paint.Style.FILL);
        pDisco.setColor(TINTA);
        pRisco.setStyle(Paint.Style.STROKE);
        pRisco.setColor(CREME);
    }

    public void setAcao(Acao a) { acao = a; }
    public void setGravando(boolean g) { gravando = g; invalidate(); }

    public boolean onTouchEvent(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                pressao = 1f; invalidate(); return true;
            case android.view.MotionEvent.ACTION_UP:
                pressao = 0f; invalidate();
                if (acao != null) acao.disparar();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                pressao = 0f; invalidate(); return true;
        }
        return super.onTouchEvent(e);
    }

    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float raioExt = Math.min(w, h) / 2f - 3f;

        pAnel.setStrokeWidth(2.2f);
        pAnel.setAlpha(gravando ? 90 : 220);
        c.drawCircle(cx, cy, raioExt, pAnel);

        float raioInt = raioExt - 9f;
        raioInt *= (1f - pressao * 0.07f);

        if (gravando) {
            r.set(cx - raioInt, cy - raioInt, cx + raioInt, cy + raioInt);
            pDisco.setColor(ACENTO);
            c.drawRoundRect(r, raioInt * 0.42f, raioInt * 0.42f, pDisco);
        } else {
            pDisco.setColor(TINTA);
            c.drawCircle(cx, cy, raioInt, pDisco);
            pRisco.setStrokeWidth(1.6f);
            pRisco.setAlpha(140);
            c.drawCircle(cx, cy, raioInt - 6f, pRisco);
        }
    }

    protected void onMeasure(int w, int h) {
        int tam = Math.max(72, Math.min(resolve(android.view.View.MeasureSpec.getSize(w)), 96));
        setMeasuredDimension(tam, tam);
    }

    private int resolve(int s) { return s > 0 ? s : 84; }
}
