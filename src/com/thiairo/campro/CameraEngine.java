package com.thiairo.campro;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Camera2 puro. Controles manuais sao consultados nas caracteristicas do
 * dispositivo antes de serem oferecidos - nunca prometemos o que o
 * hardware nao tem.
 */
@TargetApi(21)
public class CameraEngine {

    public interface Listener {
        void onAberta(Caps c);
        void onErro(String msg);
        void onJpeg(byte[] dados);
        void onFoco(boolean travado);
    }

    public static class Caps {
        public int isoMax = 1600;
        public long expMin = 1000000L / 8000L;
        public long expMax = 1000000000L / 30L;
        public float focoMin = 0f;
        public float focoMax = 10f;
        public int evMin = -6, evMax = 6;
        public boolean manual = false;
        public boolean focoManual = false;
        public boolean flash = false;
        public int nivel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        public int sensorW = 4032, sensorH = 3024;
    }

    public final Caps caps = new Caps();

    private static final CameraCaptureSession.CaptureCallback SEM_RETORNO =
        new CameraCaptureSession.CaptureCallback() { };

    private final Context ctx;
    private final Listener l;
    private CameraDevice device;
    private CameraCaptureSession sessao;
    private ImageReader leitorJpeg;
    private HandlerThread fio;
    private Handler h;
    private String cameraId;
    private Size tamanhoFoto = new Size(1920, 1440);

    private boolean manualAtivo = false;
    private int iso = 400;
    private long exposicao = 1000000000L / 125L;
    private float foco = 2.0f;
    private int ev = 0;
    private int modoFlash = CameraMetadata.FLASH_MODE_OFF;
    private MeteringRectangle areaFoco;

    public CameraEngine(Context c, Listener l) { this.ctx = c; this.l = l; }

    public void iniciar() {
        fio = new HandlerThread("cam");
        fio.start();
        h = new Handler(fio.getLooper());
    }

