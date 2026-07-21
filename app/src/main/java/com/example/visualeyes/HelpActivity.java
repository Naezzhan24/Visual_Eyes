package com.example.visualeyes;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class HelpActivity extends AppCompatActivity {

    private ImageView btnCloseHelp;
    private Button btnReadAloud;
    private TextView[] bodyTexts;
    private GoogleTtsManager googleTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        googleTts = new GoogleTtsManager(this);

        bindViews();
        applyFontSize();
        setupClicks();
    }

    private void bindViews() {
        btnCloseHelp = findViewById(R.id.btnCloseHelp);
        btnReadAloud = findViewById(R.id.btnReadAloud);
        bodyTexts = new TextView[]{
                findViewById(R.id.txtSection1Body),
                findViewById(R.id.txtSection2Body),
                findViewById(R.id.txtSection3Body),
                findViewById(R.id.txtSectionLoginBody),
                findViewById(R.id.txtSection4Body),
                findViewById(R.id.txtSection5Body),
                findViewById(R.id.txtSection6Body),
                findViewById(R.id.txtSection7Body),
                findViewById(R.id.txtSection8Body),
                findViewById(R.id.txtSection9Body),
        };
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);
        for (TextView t : bodyTexts) {
            if (t != null) t.setTextSize(b - 1);
        }
    }

    private void setupClicks() {
        if (btnCloseHelp != null) btnCloseHelp.setOnClickListener(v -> finish());
        if (btnReadAloud != null) btnReadAloud.setOnClickListener(v -> readAllAloud());
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
