package com.custom.a7filmcam;

import android.app.Activity;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.provider.AvindexStore;
import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FilmCameraActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceHolder surfaceHolder;
    private CameraEx cameraEx;
    private TextView hudTextView;

    private final FilmProcessor filmProcessor = new FilmProcessor();
    private int currentPreset = FilmProcessor.PRESET_KODAK_GOLD;
    private boolean dateStampEnabled = true;
    private boolean isProcessing = false;

    private final String[] presetNames = {
        "KODAK GOLD 200",
        "FUJI CLASSIC NEG",
        "CINESTILL 800T"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_film_camera);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        hudTextView = (TextView) findViewById(R.id.hudText);

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        updateHud();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraEx = CameraEx.open(0, null);
        surfaceHolder.addCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraEx != null) {
            cameraEx.getNormalCamera().stopPreview();
            cameraEx.release();
            cameraEx = null;
        }
        surfaceHolder.removeCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            Camera normalCam = cameraEx.getNormalCamera();
            normalCam.setPreviewDisplay(holder);
            normalCam.startPreview();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();

        if (scanCode == ScalarInput.IS_USER_KEY_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            currentPreset = (currentPreset + presetNames.length - 1) % presetNames.length;
            updateHud();
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            currentPreset = (currentPreset + 1) % presetNames.length;
            updateHud();
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            dateStampEnabled = !dateStampEnabled;
            updateHud();
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_S1_ON) {
            if (!isProcessing && cameraEx != null) {
                cameraEx.getNormalCamera().autoFocus(null);
            }
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_S2_ON) {
            takeFilmPicture();
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.IS_USER_KEY_S1_OFF) {
            if (!isProcessing && cameraEx != null) {
                cameraEx.getNormalCamera().cancelAutoFocus();
            }
            return true;
        } else if (scanCode == ScalarInput.IS_USER_KEY_S2_OFF) {
            if (cameraEx != null) {
                cameraEx.cancelTakePicture();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void takeFilmPicture() {
        if (isProcessing || cameraEx == null) return;
        isProcessing = true;
        hudTextView.setText("DEVELOPING FILM...");

        cameraEx.getNormalCamera().takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(final byte[] data, final Camera camera) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap filmBitmap = filmProcessor.processFilmLook(data, currentPreset, dateStampEnabled);
                        if (filmBitmap != null) {
                            saveToSdCard(filmBitmap);
                            filmBitmap.recycle();
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                cameraEx.cancelTakePicture();
                                camera.startPreview();
                                isProcessing = false;
                                updateHud();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void saveToSdCard(Bitmap bitmap) {
        File dcimDir = new File(Environment.getExternalStorageDirectory(), "DCIM/100MSDCF");
        if (!dcimDir.exists()) dcimDir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outFile = new File(dcimDir, "FLM_" + timeStamp + ".JPG");

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos);
            fos.flush();

            String[] mediaIds = AvindexStore.getExternalMediaIds();
            if (mediaIds != null && mediaIds.length > 0) {
                AvindexStore.Images.Media.waitAndUpdateDatabase(getContentResolver(), mediaIds[0]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void updateHud() {
        String stampStatus = dateStampEnabled ? "[DATE ON]" : "[DATE OFF]";
        hudTextView.setText("FILM: " + presetNames[currentPreset] + "  " + stampStatus);
    }
}
