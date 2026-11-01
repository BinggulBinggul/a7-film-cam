package com.custom.a7filmcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class FilmProcessor {
    public static final int PRESET_KODAK_GOLD = 0;
    public static final int PRESET_CLASSIC_NEG = 1;
    public static final int PRESET_CINESTILL_800T = 2;

    private final int[] rLUT = new int[256];
    private final int[] gLUT = new int[256];
    private final int[] bLUT = new int[256];
    private final Random random = new Random();

    public Bitmap processFilmLook(byte[] jpegData, int preset, boolean addDateStamp) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2; // A7M1 메모리 한계(OOM) 방지를 위해 6MP 크기로 샘플링
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, opts);
        if (decoded == null) return null;

        Bitmap src = decoded.copy(Bitmap.Config.ARGB_8888, true);
        decoded.recycle();
        if (src == null) return null;

        buildLUT(preset);

        int width = src.getWidth();
        int height = src.getHeight();
        int[] linePixels = new int[width];

        Bitmap halationMap = createHalationLayer(src, width / 8, height / 8);
        int grainStrength = (preset == PRESET_CINESTILL_800T) ? 22 : 14;

        for (int y = 0; y < height; y++) {
            src.getPixels(linePixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = linePixels[x];
                int r = rLUT[(color >> 16) & 0xFF];
                int g = gLUT[(color >> 8) & 0xFF];
                int b = bLUT[color & 0xFF];

                if (preset == PRESET_CLASSIC_NEG) {
                    int luma = (r * 77 + g * 150 + b * 29) >> 8;
                    if (luma < 110) {
                        g = clamp(g + ((110 - luma) >> 4));
                        b = clamp(b + ((110 - luma) >> 3));
                    } else {
                        r = clamp(r + ((luma - 110) >> 4));
                    }
                }

                int luma = (r + g + b) / 3;
                float midWeight = 1.0f - Math.abs(luma - 128) / 128.0f;
                int noise = (int) ((random.nextInt(grainStrength * 2 + 1) - grainStrength) * midWeight);

                r = clamp(r + noise);
                g = clamp(g + noise);
                b = clamp(b + (int) (noise * 0.9f));

                linePixels[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            src.setPixels(linePixels, 0, width, 0, y, width, 1);
        }

        Canvas canvas = new Canvas(src);
        if (halationMap != null) {
            Paint halationPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            halationPaint.setAlpha(preset == PRESET_CINESTILL_800T ? 115 : 65);
            canvas.drawBitmap(halationMap, null, new Rect(0, 0, width, height), halationPaint);
            halationMap.recycle();
        }

        if (addDateStamp) {
            drawRetroDateStamp(canvas, width, height);
        }
        return src;
    }

    private void buildLUT(int preset) {
        for (int i = 0; i < 256; i++) {
            float norm = i / 255.0f;
            float curved = (float) (1.0 / (1.0 + Math.exp(-5.2 * (norm - 0.5))));
            curved = 0.06f + curved * 0.90f;
            int base = clamp((int) (curved * 255.0f));

            if (preset == PRESET_KODAK_GOLD) {
                rLUT[i] = clamp((int) (base * 1.08f + 4));
                gLUT[i] = clamp((int) (base * 1.02f + 2));
                bLUT[i] = clamp((int) (base * 0.88f));
            } else if (preset == PRESET_CLASSIC_NEG) {
                rLUT[i] = clamp((int) (base * 1.04f));
                gLUT[i] = clamp((int) (base * 0.98f));
                bLUT[i] = clamp((int) (base * 0.94f));
            } else {
                rLUT[i] = clamp((int) (base * 0.94f));
                gLUT[i] = clamp((int) (base * 1.01f));
                bLUT[i] = clamp((int) (base * 1.12f + 6));
            }
        }
    }

    private Bitmap createHalationLayer(Bitmap src, int smallW, int smallH) {
        Bitmap small = Bitmap.createScaledBitmap(src, smallW, smallH, true);
        int[] pixels = new int[smallW * smallH];
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int luma = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
            if (luma > 215) {
                int intensity = clamp((luma - 215) * 6);
                pixels[i] = (intensity << 24) | (255 << 16) | (65 << 8) | 20;
            } else {
                pixels[i] = 0x00000000;
            }
        }
        boxBlur(pixels, smallW, smallH);
        boxBlur(pixels, smallW, smallH);
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
        return small;
    }

    private void boxBlur(int[] pixels, int w, int h) {
        int[] temp = pixels.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int aSum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        aSum += (temp[(y + dy) * w + (x + dx)] >>> 24);
                    }
                }
                pixels[y * w + x] = ((aSum / 9) << 24) | (255 << 16) | (65 << 8) | 20;
            }
        }
    }

    private void drawRetroDateStamp(Canvas canvas, int width, int height) {
        String dateText = new SimpleDateFormat("''yy MM dd", Locale.US).format(new Date());
        float textSize = height * 0.038f;
        float x = width * 0.78f;
        float y = height * 0.93f;

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setTypeface(Typeface.MONOSPACE);
        glow.setTextSize(textSize);
        glow.setColor(Color.argb(180, 255, 60, 0));
        glow.setShadowLayer(textSize * 0.35f, 0, 0, Color.rgb(255, 40, 0));
        canvas.drawText(dateText, x, y, glow);

        Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        core.setTypeface(Typeface.MONOSPACE);
        core.setTextSize(textSize);
        core.setColor(Color.rgb(255, 165, 40));
        canvas.drawText(dateText, x, y, core);
    }

    private static int clamp(int val) {
        return val < 0 ? 0 : (val > 255 ? 255 : val);
    }
}
