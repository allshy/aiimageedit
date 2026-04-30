package com.local.stepimageedit;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_IMAGE = 1001;
    private static final String API_URL = "https://api.stepfun.com/v1/images/edits";
    private static final String MODEL = "step-image-edit-2";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText apiKeyInput;
    private EditText promptInput;
    private CheckBox rememberKeyCheck;
    private TextView keyStatusView;
    private TextView imageInfoView;
    private TextView statusView;
    private TextView outputPathView;
    private ImageView inputPreview;
    private ImageView outputPreview;
    private ProgressBar progressBar;
    private Button editButton;

    private Uri selectedImageUri;
    private String uploadMode = "jpg95";
    private int maxSide = 2048;
    private int timeoutSeconds = 180;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        loadSavedKey();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            selectedImageUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (SecurityException ignored) {
                    // Some document providers do not grant persistable permissions.
                }
                inputPreview.setImageURI(selectedImageUri);
                imageInfoView.setText(describeUri(selectedImageUri));
            }
        }
    }

    private View buildContentView() {
        int pad = dp(16);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 247, 249));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Step Image Edit 2");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 27, 38));
        title.setGravity(Gravity.START);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("本地保存 Key，手机端直接编辑图片");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(86, 98, 116));
        root.addView(subtitle, lpMatchWrap(0, 4, 0, 14));

        root.addView(buildApiPanel());
        root.addView(buildImagePanel(), lpMatchWrap(0, 12, 0, 0));
        root.addView(buildPromptPanel(), lpMatchWrap(0, 12, 0, 0));
        root.addView(buildOptionsPanel(), lpMatchWrap(0, 12, 0, 0));
        root.addView(buildOutputPanel(), lpMatchWrap(0, 12, 0, 0));

        statusView = new TextView(this);
        statusView.setText("准备就绪");
        statusView.setTextColor(Color.rgb(70, 82, 98));
        root.addView(statusView, lpMatchWrap(0, 10, 0, 0));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, lpMatchWrap(0, 8, 0, 0));

        return scrollView;
    }

    private View buildApiPanel() {
        LinearLayout panel = panel();
        panel.addView(label("API Key"));

        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setBackgroundResource(getResources().getIdentifier("edit_text_bg", "drawable", getPackageName()));
        panel.addView(apiKeyInput, lpMatchWrap(0, 6, 0, 8));

        LinearLayout row = horizontal();
        rememberKeyCheck = new CheckBox(this);
        rememberKeyCheck.setText("自动保存");
        rememberKeyCheck.setChecked(true);
        row.addView(rememberKeyCheck, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button saveButton = secondaryButton("保存");
        saveButton.setOnClickListener(v -> saveKeyClicked());
        row.addView(saveButton);

        Button clearButton = secondaryButton("清除");
        clearButton.setOnClickListener(v -> clearKeyClicked());
        row.addView(clearButton, lpWrapWrap(8, 0, 0, 0));

        Button testButton = secondaryButton("测试 API");
        testButton.setOnClickListener(v -> startApiTest());
        row.addView(testButton, lpWrapWrap(8, 0, 0, 0));
        panel.addView(row);

        keyStatusView = hint("未保存");
        panel.addView(keyStatusView, lpMatchWrap(0, 8, 0, 0));
        return panel;
    }

    private View buildImagePanel() {
        LinearLayout panel = panel();
        panel.addView(label("原图"));

        Button pickButton = secondaryButton("选择图片");
        pickButton.setOnClickListener(v -> pickImage());
        panel.addView(pickButton, lpMatchWrap(0, 6, 0, 8));

        imageInfoView = hint("未选择图片");
        panel.addView(imageInfoView);

        inputPreview = new ImageView(this);
        inputPreview.setBackgroundColor(Color.rgb(238, 242, 247));
        inputPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        panel.addView(inputPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
        ));
        return panel;
    }

    private View buildPromptPanel() {
        LinearLayout panel = panel();
        panel.addView(label("编辑提示词"));

        promptInput = new EditText(this);
        promptInput.setMinLines(4);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        promptInput.setText("把背景换成干净的白色摄影棚，保持主体不变");
        promptInput.setBackgroundResource(getResources().getIdentifier("edit_text_bg", "drawable", getPackageName()));
        panel.addView(promptInput, lpMatchWrap(0, 6, 0, 0));
        return panel;
    }

    private View buildOptionsPanel() {
        LinearLayout panel = panel();
        panel.addView(label("上传与超时"));

        panel.addView(hint("原图最保真；高质JPG更稳；无损PNG避免 JPG 压缩但文件可能更大。"));
        panel.addView(spinnerRow("上传模式", new String[]{"高质JPG", "无损PNG", "原图"}, 0,
                (parent, view, position, id) -> {
                    if (position == 1) uploadMode = "png";
                    else if (position == 2) uploadMode = "original";
                    else uploadMode = "jpg95";
                }));
        panel.addView(spinnerRow("长边", new String[]{"1536", "2048", "3072", "4096"}, 1,
                (parent, view, position, id) -> maxSide = Integer.parseInt((String) parent.getItemAtPosition(position))));
        panel.addView(spinnerRow("超时", new String[]{"75", "120", "180", "300", "600"}, 2,
                (parent, view, position, id) -> timeoutSeconds = Integer.parseInt((String) parent.getItemAtPosition(position))));

        editButton = primaryButton("开始编辑");
        editButton.setOnClickListener(v -> startEdit());
        panel.addView(editButton, lpMatchWrap(0, 10, 0, 0));
        return panel;
    }

    private View buildOutputPanel() {
        LinearLayout panel = panel();
        panel.addView(label("输出"));

        outputPreview = new ImageView(this);
        outputPreview.setBackgroundColor(Color.rgb(238, 242, 247));
        outputPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(outputPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
        ));

        outputPathView = hint("还没有结果");
        panel.addView(outputPathView, lpMatchWrap(0, 8, 0, 0));
        return panel;
    }

    private LinearLayout spinnerRow(String title, String[] values, int selected, AdapterView.OnItemSelectedListener listener) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(15);
        text.setTextColor(Color.rgb(30, 42, 58));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(listener);
        row.addView(spinner, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    private void startEdit() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String prompt = promptInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return;
        }
        if (selectedImageUri == null) {
            toast("请先选择图片");
            return;
        }
        if (prompt.isEmpty()) {
            toast("请输入提示词");
            return;
        }
        if (prompt.length() > 512) {
            toast("提示词最多 512 个字符");
            return;
        }
        maybeSaveKey(apiKey);
        runRequest(() -> editImage(apiKey, prompt, selectedImageUri, false));
    }

    private void startApiTest() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return;
        }
        maybeSaveKey(apiKey);
        runRequest(() -> {
            UploadFile testFile = new UploadFile("image", "api_test.png", "image/png", createTestPng());
            return sendEditRequest(apiKey, "把蓝色方块改成红色方块", testFile, timeoutSeconds);
        });
    }

    private void runRequest(RequestTask task) {
        setBusy(true, "请求中...");
        executor.execute(() -> {
            try {
                byte[] result = task.run();
                File out = saveOutput(result);
                mainHandler.post(() -> {
                    setBusy(false, "完成");
                    outputPreview.setImageURI(Uri.fromFile(out));
                    outputPathView.setText(out.getAbsolutePath());
                    toast("已保存结果");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "失败");
                    toast(e.getMessage() == null ? "请求失败" : e.getMessage());
                });
            }
        });
    }

    private byte[] editImage(String apiKey, String prompt, Uri imageUri, boolean isTest) throws Exception {
        UploadFile file = prepareUploadFile(imageUri);
        return sendEditRequest(apiKey, prompt, file, timeoutSeconds);
    }

    private byte[] sendEditRequest(String apiKey, String prompt, UploadFile file, int timeout) throws Exception {
        String boundary = "----step-android-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        addFormField(body, boundary, "model", MODEL);
        addFormField(body, boundary, "prompt", prompt);
        addFormField(body, boundary, "response_format", "b64_json");
        addFormField(body, boundary, "cfg_scale", "1.0");
        addFormField(body, boundary, "steps", "8");
        addFileField(body, boundary, file);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setConnectTimeout(timeout * 1000);
        conn.setReadTimeout(timeout * 1000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            body.writeTo(os);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(readAll(stream), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + response);
        }
        return parseImageResponse(response);
    }

    private UploadFile prepareUploadFile(Uri uri) throws Exception {
        if ("original".equals(uploadMode)) {
            String name = getDisplayName(uri);
            if (name == null || name.trim().isEmpty()) name = "image.jpg";
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            return new UploadFile("image", safeFileName(name), mime, readAll(open(uri)));
        }

        Bitmap source;
        try (InputStream in = open(uri)) {
            source = BitmapFactory.decodeStream(in);
        }
        if (source == null) {
            throw new Exception("无法读取图片");
        }

        Bitmap scaled = scaleBitmap(source, maxSide);
        if (scaled != source) {
            source.recycle();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filename;
        String mime;
        if ("png".equals(uploadMode)) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            filename = "image.png";
            mime = "image/png";
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, 95, out);
            filename = "image.jpg";
            mime = "image/jpeg";
        }
        scaled.recycle();
        return new UploadFile("image", filename, mime, out.toByteArray());
    }

    private Bitmap scaleBitmap(Bitmap source, int maxSideValue) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSideValue) {
            return source;
        }
        float scale = maxSideValue / (float) longest;
        int outWidth = Math.max(1, Math.round(width * scale));
        int outHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, outWidth, outHeight, true);
    }

    private byte[] parseImageResponse(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray data = root.getJSONArray("data");
        if (data.length() == 0) {
            throw new Exception("API 没有返回图片数据");
        }
        JSONObject item = data.getJSONObject(0);
        if (item.has("b64_json")) {
            return Base64.decode(item.getString("b64_json"), Base64.DEFAULT);
        }
        if (item.has("url")) {
            HttpURLConnection conn = (HttpURLConnection) new URL(item.getString("url")).openConnection();
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            return readAll(conn.getInputStream());
        }
        throw new Exception("API 响应里没有 b64_json 或 url");
    }

    private void addFormField(ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void addFileField(ByteArrayOutputStream body, String boundary, UploadFile file) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + file.fieldName + "\"; filename=\"" + file.filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + file.mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file.bytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] createTestPng() {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(246, 248, 250));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(42, 126, 214));
        canvas.drawRect(72, 72, 184, 184, paint);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private File saveOutput(byte[] bytes) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建输出目录");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "step-edit-" + stamp + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        return out;
    }

    private void loadSavedKey() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String key = prefs.getString("api_key", "");
        apiKeyInput.setText(key);
        keyStatusView.setText(key.isEmpty() ? "未保存" : "已从本地读取");
    }

    private void maybeSaveKey(String key) {
        if (!rememberKeyCheck.isChecked()) return;
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putString("api_key", key)
                .apply();
        keyStatusView.setText("已保存到手机本地");
    }

    private void saveKeyClicked() {
        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            toast("API Key 为空");
            return;
        }
        maybeSaveKey(key);
        toast("已保存");
    }

    private void clearKeyClicked() {
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .remove("api_key")
                .apply();
        apiKeyInput.setText("");
        keyStatusView.setText("已清除");
        toast("已清除本地 Key");
    }

    private void setBusy(boolean busy, String status) {
        editButton.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusView.setText(status);
    }

    private String describeUri(Uri uri) {
        String name = getDisplayName(uri);
        long size = getSize(uri);
        if (name == null) name = "已选择图片";
        if (size > 0) {
            return name + " | " + String.format(Locale.US, "%.2f MB", size / 1024f / 1024f);
        }
        return name;
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long getSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private InputStream open(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new Exception("无法打开图片");
        return in;
    }

    private byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private String safeFileName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "image.jpg" : cleaned;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(getResources().getIdentifier("panel_bg", "drawable", getPackageName()));
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        return panel;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16);
        label.setTextColor(Color.rgb(20, 27, 38));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private TextView hint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(86, 98, 116));
        return hint;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setBackgroundResource(getResources().getIdentifier("button_primary", "drawable", getPackageName()));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.rgb(30, 42, 58));
        button.setBackgroundResource(getResources().getIdentifier("button_secondary", "drawable", getPackageName()));
        return button;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return lp;
    }

    private LinearLayout.LayoutParams lpWrapWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private interface RequestTask {
        byte[] run() throws Exception;
    }

    private static class UploadFile {
        final String fieldName;
        final String filename;
        final String mimeType;
        final byte[] bytes;

        UploadFile(String fieldName, String filename, String mimeType, byte[] bytes) {
            this.fieldName = fieldName;
            this.filename = filename;
            this.mimeType = mimeType;
            this.bytes = bytes;
        }
    }
}
