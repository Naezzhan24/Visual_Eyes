package com.example.visualeyes;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;

import java.net.URLEncoder;
import java.util.Locale;

public class MaterialViewerActivity extends AppCompatActivity {

    private TextView txtMaterialTitle, txtViewerStatus, txtViewerTitle;
    private LinearLayout viewerContainer;
    private ImageView imgViewerIcon;
    private ProgressBar progressViewer;

    private String materialId = "";
    private String fileUrl = "";
    private String title = "Learning Material";
    private String impairmentLevel = "moderate";
    private int recommendedTextSize = 24;

    private static final String LOCAL_API_BASE_URL = "http://192.168.1.109/visualed/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_material_viewer);

        viewerContainer = findViewById(R.id.viewerContainer);
        imgViewerIcon = findViewById(R.id.imgViewerIcon);
        txtViewerTitle = findViewById(R.id.txtViewerTitle);
        txtMaterialTitle = findViewById(R.id.txtMaterialTitle);
        txtViewerStatus = findViewById(R.id.txtViewerStatus);
        progressViewer = findViewById(R.id.progressViewer);

        startOpeningAnimations();

        materialId = safe(getIntent().getStringExtra("material_id"), "");
        fileUrl = safe(getIntent().getStringExtra("file_url"), "");
        title = safe(getIntent().getStringExtra("title"), "Learning Material");
        impairmentLevel = safe(getIntent().getStringExtra("impairment_level"), "");
        recommendedTextSize = getIntent().getIntExtra("recommended_text_size", 24);

        SharedPreferences prefs = getSharedPreferences("VisualEyesPrefs", MODE_PRIVATE);

        String savedImpairmentLevel = prefs.getString("impairmentLevel", "moderate");
        String savedRecommendedTextSize = prefs.getString("recommendedTextSize", "24sp");

        if (impairmentLevel.trim().isEmpty()) {
            impairmentLevel = savedImpairmentLevel;
        }

        if (recommendedTextSize <= 0 || recommendedTextSize == 24) {
            recommendedTextSize = parseRecommendedTextSize(savedRecommendedTextSize);
        }

        if (impairmentLevel == null || impairmentLevel.trim().isEmpty()) {
            impairmentLevel = "moderate";
        }

        txtMaterialTitle.setText(title);

        if (materialId.trim().isEmpty()) {
            txtViewerStatus.setText("Material ID not found.");
            Toast.makeText(this, "Material ID not found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        txtViewerStatus.setText("Loading accessible material...");
        loadMaterialContentFromServer();
    }

    private void loadMaterialContentFromServer() {
        try {
            String encodedId = URLEncoder.encode(materialId, "UTF-8");
            String url = LOCAL_API_BASE_URL + "get_material_content.php?id=" + encodedId;

            RequestQueue queue = VolleySingleton.getInstance(this).getRequestQueue();

            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.GET,
                    url,
                    null,
                    response -> {
                        try {
                            boolean success = response.optBoolean("success", false);

                            if (!success) {
                                openAccessibleReader("No readable content found for this material.");
                                return;
                            }

                            String serverTitle = response.optString("title", title);
                            String content = response.optString("content", "");

                            if (serverTitle != null && !serverTitle.trim().isEmpty()) {
                                title = serverTitle.trim();
                            }

                            if (content == null || content.trim().isEmpty()) {
                                content = "No readable content found. Please make sure the material was extracted successfully.";
                            }

                            txtViewerStatus.setText("Opening accessible material...");

                            openAccessibleReader(formatAccessibleText(content));

                        } catch (Exception e) {
                            openAccessibleReader("Error loading material content.");
                        }
                    },
                    error -> {
                        txtViewerStatus.setText("Connection error.");
                        openAccessibleReader("Unable to connect to the material content server. Please check your internet connection or server IP address.");
                    }
            );

            queue.add(request);

        } catch (Exception e) {
            openAccessibleReader("Failed to prepare learning material.");
        }
    }

    private void openAccessibleReader(String content) {
        Intent intent = new Intent(MaterialViewerActivity.this, AccessibleMaterialActivity.class);
        intent.putExtra("material_id", materialId);
        intent.putExtra("file_url", fileUrl);
        intent.putExtra("title", title);
        intent.putExtra("content", content);
        intent.putExtra("recommended_text_size", recommendedTextSize);
        intent.putExtra("impairment_level", impairmentLevel);

        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right_soft, R.anim.fade_out_soft);
        finish();
    }

    private String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private void startOpeningAnimations() {
        try {
            Animation cardEnter = AnimationUtils.loadAnimation(this, R.anim.viewer_card_enter);
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.viewer_pulse);

            if (viewerContainer != null) viewerContainer.startAnimation(cardEnter);
            if (imgViewerIcon != null) imgViewerIcon.startAnimation(pulse);
            if (txtViewerStatus != null) txtViewerStatus.startAnimation(pulse);

        } catch (Exception ignored) {
        }
    }

    private int parseRecommendedTextSize(String sizeText) {
        if (sizeText == null || sizeText.trim().isEmpty()) {
            return 24;
        }

        String cleaned = sizeText.toLowerCase(Locale.ROOT).replace("sp", "").trim();

        try {
            return Integer.parseInt(cleaned);
        } catch (Exception e) {
            return 24;
        }
    }

    private String formatAccessibleText(String rawText) {
        if (rawText == null) {
            return "";
        }

        String text = rawText.trim();
        text = text.replaceAll("\\\\n", "\n");
        text = text.replaceAll("\\r\\n", "\n");
        text = text.replaceAll("\\r", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("(?m)^\\s+", "");
        text = text.replaceAll("(?m)\\s+$", "");

        if (text.trim().isEmpty()) {
            return "No readable text found in this file.";
        }

        return text;
    }
}
