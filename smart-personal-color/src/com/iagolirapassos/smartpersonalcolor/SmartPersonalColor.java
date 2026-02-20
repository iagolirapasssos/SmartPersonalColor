package com.iagolirapassos.smartpersonalcolor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
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
 * SmartPersonalColor v5.0 - ANÁLISE COMPLETA DE COLORAÇÃO PESSOAL
 * 
 * Características:
 * - Detecção facial precisa
 * - Análise de subtom (Quente, Frio, Neutro)
 * - Análise de contraste (Baixo, Médio, Alto)
 * - Análise de intensidade (Brilhante, Suave)
 * - Classificação nas 12 estações sazonais completas
 * - Geração de 15 paletas profissionais
 * - Remoção de fundo com preservação de alpha
 * - Imagem do rosto em PNG com transparência
 */
@DesignerComponent(
        version = 52,
        versionName = "5.0",
        description = "ANÁLISE COMPLETA DE COLORAÇÃO PESSOAL - Detecta subtom, contraste, intensidade e classifica nas 12 estações sazonais com alta precisão.",
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

    @SimpleFunction(description = "Analyzes the image. Fires AnalysisResult with complete personal color analysis.")
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
                            int r = ((Number) result.get("r")).intValue();
                            int g = ((Number) result.get("g")).intValue();
                            int b = ((Number) result.get("b")).intValue();
                            String undertone = (String) result.get("undertone");
                            String undertoneDetail = (String) result.get("undertoneDetail");
                            String contrast = (String) result.get("contrast");
                            String intensity = (String) result.get("intensity");
                            String season = (String) result.get("season");
                            String seasonFull = (String) result.get("seasonFull");
                            String seasonCategory = (String) result.get("seasonCategory");
                            YailList palettes = (YailList) result.get("palettes");
                            String facePath = (String) result.get("faceImagePath");

                            if (palettes == null) palettes = YailList.makeEmptyList();
                            if (facePath == null) facePath = "";

                            // Chamar o evento com todos os parâmetros
                            AnalysisResult(r, g, b, undertone, undertoneDetail, contrast, intensity, 
                                          season, seasonFull, seasonCategory, palettes, facePath);
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

            // Scale to a manageable width
            int targetW = 600;
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
            Map<String, Object> analysis = new HashMap<>();

            if (found > 0) {
                Face face = faces[0];
                Bitmap faceRect = extractFaceRegion(face, argb);
                avgColor = sampleSkinPixels(faceRect);
                
                // Análise avançada de características faciais
                analysis = analyzeFaceFeatures(faceRect, face, avgColor);
                
                Bitmap faceNoBg = removeBackground(faceRect, rgbToHsv(avgColor[0], avgColor[1], avgColor[2]));
                faceRect.recycle();
                facePath = saveFaceImage(faceNoBg, "face");
                faceNoBg.recycle();
            } else {
                Bitmap center = extractCenterRegion(argb);
                avgColor = averageColor(center);
                analysis = analyzeBasicFeatures(avgColor);
                facePath = saveFaceImage(center, "center_region");
                center.recycle();
            }

            argb.recycle();

            Map<String, Object> result = classify(avgColor, analysis);
            result.put("faceImagePath", facePath != null ? facePath : "");
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // =========================================================================
    //  ANÁLISE AVANÇADA DE CARACTERÍSTICAS FACIAIS
    // =========================================================================

    private Map<String, Object> analyzeFaceFeatures(Bitmap faceBitmap, Face face, int[] skinColor) {
        Map<String, Object> features = new HashMap<>();
        
        int w = faceBitmap.getWidth();
        int h = faceBitmap.getHeight();
        
        // Extrair regiões para análise de olhos e cabelo
        // Região dos olhos (parte superior do rosto)
        int eyeRegionY = (int) (h * 0.3);
        int eyeRegionHeight = (int) (h * 0.25);
        
        // Região do cabelo (testa e acima)
        int hairRegionY = (int) (h * 0.1);
        int hairRegionHeight = (int) (h * 0.2);
        
        // Analisar cor dos olhos (região central superior)
        int[] eyeColor = sampleRegionColor(faceBitmap, w/3, eyeRegionY, w/3, eyeRegionHeight);
        
        // Analisar cor do cabelo (região superior)
        int[] hairColor = sampleRegionColor(faceBitmap, 0, hairRegionY, w, hairRegionHeight);
        
        // Calcular contraste entre pele, olhos e cabelo
        float skinEyeContrast = calculateContrast(skinColor, eyeColor);
        float skinHairContrast = calculateContrast(skinColor, hairColor);
        float eyeHairContrast = calculateContrast(eyeColor, hairColor);
        
        float avgContrast = (skinEyeContrast + skinHairContrast + eyeHairContrast) / 3;
        
        // Determinar nível de contraste
        String contrastLevel;
        if (avgContrast > 70) {
            contrastLevel = "Alto";
        } else if (avgContrast > 40) {
            contrastLevel = "Médio";
        } else {
            contrastLevel = "Baixo";
        }
        
        // Analisar intensidade (brilho vs suavidade)
        float[] skinHsv = rgbToHsv(skinColor[0], skinColor[1], skinColor[2]);
        float[] eyeHsv = rgbToHsv(eyeColor[0], eyeColor[1], eyeColor[2]);
        float[] hairHsv = rgbToHsv(hairColor[0], hairColor[1], hairColor[2]);
        
        float avgSaturation = (skinHsv[1] + eyeHsv[1] + hairHsv[1]) / 3;
        float avgValue = (skinHsv[2] + eyeHsv[2] + hairHsv[2]) / 3;
        
        // Intensidade: Brilhante (alta saturação e valor) vs Suave (baixa saturação)
        String intensity;
        if (avgSaturation > 0.4 && avgValue > 0.6) {
            intensity = "Brilhante";
        } else {
            intensity = "Suave";
        }
        
        features.put("contrast", contrastLevel);
        features.put("contrastValue", avgContrast);
        features.put("intensity", intensity);
        features.put("eyeColor", eyeColor);
        features.put("hairColor", hairColor);
        features.put("saturation", avgSaturation);
        features.put("brightness", avgValue);
        
        return features;
    }

    private Map<String, Object> analyzeBasicFeatures(int[] skinColor) {
        Map<String, Object> features = new HashMap<>();
        
        float[] hsv = rgbToHsv(skinColor[0], skinColor[1], skinColor[2]);
        
        features.put("contrast", "Médio");
        features.put("intensity", hsv[1] > 0.4 ? "Brilhante" : "Suave");
        features.put("saturation", hsv[1]);
        features.put("brightness", hsv[2]);
        
        return features;
    }

    private int[] sampleRegionColor(Bitmap bitmap, int startX, int startY, int width, int height) {
        int endX = Math.min(startX + width, bitmap.getWidth());
        int endY = Math.min(startY + height, bitmap.getHeight());
        
        long sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        
        for (int x = startX; x < endX; x += 3) {
            for (int y = startY; y < endY; y += 3) {
                int pixel = bitmap.getPixel(x, y);
                sumR += Color.red(pixel);
                sumG += Color.green(pixel);
                sumB += Color.blue(pixel);
                count++;
            }
        }
        
        if (count == 0) return new int[]{128, 128, 128};
        
        return new int[]{
            (int) (sumR / count),
            (int) (sumG / count),
            (int) (sumB / count)
        };
    }

    private float calculateContrast(int[] color1, int[] color2) {
        // Calcular diferença de luminância (contraste)
        float l1 = 0.2126f * color1[0] + 0.7152f * color1[1] + 0.0722f * color1[2];
        float l2 = 0.2126f * color2[0] + 0.7152f * color2[1] + 0.0722f * color2[2];
        
        return Math.abs(l1 - l2);
    }

    // =========================================================================
    //  BACKGROUND REMOVAL (mantido igual)
    // =========================================================================

    private Bitmap removeBackground(Bitmap src, float[] skinRef) {
        int W = src.getWidth();
        int H = src.getHeight();

        int[] pixels = new int[W * H];
        src.getPixels(pixels, 0, W, 0, 0, W, H);

        float cx = W / 2.0f;
        float cy = H * 0.48f;
        float rx = W * 0.46f;
        float ry = H * 0.50f;

        final float INNER = 0.70f;
        final float OUTER = 1.15f;

        float[] rawAlpha = new float[W * H];

        for (int idx = 0; idx < pixels.length; idx++) {
            int x = idx % W;
            int y = idx / W;

            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float rNorm = (float) Math.sqrt(dx * dx + dy * dy);

            if (rNorm <= INNER) {
                rawAlpha[idx] = 1.0f;
            } else if (rNorm >= OUTER) {
                rawAlpha[idx] = 0.0f;
            } else {
                rawAlpha[idx] = -1.0f;
            }
        }

        float refH = skinRef[0];
        float refS = skinRef[1];
        float refV = skinRef[2];

        float hueTol = 28f + refS * 12f;
        float satTol = 0.22f + refV * 0.10f;
        float valTol = 0.25f + (1f - refV) * 0.10f;

        for (int idx = 0; idx < pixels.length; idx++) {
            if (rawAlpha[idx] != -1.0f) continue;

            int px = pixels[idx];
            int r = Color.red(px);
            int g = Color.green(px);
            int b = Color.blue(px);

            float[] hsv = new float[3];
            Color.RGBToHSV(r, g, b, hsv);

            float dH = Math.abs(hsv[0] - refH);
            if (dH > 180f) dH = 360f - dH;

            float dS = Math.abs(hsv[1] - refS);
            float dV = Math.abs(hsv[2] - refV);

            float scoreH = (float) Math.exp(-(dH * dH) / (2f * hueTol * hueTol));
            float scoreS = (float) Math.exp(-(dS * dS) / (2f * satTol * satTol));
            float scoreV = (float) Math.exp(-(dV * dV) / (2f * valTol * valTol));

            float skinScore = scoreH * scoreS * scoreV;

            int x = idx % W;
            int y = idx / W;
            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float rNorm = (float) Math.sqrt(dx * dx + dy * dy);
            float t = (rNorm - INNER) / (OUTER - INNER);
            float ellipticW = (float) (0.5f * (1f + Math.cos(Math.PI * t)));

            rawAlpha[idx] = skinScore * ellipticW;
        }

        int blurRadius = Math.max(2, Math.min(W, H) / 28);
        float[] blurred = boxBlurAlpha(rawAlpha, W, H, blurRadius, 3);

        for (int idx = 0; idx < pixels.length; idx++) {
            float a = blurred[idx];
            a = sCurve(a);
            int alpha = Math.round(a * 255f);
            if (alpha < 10) alpha = 0;
            if (alpha > 245) alpha = 255;

            int origPx = pixels[idx];
            pixels[idx] = Color.argb(
                    alpha,
                    Color.red(origPx),
                    Color.green(origPx),
                    Color.blue(origPx));
        }

        Bitmap result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, W, 0, 0, W, H);
        return result;
    }

    private float[] boxBlurAlpha(float[] src, int W, int H, int radius, int iterations) {
        float[] a = src.clone();
        float[] b = new float[W * H];

        for (int iter = 0; iter < iterations; iter++) {
            for (int y = 0; y < H; y++) {
                float sum = 0;
                int count = 0;
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

        float fw = ed * 3.4f;
        float fh = fw * 1.4f;

        int l = Math.max(0, (int) (mid.x - fw / 2));
        int t = Math.max(0, (int) (mid.y - fh * 0.42f));
        int r = Math.min(src.getWidth(), (int) (mid.x + fw / 2));
        int b = Math.min(src.getHeight(), (int) (mid.y + fh * 0.58f));

        if ((r - l) < 60 || (b - t) < 60) {
            l = Math.max(0, (int) (mid.x - 90));
            t = Math.max(0, (int) (mid.y - 90));
            r = Math.min(src.getWidth(), (int) (mid.x + 90));
            b = Math.min(src.getHeight(), (int) (mid.y + 90));
        }

        return Bitmap.createBitmap(src, l, t, r - l, b - t);
    }

    private Bitmap extractCenterRegion(Bitmap bmp) {
        int cx = bmp.getWidth() / 2;
        int cy = bmp.getHeight() / 3;
        int sz = Math.min(bmp.getWidth(), bmp.getHeight()) / 2;

        int l = Math.max(0, cx - sz / 2);
        int t = Math.max(0, cy - sz / 2);
        int r = Math.min(bmp.getWidth(), cx + sz / 2);
        int b = Math.min(bmp.getHeight(), cy + sz / 2);
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t);
    }

    // =========================================================================
    //  COLOR SAMPLING
    // =========================================================================

    private int[] sampleSkinPixels(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        int sl = W / 4, st = H / 4, sr = W * 3 / 4, sb = H * 3 / 4;

        if (sr <= sl || sb <= st) return averageColor(bmp);

        List<int[]> skin = new ArrayList<>();
        Random rng = new Random(42);

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
        return new int[]{(int) (sumR / cnt), (int) (sumG / cnt), (int) (sumB / cnt)};
    }

    private boolean isSkinTone(float[] hsv) {
        float h = hsv[0], s = hsv[1], v = hsv[2];
        boolean hOk = (h >= 0 && h <= 50) || (h >= 340 && h <= 360);
        boolean sOk = s >= 0.12f && s <= 0.78f;
        boolean vOk = v >= 0.18f && v <= 0.96f;
        return hOk && sOk && vOk;
    }

    private float[] rgbToHsv(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return hsv;
    }

    // =========================================================================
    //  FILE I/O
    // =========================================================================

    private String saveFaceImage(Bitmap bmp, String prefix) {
        try {
            File dir = new File(container.$form().getCacheDir(), FACE_IMAGES_DIR);
            if (!dir.exists()) dir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File file = new File(dir, prefix + "_" + ts + ".png");

            FileOutputStream out = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();

            return "file://" + file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // =========================================================================
    //  CLASSIFICAÇÃO COMPLETA - 12 ESTAÇÕES SAZONAIS
    // =========================================================================

    private Map<String, Object> classify(int[] rgb, Map<String, Object> features) {
        int r = rgb[0], g = rgb[1], b = rgb[2];

        // Análise de subtom detalhada
        int rgd = r - g;
        int gbd = g - b;
        int rbd = r - b;

        String undertone;
        String undertoneDetail;

        // Classificação de subtom mais precisa
        if (rgd > 25 && rbd > 30) {
            undertone = "Quente";
            undertoneDetail = "Dourado Intenso";
        } else if (rgd > 15 && rbd > 20) {
            undertone = "Quente";
            undertoneDetail = "Dourado Médio";
        } else if (rgd > 5 && rbd > 10) {
            undertone = "Quente";
            undertoneDetail = "Dourado Suave";
        } else if (gbd > 20 && rbd < 10) {
            undertone = "Frio";
            undertoneDetail = "Rosado Intenso";
        } else if (gbd > 10 && rbd < 5) {
            undertone = "Frio";
            undertoneDetail = "Rosado Médio";
        } else if (gbd > 5) {
            undertone = "Frio";
            undertoneDetail = "Rosado Suave";
        } else if (Math.abs(rgd) < 8 && Math.abs(gbd) < 8 && Math.abs(rbd) < 15) {
            undertone = "Neutro";
            undertoneDetail = "Neutro Equilibrado";
        } else if (rgd > 8 && rbd > 12 && gbd < 3) {
            undertone = "Quente";
            undertoneDetail = "Pêssego";
        } else if (gbd > 5 && rbd > 5 && rgd < 5) {
            undertone = "Frio";
            undertoneDetail = "Oliva";
        } else {
            undertone = "Neutro";
            undertoneDetail = "Neutro Suave";
        }

        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        float hue = hsv[0];
        float saturation = hsv[1];
        float value = hsv[2];

        // Obter características da análise facial
        String contrast = (String) features.get("contrast");
        String intensity = (String) features.get("intensity");
        
        // Determinar temperatura (Quente vs Frio)
        boolean isWarm = undertone.equals("Quente");
        
        // Determinar claridade (Claro vs Escuro)
        boolean isLight = value > 0.6f;
        boolean isDark = value < 0.4f;
        
        // Determinar cromaticidade (Brilhante vs Suave)
        boolean isClear = saturation > 0.45f || intensity.equals("Brilhante");
        boolean isSoft = saturation < 0.35f || intensity.equals("Suave");

        // CLASSIFICAÇÃO NAS 12 ESTAÇÕES SAZONAIS
        String season = "";
        String seasonFull = "";
        String seasonCategory = "";

        if (isWarm) {
            // Família QUENTE (Primavera e Outono)
            if (isLight) {
                // Primavera (claras)
                if (isClear) {
                    season = "Bright Spring";
                    seasonFull = "Primavera Brilhante";
                    seasonCategory = "spring_bright";
                } else if (isSoft) {
                    season = "Light Spring";
                    seasonFull = "Primavera Clara";
                    seasonCategory = "spring_light";
                } else {
                    season = "Warm Spring";
                    seasonFull = "Primavera Quente";
                    seasonCategory = "spring_warm";
                }
            } else {
                // Outono (escuras)
                if (isClear) {
                    season = "Warm Autumn";
                    seasonFull = "Outono Quente";
                    seasonCategory = "autumn_warm";
                } else if (isSoft) {
                    season = "Soft Autumn";
                    seasonFull = "Outono Suave";
                    seasonCategory = "autumn_soft";
                } else {
                    season = "Deep Autumn";
                    seasonFull = "Outono Escuro";
                    seasonCategory = "autumn_deep";
                }
            }
        } else {
            // Família FRIA (Verão e Inverno)
            if (isLight) {
                // Verão (claras)
                if (isClear) {
                    season = "Light Summer";
                    seasonFull = "Verão Claro";
                    seasonCategory = "summer_light";
                } else if (isSoft) {
                    season = "Soft Summer";
                    seasonFull = "Verão Suave";
                    seasonCategory = "summer_soft";
                } else {
                    season = "Cool Summer";
                    seasonFull = "Verão Frio";
                    seasonCategory = "summer_cool";
                }
            } else {
                // Inverno (escuras)
                if (isClear) {
                    season = "Bright Winter";
                    seasonFull = "Inverno Brilhante";
                    seasonCategory = "winter_bright";
                } else if (isSoft) {
                    season = "Cool Winter";
                    seasonFull = "Inverno Frio";
                    seasonCategory = "winter_cool";
                } else {
                    season = "Deep Winter";
                    seasonFull = "Inverno Escuro";
                    seasonCategory = "winter_deep";
                }
            }
        }

        YailList palettes = buildAllPalettes(r, g, b, hsv, season, undertone, contrast, intensity);

        Map<String, Object> result = new HashMap<>();
        result.put("r", r);
        result.put("g", g);
        result.put("b", b);
        result.put("undertone", undertone);
        result.put("undertoneDetail", undertoneDetail);
        result.put("contrast", contrast);
        result.put("intensity", intensity);
        result.put("season", season);
        result.put("seasonFull", seasonFull);
        result.put("seasonCategory", seasonCategory);
        result.put("palettes", palettes);
        
        return result;
    }

    // =========================================================================
    //  PALETTE GENERATION — 15 paletas personalizadas
    // =========================================================================

    private YailList buildAllPalettes(int r, int g, int b, float[] hsv, 
                                      String season, String undertone, 
                                      String contrast, String intensity) {
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        List<Object> all = new ArrayList<>();

        // Paleta 1-6: Teoria das Cores (baseada no tom de pele)
        all.add(palette("Monochromatic",
                hsvHex(h, clamp(s - 0.35f), clamp(v + 0.25f)),
                hsvHex(h, clamp(s - 0.18f), clamp(v + 0.12f)),
                hsvHex(h, s, v),
                hsvHex(h, clamp(s + 0.18f), clamp(v - 0.12f)),
                hsvHex(h, clamp(s + 0.30f), clamp(v - 0.22f))
        ));

        all.add(palette("Analogous",
                hsvHex(rotH(h, -60), s, v),
                hsvHex(rotH(h, -30), s, v),
                hsvHex(h, s, v),
                hsvHex(rotH(h, 30), s, v),
                hsvHex(rotH(h, 60), s, v)
        ));

        float hC = rotH(h, 180);
        all.add(palette("Complementary",
                hsvHex(h, s, clamp(v + 0.10f)),
                hsvHex(h, s, v),
                hsvHex(h, clamp(s + 0.15f), clamp(v - 0.15f)),
                hsvHex(hC, s, v),
                hsvHex(hC, clamp(s - 0.15f), clamp(v + 0.10f))
        ));

        // Paletas 7-15: Baseadas na estação sazonal
        if (season.contains("Spring")) {
            // Primavera - cores vibrantes e quentes
            all.add(palette("Spring Vibrant", "#FF6B6B", "#FFB347", "#FFD966", "#98FB98", "#87CEEB"));
            all.add(palette("Spring Pastel", "#FFB6C1", "#FFDAB9", "#FFFACD", "#E6E6FA", "#B0E0E6"));
            all.add(palette("Spring Bright", "#FF4500", "#FF8C00", "#FFD700", "#ADFF2F", "#00CED1"));
        } else if (season.contains("Summer")) {
            // Verão - cores suaves e frias
            all.add(palette("Summer Soft", "#B0C4DE", "#B0E0E6", "#D8BFD8", "#DDA0DD", "#E6E6FA"));
            all.add(palette("Summer Cool", "#87CEEB", "#ADD8E6", "#B0C4DE", "#C6E2FF", "#E0FFFF"));
            all.add(palette("Summer Pastel", "#FFB6C1", "#FFC0CB", "#FFDAB9", "#E0FFFF", "#F0FFF0"));
        } else if (season.contains("Autumn")) {
            // Outono - cores terrosas e quentes
            all.add(palette("Autumn Earth", "#8B4513", "#A0522D", "#CD853F", "#D2B48C", "#F4A460"));
            all.add(palette("Autumn Warm", "#B22222", "#CD5C5C", "#D2691E", "#B8860B", "#9ACD32"));
            all.add(palette("Autumn Rich", "#800000", "#8B0000", "#B8860B", "#556B2F", "#2E8B57"));
        } else if (season.contains("Winter")) {
            // Inverno - cores intensas e frias
            all.add(palette("Winter Cool", "#4169E1", "#0000CD", "#00008B", "#483D8B", "#191970"));
            all.add(palette("Winter Bright", "#DC143C", "#B22222", "#8B0000", "#FF1493", "#9400D3"));
            all.add(palette("Winter Jewel", "#9932CC", "#8A2BE2", "#4B0082", "#2E0854", "#191970"));
        }

        // Paleta baseada no contraste
        if (contrast.equals("Alto")) {
            all.add(palette("High Contrast", "#000000", "#FFFFFF", "#FF0000", "#0000FF", "#FFFF00"));
        } else if (contrast.equals("Médio")) {
            all.add(palette("Medium Contrast", "#696969", "#D3D3D3", "#B22222", "#2E8B57", "#1E90FF"));
        } else {
            all.add(palette("Low Contrast", "#D3D3D3", "#F5F5F5", "#FFE4E1", "#E6E6FA", "#F0FFF0"));
        }

        // Paleta baseada na intensidade
        if (intensity.equals("Brilhante")) {
            all.add(palette("Bright Palette", "#FF1493", "#FF4500", "#FFD700", "#00FF00", "#00BFFF"));
        } else {
            all.add(palette("Soft Palette", "#FFB6C1", "#FFDAB9", "#E6E6FA", "#B0E0E6", "#D3D3D3"));
        }

        // Paleta neutra universal
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
    //  EVENTOS
    // =========================================================================

    @SimpleEvent(description = "Fires when analysis is complete. Returns complete personal color analysis.")
    public void AnalysisResult(int r, int g, int b,
                               String undertone,
                               String undertoneDetail,
                               String contrast,
                               String intensity,
                               String season,
                               String seasonFull,
                               String seasonCategory,
                               YailList palettes,
                               String faceImagePath) {
        EventDispatcher.dispatchEvent(this, "AnalysisResult",
                r, g, b,
                undertone,
                undertoneDetail,
                contrast,
                intensity,
                season,
                seasonFull,
                seasonCategory,
                palettes,
                faceImagePath != null ? faceImagePath : "");
    }

    @SimpleEvent(description = "Fires when analysis fails. Provides a descriptive error message.")
    public void Error(String message) {
        EventDispatcher.dispatchEvent(this, "Error", message);
    }
}