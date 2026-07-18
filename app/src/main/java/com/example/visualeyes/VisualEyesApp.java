package com.example.visualeyes;

import android.app.Application;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class VisualEyesApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(getApplicationContext());
    }
}
