package com.example.visualeyes;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MaterialsDrawerController {

    private static final String TAG = "MaterialsDrawer";
    private static final String PREFS_NAME            = "VisualEyesPrefs";
    private static final String KEY_LAST_OPENED_TITLE = "last_opened_title";
    private static final String KEY_LAST_OPENED_URL   = "last_opened_url";

    private final Activity activity;
    private final DrawerLayout drawerLayout;
    private final LinearLayout container;
    private final ArrayList<LearningMaterial> materials = new ArrayList<>();
    private final Runnable beforeOpenMaterial;

    MaterialsDrawerController(Activity activity, DrawerLayout drawerLayout, LinearLayout container,
                               ImageView menuIcon, ImageView btnClose, Runnable beforeOpenMaterial) {
        this.activity = activity;
        this.drawerLayout = drawerLayout;
        this.container = container;
        this.beforeOpenMaterial = beforeOpenMaterial;
        if (btnClose != null) btnClose.setOnClickListener(v -> close());
        if (drawerLayout != null && menuIcon != null) {
            drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override public void onDrawerOpened(View drawerView) {
                    menuIcon.animate().rotation(90f).setDuration(220).start();
                }
                @Override public void onDrawerClosed(View drawerView) {
                    menuIcon.animate().rotation(0f).setDuration(220).start();
                }
            });
        }
    }

    void open() {
        renderList();
        if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
    }

    void close() { if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START); }

    void load() {
        if (container == null) return;

        String url = ApiConfig.MATERIALS
                + "?is_sent_to_app=eq.true"
                + "&admin_approval_status=eq.approved"
                + "&select=id,title,file_path,upload_date"
                + "&order=upload_date.desc";

        RequestQueue queue = Volley.newRequestQueue(activity);
        JsonArrayRequest req = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    materials.clear();
                    try {
                        if (response != null) {
                            for (int i = 0; i < response.length(); i++) {
                                JSONObject obj = response.getJSONObject(i);
                                materials.add(new LearningMaterial(
                                        obj.optString("id",          ""),
                                        obj.optString("title",       "Untitled Material"),
                                        "English",
                                        buildFileUrl(obj.optString("file_path", "")),
                                        obj.optString("upload_date", "")
                                ));
                            }
                        }
                        renderList();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse materials: " + e.getMessage());
                        container.removeAllViews();
                        container.addView(emptyLabel("Unable to load materials."));
                    }
                },
                error -> {
                    Log.e(TAG, "Failed to load materials: " + error.getMessage());
                    container.removeAllViews();
                    container.addView(emptyLabel("Connection failed."));
                }
        ) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("apikey",        ApiConfig.SUPABASE_KEY);
                h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                h.put("Accept",        "application/json");
                return h;
            }
        };
        queue.add(req);
    }

    void showFromList(List<LearningMaterial> alreadyFetched) {
        materials.clear();
        if (alreadyFetched != null) materials.addAll(alreadyFetched);
        renderList();
    }

    private void renderList() {
        if (container == null) return;
        container.removeAllViews();
        if (materials.isEmpty()) {
            container.addView(emptyLabel("No materials sent yet."));
            return;
        }
        String lastGroup = null;
        for (LearningMaterial m : materials) {
            String group = dateGroupLabel(m.getDateResolved());
            if (!group.equals(lastGroup)) {
                container.addView(dateHeader(group));
                lastGroup = group;
            }
            container.addView(materialRow(m));
        }
    }

    private String buildFileUrl(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "";
        filePath = filePath.trim().replace("\\", "/");
        if (filePath.startsWith("http://") || filePath.startsWith("https://"))
            return filePath.replace(" ", "%20");
        while (filePath.startsWith("/")) filePath = filePath.substring(1);
        if (filePath.startsWith("materials/")) filePath = filePath.substring("materials/".length());
        return ApiConfig.SUPABASE_URL + "/storage/v1/object/public/materials/"
                + filePath.replace(" ", "%20");
    }

    private String dateGroupLabel(String rawDate) {
        if (rawDate == null || rawDate.trim().isEmpty()) return "Unknown Date";
        String datePart = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setLenient(false);
            Date parsed = sdf.parse(datePart);

            Calendar target = Calendar.getInstance();
            target.setTime(parsed);
            Calendar today = Calendar.getInstance();
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);

            if (isSameDay(target, today))     return "Today";
            if (isSameDay(target, yesterday)) return "Yesterday";

            return new SimpleDateFormat("MMMM d, yyyy", Locale.US).format(parsed);
        } catch (Exception e) {
            return datePart;
        }
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private TextView emptyLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(0xFF7E6E73);
        label.setTextSize(14f);
        label.setPadding(dp(16), dp(16), dp(16), dp(16));
        return label;
    }

    private TextView dateHeader(String label) {
        TextView header = new TextView(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(14), dp(16), dp(6));
        header.setLayoutParams(lp);
        header.setText(label);
        header.setTextSize(13f);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setTextColor(0xFF8C4356);
        return header;
    }

    private View materialRow(LearningMaterial material) {
        boolean opened = MaterialReadTracker.isOpened(activity, material.getId());

        LinearLayout row = new LinearLayout(activity);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(dp(12), dp(4), dp(12), dp(4));
        row.setLayoutParams(rowLp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setShape(GradientDrawable.RECTANGLE);
        cardShape.setColor(0xFFFFFFFF);
        cardShape.setCornerRadius(dp(14));
        cardShape.setStroke(dp(1), 0xFFE7D9DE);
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x338C4356), cardShape, cardShape));

        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(opened ? 0xFFCCCCCC : 0xFF8C4356);
        View dot = new View(activity);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        dot.setBackground(dotShape);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));

        TextView title = new TextView(activity);
        title.setText(material.getTitle());
        title.setTextSize(24f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF2F2A2C);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);

        TextView status = new TextView(activity);
        status.setText(opened ? "Opened" : "New");
        status.setTextSize(11f);
        status.setTextColor(opened ? 0xFF9E9E9E : 0xFF8C4356);
        status.setTypeface(null, opened ? Typeface.NORMAL : Typeface.BOLD);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(textLp);
        textCol.addView(title);
        textCol.addView(status);

        row.addView(dot);
        row.addView(textCol);

        row.setOnClickListener(v -> {
            MaterialReadTracker.markOpened(activity, material.getId());
            close();
            openMaterial(material);
        });

        return row;
    }

    private void openMaterial(LearningMaterial material) {
        if (material.getFileUrl() == null || material.getFileUrl().trim().isEmpty()) {
            Toast.makeText(activity, "Material file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_OPENED_TITLE, material.getTitle())
                .putString(KEY_LAST_OPENED_URL,   material.getFileUrl()).apply();

        if (beforeOpenMaterial != null) beforeOpenMaterial.run();

        Intent intent = new Intent(activity, AccessibleMaterialActivity.class);
        intent.putExtra("material_id",      material.getId());
        intent.putExtra("file_url",         material.getFileUrl());
        intent.putExtra("title",            material.getTitle());
        intent.putExtra("impairment_level", "moderate");
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
