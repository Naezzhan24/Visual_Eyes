package com.example.visualeyes;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class PrivacyPolicyActivity extends AppCompatActivity {

    private ImageView btnClosePrivacyPolicy;
    private Button btnReadPolicyAloud;
    private TextView[] bodyTexts;
    private GoogleTtsManager googleTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);

        googleTts = new GoogleTtsManager(this);

        bindViews();
        applyFontSize();
        setupClicks();
    }

    private void bindViews() {
        btnClosePrivacyPolicy = findViewById(R.id.btnClosePrivacyPolicy);
        btnReadPolicyAloud    = findViewById(R.id.btnReadPolicyAloud);
        bodyTexts = new TextView[]{
                findViewById(R.id.txtSection1Body),
                findViewById(R.id.txtSection2Body),
                findViewById(R.id.txtSection3Body),
                findViewById(R.id.txtSection4Body),
                findViewById(R.id.txtSection5Body),
        };
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);
        for (TextView t : bodyTexts) {
            if (t != null) t.setTextSize(b - 1);
        }
    }

    private void setupClicks() {
        if (btnClosePrivacyPolicy != null) btnClosePrivacyPolicy.setOnClickListener(v -> finish());
        if (btnReadPolicyAloud    != null) btnReadPolicyAloud.setOnClickListener(v -> readAllAloud());
    }

    private void readAllAloud() {
        StringBuilder sb = new StringBuilder();
        for (TextView t : bodyTexts) {
            if (t != null) sb.append(t.getText().toString()).append(". ");
        }
        googleTts.speak(sb.toString(), null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override
    protected void onDestroy() {
        if (googleTts != null) googleTts.destroy();
        super.onDestroy();
    }
}
