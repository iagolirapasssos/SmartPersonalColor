package com.iagolirapassos.smartpersonalcolor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.FaceDetector;
import android.media.FaceDetector.Face;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailList;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * SmartPersonalColor v4.0
 *
 * Changes from v3.0:
 *  - Background removal via a zero-dependency, on-device 4-pass segmentation:
 *      Pass 1 · Trimap — corners = definite BG, inner ellipse = definite FG,
 *                         ring zone = uncertain.
 *      Pass 2 · HSV skin scoring — every uncertain pixel gets a confidence
 *                         value [0,1] based on how well it matches the known
 *                         skin-tone distribution of the detected face.
 *      Pass 3 · Spatial elliptic weighting — score is multiplied by a smooth
 *                         elliptic falloff so edges feather naturally.
 *      Pass 4 · Approximate Gaussian blur on the alpha channel — removes
 *                         hard staircase artefacts at the boundary.
 *  - Face image is now saved as PNG (was JPEG) to preserve alpha transparency.
 *  - New event parameter `faceImagePath` still returns a plain file:// string.
 */
@DesignerComponent(
        version = 30,
        versionName = "4.0",
        description = "AI Skin Tone Analyzer — On-device background removal (no external API), "
                + "average face RGB, undertone, season, and 15 professional color palettes.",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = "https://img.icons8.com/color/48/skin.png")
public class SmartPersonalColor extends AndroidNonvisibleComponent {

    private final ComponentContainer container;
    private static final String FACE_IMAGES_DIR = "SmartPersonalColor/faces";

