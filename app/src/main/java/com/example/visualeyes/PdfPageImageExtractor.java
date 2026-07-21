package com.example.visualeyes;

import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.util.Matrix;

import java.util.ArrayList;
import java.util.List;

public class PdfPageImageExtractor extends PDFGraphicsStreamEngine {

    public static class ExtractedImage {
        public final Bitmap bitmap;
        public final float  topY;
        public final float  bottomY;

        ExtractedImage(Bitmap bitmap, float topY, float bottomY) {
            this.bitmap  = bitmap;
            this.topY    = topY;
            this.bottomY = bottomY;
        }
    }

    private static final String TAG = "PdfImageExtract";
    private static final float  MIN_IMAGE_SIZE_PT     = 25f;
    private static final int    MAX_BITMAP_DIMENSION  = 1600;

    private final float pageHeightPt;
    private final List<ExtractedImage> images = new ArrayList<>();

    public PdfPageImageExtractor(PDPage page) {
        super(page);
        pageHeightPt = page.getMediaBox().getHeight();
    }

    public List<ExtractedImage> extract() {
        try {
            processPage(getPage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to process page for images: " + e.getMessage());
        }
        return images;
    }

    @Override
    public void drawImage(PDImage pdImage) {
        try {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            PointF p0 = ctm.transformPoint(0, 0);
            PointF p1 = ctm.transformPoint(1, 1);

            float x0 = Math.min(p0.x, p1.x);
            float x1 = Math.max(p0.x, p1.x);
            float y0 = Math.min(p0.y, p1.y);
            float y1 = Math.max(p0.y, p1.y);

            if ((x1 - x0) < MIN_IMAGE_SIZE_PT || (y1 - y0) < MIN_IMAGE_SIZE_PT) return;

            Bitmap bitmap = pdImage.getImage();
            if (bitmap == null) return;

            if (bitmap.getWidth() > MAX_BITMAP_DIMENSION || bitmap.getHeight() > MAX_BITMAP_DIMENSION) {
                float scale = Math.min(
                        (float) MAX_BITMAP_DIMENSION / bitmap.getWidth(),
                        (float) MAX_BITMAP_DIMENSION / bitmap.getHeight());
                int newW = Math.max(1, Math.round(bitmap.getWidth() * scale));
                int newH = Math.max(1, Math.round(bitmap.getHeight() * scale));
                bitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
            }

            float topY    = pageHeightPt - y1;
            float bottomY = pageHeightPt - y0;
            images.add(new ExtractedImage(bitmap, topY, bottomY));
        } catch (Exception e) {
            Log.e(TAG, "Skipping image: " + e.getMessage());
        }
    }

    @Override public void appendRectangle(PointF p0, PointF p1, PointF p2, PointF p3) {}
    @Override public void clip(Path.FillType windingRule) {}
    @Override public void moveTo(float x, float y) {}
    @Override public void lineTo(float x, float y) {}
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
    @Override public PointF getCurrentPoint() { return new PointF(0, 0); }
    @Override public void closePath() {}
    @Override public void endPath() {}
    @Override public void strokePath() {}
    @Override public void fillPath(Path.FillType windingRule) {}
    @Override public void fillAndStrokePath(Path.FillType windingRule) {}
    @Override public void shadingFill(COSName shadingName) {}
}