    public void abrir(Surface preview) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) { falhar("Sem CameraManager"); return; }
        try {
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) { falhar("Nenhuma camera"); return; }
            cameraId = ids[0];
            for (String id : ids) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer face = cc.get(CameraCharacteristics.LENS_FACING);
                if (face != null && face == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
            }
            lerCaps(cm.getCameraCharacteristics(cameraId));

            try { cm.openCamera(cameraId, estado, h); }
            catch (SecurityException e) { falhar("Permissao de camera negada"); return; }

            abrirPendente = preview;
            alvoPreview = preview;
        } catch (CameraAccessException e) {
            falhar("Acesso a camera falhou: " + e.getMessage());
        } catch (Exception e) {
            falhar("Falha ao abrir: " + e.getMessage());
        }
    }

    private Surface abrirPendente;

    private void lerCaps(CameraCharacteristics cc) {
        Integer nivel = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (nivel != null) caps.nivel = nivel;
        caps.manual = caps.nivel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                   || caps.nivel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3
                   || caps.nivel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;

        Range<Integer> rIso = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (rIso != null) caps.isoMax = rIso.getUpper();

        Range<Long> rExp = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (rExp != null) { caps.expMin = rExp.getLower(); caps.expMax = rExp.getUpper(); }
        if (caps.expMax > 1000000000L) caps.expMax = 1000000000L;

        Float fMin = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Float fMax = cc.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
        if (fMin != null) caps.focoMin = fMin;
        if (fMax != null && fMax > 0) caps.focoMax = Math.min(fMax, 20f);
        caps.focoManual = caps.focoMax > caps.focoMin + 0.05f;

        Range<Integer> rEv = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (rEv != null) { caps.evMin = rEv.getLower(); caps.evMax = rEv.getUpper(); }

        Boolean disp = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        caps.flash = disp != null && disp;

        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] fotos = map.getOutputSizes(ImageFormat.JPEG);
            if (fotos != null && fotos.length > 0) {
                Arrays.sort(fotos, new java.util.Comparator<Size>() {
                    public int compare(Size a, Size b) {
                        return (b.getWidth() * b.getHeight()) - (a.getWidth() * a.getHeight());
                    }
                });
                tamanhoFoto = fotos[0];
                caps.sensorW = fotos[0].getWidth();
                caps.sensorH = fotos[0].getHeight();
            }
        }
    }

    private final CameraDevice.StateCallback estado = new CameraDevice.StateCallback() {
        public void onOpened(CameraDevice d) {
            device = d;
            if (l != null) l.onAberta(caps);
            if (abrirPendente != null) criarSessao(abrirPendente);
        }
        public void onDisconnected(CameraDevice d) { fechar(); }
        public void onError(CameraDevice d, int e) { falhar("Erro de camera: " + e); }
    };

    private void criarSessao(final Surface preview) {
        leitorJpeg = ImageReader.newInstance(tamanhoFoto.getWidth(), tamanhoFoto.getHeight(),
                ImageFormat.JPEG, 3);
        leitorJpeg.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    ByteBuffer b = img.getPlanes()[0].getBuffer();
                    byte[] dados = new byte[b.remaining()];
                    b.get(dados);
                    if (l != null) l.onJpeg(dados);
                } catch (Exception e) {
                    falhar("Falha ao ler imagem: " + e.getMessage());
                } finally {
                    if (img != null) img.close();
                }
            }
        }, h);

        try {
            device.createCaptureSession(Arrays.asList(preview, leitorJpeg.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) {
                        sessao = s;
                        repetir();
                    }
                    public void onConfigureFailed(CameraCaptureSession s) { falhar("Sessao nao configurada"); }
                }, h);
        } catch (CameraAccessException e) {
            falhar("Sessao falhou: " + e.getMessage());
        } catch (Exception e) {
            falhar("Sessao falhou: " + e.getMessage());
        }
    }

    private void repetir() {
        if (sessao == null || device == null) return;
        try {
            CaptureRequest.Builder b = construir(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(alvoPreview != null ? alvoPreview : leitorJpeg.getSurface());
            sessao.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Integer af = res.get(CaptureResult.CONTROL_AF_STATE);
                    if (l != null && af != null) {
                        l.onFoco(af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                              || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED);
                    }
                }
            }, h);
        } catch (CameraAccessException e) {
            falhar("Preview falhou: " + e.getMessage());
        } catch (Exception e) {
            falhar("Preview falhou: " + e.getMessage());
        }
    }

    private Surface alvoPreview;

    public void definirAlvoPreview(Surface s) { alvoPreview = s; }

    private CaptureRequest.Builder construir(int template) throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(template);
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        if (manualAtivo && caps.manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(100, Math.min(iso, caps.isoMax)));
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    Math.max(caps.expMin, Math.min(exposicao, caps.expMax)));
            if (caps.focoManual) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(caps.focoMin, Math.min(foco, caps.focoMax)));
            }
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(caps.evMin, Math.min(ev, caps.evMax)));
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }

        if (areaFoco != null) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] { areaFoco });
            b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] { areaFoco });
        }
        if (caps.flash) b.set(CaptureRequest.FLASH_MODE, modoFlash);

        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);
        return b;
    }

    public void capturar() {
        if (sessao == null || device == null || leitorJpeg == null) { falhar("Camera nao pronta"); return; }
        try {
            CaptureRequest.Builder b = construir(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(leitorJpeg.getSurface());
            if (alvoPreview != null) b.addTarget(alvoPreview);
            b.set(CaptureRequest.JPEG_ORIENTATION, 0);
            sessao.capture(b.build(), SEM_RETORNO, h);
        } catch (CameraAccessException e) {
            falhar("Captura falhou: " + e.getMessage());
        } catch (Exception e) {
            falhar("Captura falhou: " + e.getMessage());
        }
    }

    public void focarEm(int x, int y, int w, int h) {
        int lado = Math.max(60, Math.min(w, h) / 6);
        areaFoco = new MeteringRectangle(
                Math.max(0, x - lado / 2), Math.max(0, y - lado / 2),
                lado, lado, MeteringRectangle.METERING_WEIGHT_MAX - 1);
        try {
            CaptureRequest.Builder b = construir(CameraDevice.TEMPLATE_PREVIEW);
            if (alvoPreview != null) b.addTarget(alvoPreview);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            sessao.capture(b.build(), SEM_RETORNO, h);
        } catch (Exception e) { /* trigger opcional */ }
        repetir();
    }

    public void setManual(boolean on) { manualAtivo = on; repetir(); }
    public void setIso(int v) { iso = v; repetir(); }
    public void setExposicao(long ns) { exposicao = ns; repetir(); }
    public void setFoco(float d) { foco = d; repetir(); }
    public void setEv(int v) { ev = v; repetir(); }
    public void setFlash(int m) { modoFlash = m; repetir(); }
    public int getIso() { return iso; }
    public long getExposicao() { return exposicao; }
    public float getFoco() { return foco; }
    public int getEv() { return ev; }

    private void falhar(String m) { if (l != null) l.onErro(m); }

    public void fechar() {
        try { if (sessao != null) sessao.close(); } catch (Exception e) { /* noop */ }
        try { if (device != null) device.close(); } catch (Exception e) { /* noop */ }
        try { if (leitorJpeg != null) leitorJpeg.close(); } catch (Exception e) { /* noop */ }
        try { if (fio != null) fio.quitSafely(); } catch (Exception e) { /* noop */ }
        sessao = null; device = null; leitorJpeg = null;
    }
}