    public SmartPersonalColor(ComponentContainer container) {
        super(container.$form());
        this.container = container;
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    @SimpleFunction(description = "Analyzes the image. Fires AnalysisResult with: average face RGB, "
            + "undertone, season, 15 color palettes (YailList), and the path to a PNG of the "
            + "face with background removed.")
    public void Analyze(final String imagePath) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, Object> result = analyzeSync(imagePath);
                container.$form().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result == null) {
                            Error("Analysis failed. Make sure the image contains a visible face.");
                        } else {
                            int r             = ((Number)   result.get("r")).intValue();
                            int g             = ((Number)   result.get("g")).intValue();
                            int b             = ((Number)   result.get("b")).intValue();
                            String undertone  = (String)    result.get("undertone");
                            String season     = (String)    result.get("season");
                            YailList palettes = (YailList)  result.get("palettes");
                            String facePath   = (String)    result.get("faceImagePath");

                            if (palettes == null) palettes = YailList.makeEmptyList();
                            if (facePath  == null) facePath = "";

                            AnalysisResult(r, g, b, undertone, season, palettes, facePath);
                        }
                    }
                });
            }
        }).start();
    }

    // =========================================================================
    //  CORE ANALYSIS
    // =========================================================================

    private Map<String, Object> analyzeSync(String path) {
        try {
            if (path.startsWith("file://")) path = path.replace("file://", "");

            Bitmap original = BitmapFactory.decodeFile(path);
            if (original == null) return null;

            // Scale to a manageable width while preserving aspect ratio
            int targetW = 480;
            int targetH = (int) ((targetW / (float) original.getWidth()) * original.getHeight());
            Bitmap resized = Bitmap.createScaledBitmap(original, targetW, targetH, true);
            original.recycle();

            // FaceDetector requires RGB_565
            Bitmap rgb565 = resized.copy(Bitmap.Config.RGB_565, true);
            FaceDetector detector = new FaceDetector(rgb565.getWidth(), rgb565.getHeight(), 5);
            Face[] faces = new Face[5];
            int found = detector.findFaces(rgb565, faces);
            rgb565.recycle();

            // Working copy in ARGB_8888 for alpha support
            Bitmap argb = resized.copy(Bitmap.Config.ARGB_8888, true);
            resized.recycle();

            int[] avgColor;
            String facePath;

            if (found > 0) {
                Face face = faces[0];

                // 1. Extract rectangular face crop (ARGB_8888)
                Bitmap faceRect = extractFaceRegion(face, argb);

                // 2. Sample average skin color from crop
                avgColor = sampleSkinPixels(faceRect);

                // 3. Compute adaptive skin reference from sampled color
                float[] skinRef = rgbToHsv(avgColor[0], avgColor[1], avgColor[2]);

                // 4. Remove background with 4-pass segmentation
                Bitmap faceNoBg = removeBackground(faceRect, skinRef);
                faceRect.recycle();

                // 5. Save as PNG (preserves alpha)
                facePath = saveFaceImage(faceNoBg, "face");
                faceNoBg.recycle();

            } else {
                // Fallback: center crop without background removal
                Bitmap center = extractCenterRegion(argb);
                avgColor = averageColor(center);
                facePath = saveFaceImage(center, "center_region");
                center.recycle();
            }

            argb.recycle();

            Map<String, Object> result = classify(avgColor);
            result.put("faceImagePath", facePath != null ? facePath : "");
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // =========================================================================
    //  BACKGROUND REMOVAL — 4-PASS ON-DEVICE SEGMENTATION
    //
    //  No external libraries. No API calls. No model files.
    //  Works on any Android device, any API level.
    //
    //  The algorithm exploits three reliable priors for portrait photography:
    //    (a) The face/person is centered in the crop we extracted from FaceDetector.
    //    (b) Skin pixels follow a narrow HSV distribution, known from sampling.
    //    (c) The boundary between face and background is gradual, not binary.
    // =========================================================================

    /**
     * Removes the background from the face crop.
     *
     * @param src     ARGB_8888 face region bitmap.
     * @param skinRef HSV [h, s, v] of the detected skin tone (used to tune thresholds).
     * @return        New ARGB_8888 bitmap with background pixels made transparent.
     */
    private Bitmap removeBackground(Bitmap src, float[] skinRef) {
        int W = src.getWidth();
        int H = src.getHeight();

        // ── Read all pixels in one batch (faster than per-pixel getPixel) ──────
        int[] pixels = new int[W * H];
        src.getPixels(pixels, 0, W, 0, 0, W, H);

        // ── Pass 1 · Build raw alpha mask [0..255] for each pixel ─────────────
        //    Trimap zones (ellipse coordinates, normalized to [0,1]):
        //      r_norm <= INNER → definite foreground  (alpha = 255)
        //      r_norm >= OUTER → definite background  (alpha = 0)
        //      INNER < r_norm < OUTER → uncertain zone (scored in Pass 2)
        //
        //    The ellipse is slightly taller than wide (portrait ratio 1 : 1.2)
        //    and shifted 10% upward to include the forehead.

        float cx = W / 2.0f;
        float cy = H * 0.48f;       // center slightly above middle (forehead)
        float rx = W * 0.46f;       // horizontal semi-axis
        float ry = H * 0.50f;       // vertical semi-axis (taller)

        final float INNER = 0.70f;  // within this normalized radius → always FG
        final float OUTER = 1.15f;  // beyond this normalized radius → always BG

        float[] rawAlpha = new float[W * H];

        for (int idx = 0; idx < pixels.length; idx++) {
            int x = idx % W;
            int y = idx / W;

            // Normalized elliptic distance from center
            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float rNorm = (float) Math.sqrt(dx * dx + dy * dy);

            if (rNorm <= INNER) {
                rawAlpha[idx] = 1.0f; // definite foreground
            } else if (rNorm >= OUTER) {
                rawAlpha[idx] = 0.0f; // definite background
            } else {
                rawAlpha[idx] = -1.0f; // uncertain — mark for Pass 2
            }
        }

        // ── Pass 2 · HSV skin scoring for uncertain pixels ────────────────────
        //
        //    Each uncertain pixel is scored by how closely its HSV matches
        //    the reference skin tone. The reference ranges are widened slightly
        //    around the sampled face color to accommodate shadows and highlights.
        //
        //    Score formula:
        //      skinScore  = gaussianSimilarity(hue, sat, val, ref)  in [0,1]
        //      ellipticW  = smooth falloff from INNER to OUTER
        //      rawAlpha   = skinScore * ellipticW

        float refH = skinRef[0];
        float refS = skinRef[1];
        float refV = skinRef[2];

        // Adaptive tolerance: darker/more-saturated skins are more variable
        float hueTol = 28f + refS * 12f;   // degrees
        float satTol = 0.22f + refV * 0.10f;
        float valTol = 0.25f + (1f - refV) * 0.10f;

        for (int idx = 0; idx < pixels.length; idx++) {
            if (rawAlpha[idx] != -1.0f) continue; // only uncertain pixels

            int px = pixels[idx];
            int r  = Color.red(px);
            int g  = Color.green(px);
            int b  = Color.blue(px);

            float[] hsv = new float[3];
            Color.RGBToHSV(r, g, b, hsv);

            // Hue distance (circular, [0,360))
            float dH = Math.abs(hsv[0] - refH);
            if (dH > 180f) dH = 360f - dH;

            float dS = Math.abs(hsv[1] - refS);
            float dV = Math.abs(hsv[2] - refV);

            // Gaussian-style score: 1 when identical, 0 when far
            float scoreH = (float) Math.exp(-(dH * dH) / (2f * hueTol * hueTol));
            float scoreS = (float) Math.exp(-(dS * dS) / (2f * satTol * satTol));
            float scoreV = (float) Math.exp(-(dV * dV) / (2f * valTol * valTol));

            float skinScore = scoreH * scoreS * scoreV;

            // Elliptic spatial weight: 1 at INNER, 0 at OUTER (smooth cosine)
            int x = idx % W;
            int y = idx / W;
            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float rNorm = (float) Math.sqrt(dx * dx + dy * dy);
            float t = (rNorm - INNER) / (OUTER - INNER);          // [0,1]
            float ellipticW = (float) (0.5f * (1f + Math.cos(Math.PI * t))); // cosine ease

            rawAlpha[idx] = skinScore * ellipticW;
        }

        // ── Pass 3 · Approximate Gaussian blur on alpha (separable box blur) ──
        //
        //    Running a 3-iteration box blur on a float alpha channel approximates
        //    a Gaussian. This softens the skin/background boundary, eliminating
        //    the hard staircase that would result from a per-pixel binary mask.
        //    Radius = proportional to image size; larger crops get more feathering.

        int blurRadius = Math.max(2, Math.min(W, H) / 28);
        float[] blurred = boxBlurAlpha(rawAlpha, W, H, blurRadius, 3);

        // ── Pass 4 · Write alpha back into pixel array ─────────────────────────
        //
        //    Pixels with very low alpha (< 10/255) are zeroed fully to avoid
        //    near-invisible haze at the edges. A slight contrast boost is applied
        //    to the mid-range so the transition looks crisper.

        for (int idx = 0; idx < pixels.length; idx++) {
            float a = blurred[idx];

            // Slight S-curve contrast on alpha mid-range
            a = sCurve(a);

            int alpha = Math.round(a * 255f);
            if (alpha < 10) alpha = 0;         // cut near-invisible fringe
            if (alpha > 245) alpha = 255;       // snap near-opaque to full

            int origPx = pixels[idx];
            pixels[idx] = Color.argb(
                    alpha,
                    Color.red(origPx),
                    Color.green(origPx),
                    Color.blue(origPx));
        }

        // Build output bitmap
        Bitmap result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, W, 0, 0, W, H);
        return result;
    }

    // ─── Alpha blur helpers ────────────────────────────────────────────────────

    /**
     * Separable box blur on a float[W*H] alpha array.
     * Running {@code iterations} times approximates a Gaussian (Central Limit Theorem).
     */
    private float[] boxBlurAlpha(float[] src, int W, int H, int radius, int iterations) {
        float[] a = src.clone();
        float[] b = new float[W * H];

        for (int iter = 0; iter < iterations; iter++) {
            // Horizontal pass
            for (int y = 0; y < H; y++) {
                float sum = 0;
                int count = 0;
                // Prime the window
                for (int x = 0; x <= radius && x < W; x++) {
                    sum += a[y * W + x];
                    count++;
                }
                for (int x = 0; x < W; x++) {
                    b[y * W + x] = sum / count;
                    int add = x + radius + 1;
                    int rem = x - radius;
                    if (add < W) { sum += a[y * W + add]; count++; }
                    if (rem >= 0) { sum -= a[y * W + rem]; count--; }
                }
            }
            // Vertical pass
            for (int x = 0; x < W; x++) {
                float sum = 0;
                int count = 0;
                for (int y = 0; y <= radius && y < H; y++) {
                    sum += b[y * W + x];
                    count++;
                }
                for (int y = 0; y < H; y++) {
                    a[y * W + x] = sum / count;
                    int add = y + radius + 1;
                    int rem = y - radius;
                    if (add < H) { sum += b[add * W + x]; count++; }
                    if (rem >= 0) { sum -= b[rem * W + x]; count--; }
                }
            }
        }
        return a;
    }

    /**
     * Smooth S-curve (cubic sigmoid) on [0,1] to give the alpha mask
     * a slightly crisper center while keeping soft edges.
     * f(t) = t² * (3 - 2t)  (smoothstep)
     */
    private float sCurve(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // =========================================================================
    //  FACE / REGION EXTRACTION
    // =========================================================================

    private Bitmap extractFaceRegion(Face face, Bitmap src) {
        PointF mid = new PointF();
        face.getMidPoint(mid);
        float ed = face.eyesDistance();

        float fw = ed * 3.4f;   // slightly wider crop for segmentation context
        float fh = fw * 1.4f;

        int l = Math.max(0, (int) (mid.x - fw / 2));
        int t = Math.max(0, (int) (mid.y - fh * 0.42f));
        int r = Math.min(src.getWidth(),  (int) (mid.x + fw / 2));
        int b = Math.min(src.getHeight(), (int) (mid.y + fh * 0.58f));

        if ((r - l) < 60 || (b - t) < 60) {
            l = Math.max(0, (int) (mid.x - 90));
            t = Math.max(0, (int) (mid.y - 90));
            r = Math.min(src.getWidth(),  (int) (mid.x + 90));
            b = Math.min(src.getHeight(), (int) (mid.y + 90));
        }

        return Bitmap.createBitmap(src, l, t, r - l, b - t);
    }

    private Bitmap extractCenterRegion(Bitmap bmp) {
        int cx = bmp.getWidth()  / 2;
        int cy = bmp.getHeight() / 3;
        int sz = Math.min(bmp.getWidth(), bmp.getHeight()) / 2;

        int l = Math.max(0, cx - sz / 2);
        int t = Math.max(0, cy - sz / 2);
        int r = Math.min(bmp.getWidth(),  cx + sz / 2);
        int b = Math.min(bmp.getHeight(), cy + sz / 2);
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t);
    }

    // =========================================================================
    //  COLOR SAMPLING
    // =========================================================================

    /**
     * Samples 500 random pixels from the central 50 % of the face bitmap.
     * Only pixels that pass the broad skin-tone HSV gate are included.
     * Returns mean RGB of skin pixels, or full-bitmap average as fallback.
     */
    private int[] sampleSkinPixels(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        int sl = W / 4, st = H / 4, sr = W * 3 / 4, sb = H * 3 / 4;

        if (sr <= sl || sb <= st) return averageColor(bmp);

        List<int[]> skin = new ArrayList<>();
        Random rng = new Random(42); // fixed seed for reproducibility

        for (int i = 0; i < 500; i++) {
            int x = sl + rng.nextInt(sr - sl);
            int y = st + rng.nextInt(sb - st);
            int px = bmp.getPixel(x, y);

            float[] hsv = new float[3];
            Color.RGBToHSV(Color.red(px), Color.green(px), Color.blue(px), hsv);

            if (isSkinTone(hsv)) {
                skin.add(new int[]{Color.red(px), Color.green(px), Color.blue(px)});
            }
        }

        if (skin.size() < 50) return averageColor(bmp);

        int sumR = 0, sumG = 0, sumB = 0;
        for (int[] p : skin) { sumR += p[0]; sumG += p[1]; sumB += p[2]; }
        int n = skin.size();
        return new int[]{sumR / n, sumG / n, sumB / n};
    }

    private int[] averageColor(Bitmap bmp) {
        long sumR = 0, sumG = 0, sumB = 0;
        int cnt = 0;
        for (int x = 0; x < bmp.getWidth(); x += 5) {
            for (int y = 0; y < bmp.getHeight(); y += 5) {
                int px = bmp.getPixel(x, y);
                sumR += Color.red(px);
                sumG += Color.green(px);
                sumB += Color.blue(px);
                cnt++;
            }
        }
        if (cnt == 0) return new int[]{210, 180, 160};
        return new int[]{(int)(sumR / cnt), (int)(sumG / cnt), (int)(sumB / cnt)};
    }

    /** Broad skin-tone gate (Fitzpatrick I–VI). Used for sampling only. */
    private boolean isSkinTone(float[] hsv) {
        float h = hsv[0], s = hsv[1], v = hsv[2];
        boolean hOk = (h >= 0 && h <= 50) || (h >= 340 && h <= 360);
        boolean sOk = s >= 0.12f && s <= 0.78f;
        boolean vOk = v >= 0.18f && v <= 0.96f;
        return hOk && sOk && vOk;
    }

    /** Converts RGB ints to an HSV float[3]. */
    private float[] rgbToHsv(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return hsv;
    }

    // =========================================================================
    //  FILE I/O  (PNG for transparency support)
    // =========================================================================

    /**
     * Saves the bitmap as a lossless PNG (required to preserve alpha channel).
     * Returns a "file://" path string, or "" on failure.
     */
    private String saveFaceImage(Bitmap bmp, String prefix) {
        try {
            File dir = new File(container.$form().getCacheDir(), FACE_IMAGES_DIR);
            if (!dir.exists()) dir.mkdirs();

            String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File   file = new File(dir, prefix + "_" + ts + ".png");  // PNG, not JPEG

            FileOutputStream out = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);  // lossless
            out.flush();
            out.close();

            return "file://" + file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // =========================================================================
    //  CLASSIFICATION  (undertone · season)
    // =========================================================================

    private Map<String, Object> classify(int[] rgb) {
        int r = rgb[0], g = rgb[1], b = rgb[2];

        int rgd = r - g, gbd = g - b, rbd = r - b;

        String undertone;
        if      (rgd > 20 && rbd > 25)                             undertone = "Golden";
        else if (gbd > 15 && rbd < 15)                             undertone = "Rosy";
        else if (Math.abs(rgd) < 12 && Math.abs(gbd) < 12
                 && Math.abs(rbd) < 20)                             undertone = "Neutral";
        else if (rgd > 10 && rbd > 15 && gbd < 5)                  undertone = "Peach";
        else                                                         undertone = "Olive";

        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        float brightness = hsv[2];
        float saturation = hsv[1];

        boolean isWarm  = undertone.equals("Golden") || undertone.equals("Peach")
                          || undertone.equals("Olive");
        boolean isLight = brightness > 0.65f;
        boolean isClear = saturation > 0.45f;

        String season;
        if (isWarm) {
            if (isLight)  season = isClear ? "True Spring"  : "Light Spring";
            else          season = isClear ? "Warm Autumn"  : "Deep Autumn";
        } else {
            if (isLight)  season = isClear ? "Bright Spring" : "Light Summer";
            else          season = isClear ? "True Winter"  : "Soft Summer";
        }

        YailList palettes = buildAllPalettes(r, g, b, hsv);

        Map<String, Object> result = new HashMap<>();
        result.put("r", r);
        result.put("g", g);
        result.put("b", b);
        result.put("undertone", undertone);
        result.put("season", season);
        result.put("palettes", palettes);
        return result;
    }

    // =========================================================================
    //  PALETTE GENERATION — 15 palettes (6 color theory + 9 personal color)
    // =========================================================================

    private YailList buildAllPalettes(int r, int g, int b, float[] hsv) {
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        List<Object> all = new ArrayList<>();

        // ── Group A: Color Theory ─────────────────────────────────────────────

        // 1. Monochromatic
        all.add(palette("Monochromatic",
                hsvHex(h, clamp(s - 0.35f), clamp(v + 0.25f)),
                hsvHex(h, clamp(s - 0.18f), clamp(v + 0.12f)),
                hsvHex(h, s, v),
                hsvHex(h, clamp(s + 0.18f), clamp(v - 0.12f)),
                hsvHex(h, clamp(s + 0.30f), clamp(v - 0.22f))
        ));

        // 2. Analogous
        all.add(palette("Analogous",
                hsvHex(rotH(h, -60), s, v),
                hsvHex(rotH(h, -30), s, v),
                hsvHex(h, s, v),
                hsvHex(rotH(h,  30), s, v),
                hsvHex(rotH(h,  60), s, v)
        ));

        // 3. Complementary
        float hC = rotH(h, 180);
        all.add(palette("Complementary",
                hsvHex(h,  s,                clamp(v + 0.10f)),
                hsvHex(h,  s,                v),
                hsvHex(h,  clamp(s + 0.15f), clamp(v - 0.15f)),
                hsvHex(hC, s,                v),
                hsvHex(hC, clamp(s - 0.15f), clamp(v + 0.10f))
        ));

        // 4. Triadic
        float hT2 = rotH(h, 120), hT3 = rotH(h, 240);
        all.add(palette("Triadic",
                hsvHex(h,   s, v),
                hsvHex(hT2, s, v),
                hsvHex(hT3, s, v),
                hsvHex(hT2, clamp(s - 0.20f), clamp(v + 0.10f)),
                hsvHex(hT3, clamp(s - 0.20f), clamp(v + 0.10f))
        ));

        // 5. Split-Complementary
        float hS1 = rotH(h, 150), hS2 = rotH(h, 210);
        all.add(palette("Split-Complementary",
                hsvHex(h,   s, v),
                hsvHex(hS1, s, v),
                hsvHex(hS2, s, v),
                hsvHex(hS1, clamp(s - 0.15f), clamp(v + 0.12f)),
                hsvHex(hS2, clamp(s - 0.15f), clamp(v + 0.12f))
        ));

        // 6. Tetradic (Square)
        float hQ2 = rotH(h, 90), hQ3 = rotH(h, 180), hQ4 = rotH(h, 270);
        all.add(palette("Tetradic",
                hsvHex(h,   s, v),
                hsvHex(hQ2, s, v),
                hsvHex(hQ3, s, v),
                hsvHex(hQ4, s, v),
                hsvHex(h,   clamp(s - 0.25f), clamp(v + 0.15f))
        ));

        // ── Group B: Personal Color Analysis ─────────────────────────────────

        // 7. Warm Tones — yellow-gold family, Spring / Warm Autumn
        all.add(palette("Warm Tones",
                hsvHex(12f,  0.60f, 0.88f),
                hsvHex(25f,  0.65f, 0.82f),
                hsvHex(35f,  0.70f, 0.75f),
                hsvHex(45f,  0.60f, 0.78f),
                hsvHex(55f,  0.50f, 0.80f)
        ));

        // 8. Cool Tones — blue-rose family, Summer / Winter
        all.add(palette("Cool Tones",
                hsvHex(200f, 0.45f, 0.80f),
                hsvHex(230f, 0.50f, 0.75f),
                hsvHex(260f, 0.48f, 0.72f),
                hsvHex(290f, 0.42f, 0.78f),
                hsvHex(320f, 0.38f, 0.82f)
        ));

        // 9. Light & Pastel — high value, low chroma; Light Spring / Light Summer
        all.add(palette("Light & Pastel",
                hsvHex(rotH(h, -60), 0.22f, 0.94f),
                hsvHex(rotH(h, -30), 0.25f, 0.92f),
                hsvHex(h,            0.28f, 0.96f),
                hsvHex(rotH(h,  30), 0.25f, 0.93f),
                hsvHex(rotH(h,  60), 0.22f, 0.95f)
        ));

        // 10. Deep & Rich — low value, high chroma; Deep Autumn / Dark Winter
        all.add(palette("Deep & Rich",
                hsvHex(rotH(h, -60), 0.68f, 0.35f),
                hsvHex(rotH(h, -30), 0.72f, 0.42f),
                hsvHex(h,            0.75f, 0.38f),
                hsvHex(rotH(h,  30), 0.70f, 0.45f),
                hsvHex(rotH(h,  60), 0.65f, 0.32f)
        ));

        // 11. Clear & Vivid — max chroma, no grey; Bright Spring / Bright Winter
        all.add(palette("Clear & Vivid",
                hsvHex(rotH(h, -60), 0.80f, 0.85f),
                hsvHex(rotH(h, -30), 0.85f, 0.88f),
                hsvHex(h,            0.88f, 0.90f),
                hsvHex(rotH(h,  30), 0.82f, 0.87f),
                hsvHex(rotH(h,  60), 0.78f, 0.84f)
        ));

        // 12. Muted & Soft — grey-toned, low chroma; Soft Autumn / Soft Summer
        all.add(palette("Muted & Soft",
                hsvHex(rotH(h, -60), 0.18f, 0.65f),
                hsvHex(rotH(h, -30), 0.20f, 0.60f),
                hsvHex(h,            0.22f, 0.68f),
                hsvHex(rotH(h,  30), 0.20f, 0.63f),
                hsvHex(rotH(h,  60), 0.18f, 0.70f)
        ));

        // 13. Earth Tones — terracotta → olive; Warm / True Autumn
        all.add(palette("Earth Tones",
                hsvHex(10f,  0.55f, 0.58f),
                hsvHex(22f,  0.60f, 0.62f),
                hsvHex(35f,  0.55f, 0.55f),
                hsvHex(75f,  0.48f, 0.45f),
                hsvHex(50f,  0.45f, 0.68f)
        ));

        // 14. Jewel Tones — sapphire, emerald, ruby, amethyst, teal; True/Dark Winter
        all.add(palette("Jewel Tones",
                hsvHex(215f, 0.82f, 0.50f),
                hsvHex(155f, 0.78f, 0.40f),
                hsvHex(350f, 0.80f, 0.52f),
                hsvHex(275f, 0.72f, 0.48f),
                hsvHex(185f, 0.76f, 0.42f)
        ));

        // 15. Neutral Harmony — near-zero chroma, white → near-black, tinted with skin hue
        all.add(palette("Neutral Harmony",
                hsvHex(h, 0.04f, 0.96f),
                hsvHex(h, 0.07f, 0.78f),
                hsvHex(h, 0.09f, 0.56f),
                hsvHex(h, 0.11f, 0.32f),
                hsvHex(h, 0.13f, 0.12f)
        ));

        return YailList.makeList(all);
    }

    // ── Palette helpers ────────────────────────────────────────────────────────

    private YailList palette(String name, String c1, String c2, String c3, String c4, String c5) {
        return YailList.makeList(Arrays.asList(name, c1, c2, c3, c4, c5));
    }

    private String hsvHex(float h, float s, float v) {
        int c = Color.HSVToColor(new float[]{h, s, v});
        return String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c));
    }

    private float rotH(float h, float deg) {
        float r = (h + deg) % 360f;
        return r < 0f ? r + 360f : r;
    }

    private float clamp(float val) {
        return Math.max(0f, Math.min(1f, val));
    }

    // =========================================================================
    //  EVENTS
    // =========================================================================

    /**
     * Fired when analysis completes successfully.
     *
     * @param r             Red channel of the average face skin color (0-255).
     * @param g             Green channel (0-255).
     * @param b             Blue channel (0-255).
     * @param undertone     "Golden" | "Rosy" | "Neutral" | "Peach" | "Olive"
     * @param season        12-season result, e.g. "True Spring", "Soft Summer", "Deep Autumn".
     * @param palettes      YailList of 15 palettes.
     *                      Each palette is a YailList:
     *                        index 0 → palette name (String)
     *                        index 1-5 → "#RRGGBB" hex color strings
     *
     *                      Color Theory (6):
     *                        "Monochromatic", "Analogous", "Complementary",
     *                        "Triadic", "Split-Complementary", "Tetradic"
     *
     *                      Personal Color Analysis (9):
     *                        "Warm Tones", "Cool Tones", "Light & Pastel",
     *                        "Deep & Rich", "Clear & Vivid", "Muted & Soft",
     *                        "Earth Tones", "Jewel Tones", "Neutral Harmony"
     *
     * @param faceImagePath file:// path to a transparent PNG of the face
     *                      with the background removed.
     *                      If face detection failed, this is the center-crop PNG (no removal).
     */
    @SimpleEvent(description = "Fires when analysis is complete. "
            + "Returns average face RGB, undertone, 12-season result, "
            + "15 named color palettes (YailList of YailLists: index 0 = name, "
            + "index 1-5 = hex colors), and a transparent PNG path of the face "
            + "with background removed (no external API).")
    public void AnalysisResult(int r, int g, int b,
                               String undertone,
                               String season,
                               YailList palettes,
                               String faceImagePath) {
        EventDispatcher.dispatchEvent(this, "AnalysisResult",
                r, g, b,
                undertone,
                season,
                palettes,
                faceImagePath != null ? faceImagePath : "");
    }

    @SimpleEvent(description = "Fires when analysis fails. Provides a descriptive error message.")
    public void Error(String message) {
        EventDispatcher.dispatchEvent(this, "Error", message);
    }
}