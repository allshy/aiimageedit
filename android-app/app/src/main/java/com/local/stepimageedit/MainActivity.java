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
import android.provider.DocumentsContract;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_PICK_FOLDER = 1002;
    private static final String API_URL = "https://api.stepfun.com/v1/images/edits";
    private static final String MODEL = "step-image-edit-2";
    private static final String OUTPUT_FOLDER_NAME = "stepfun_outputs";
    private static final int DIMENSION_ALIGNMENT = 16;
    private static final double RATIO_MATCH_TOLERANCE = 0.025;
    private static final RatioSpec[] COMMON_RATIOS = new RatioSpec[]{
            new RatioSpec("9:16", 9, 16),
            new RatioSpec("2:3", 2, 3),
            new RatioSpec("3:4", 3, 4),
            new RatioSpec("1:1", 1, 1),
            new RatioSpec("4:3", 4, 3),
            new RatioSpec("3:2", 3, 2),
            new RatioSpec("16:9", 16, 9)
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object pauseLock = new Object();

    private EditText apiKeyInput;
    private EditText promptInput;
    private CheckBox rememberKeyCheck;
    private CheckBox privatePreviewCheck;
    private TextView keyStatusView;
    private TextView imageInfoView;
    private TextView folderInfoView;
    private TextView statusView;
    private TextView outputPathView;
    private TextView currentInfoView;
    private TextView outputInfoView;
    private ImageView inputPreview;
    private ImageView outputPreview;
    private ProgressBar progressBar;
    private Button editButton;
    private Button batchButton;
    private Button pauseButton;

    private Uri selectedImageUri;
    private Uri selectedFolderUri;
    private String uploadMode = "smart";
    private int maxSide = 4096;
    private int timeoutSeconds = 300;
    private int repeatCount = 1;
    private volatile boolean pauseRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        loadSavedKey();
    }

    @Override
    protected void onDestroy() {
        synchronized (pauseLock) {
            pauseRequested = false;
            pauseLock.notifyAll();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_IMAGE) {
            handlePickedImage(data);
        } else if (requestCode == REQ_PICK_FOLDER) {
            handlePickedFolder(data);
        }
    }

    private void handlePickedImage(Intent data) {
        selectedImageUri = data.getData();
        if (selectedImageUri == null) {
            return;
        }
        takeReadPermission(selectedImageUri, data.getFlags());
        inputPreview.setImageURI(selectedImageUri);
        applyPreviewPrivacy();
        imageInfoView.setText(describeUri(selectedImageUri));
        currentInfoView.setText("当前处理：无");
    }

    private void handlePickedFolder(Intent data) {
        selectedFolderUri = data.getData();
        if (selectedFolderUri == null) {
            return;
        }
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selectedFolderUri, flags);
        } catch (Exception ignored) {
            // Some providers grant temporary access only.
        }
        try {
            List<BatchImage> images = listFolderImages(selectedFolderUri);
            folderInfoView.setText("找到 " + images.size() + " 张图片；输出到 " + OUTPUT_FOLDER_NAME);
        } catch (Exception exc) {
            folderInfoView.setText("文件夹读取失败：" + safeMessage(exc));
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
        subtitle.setText("本地保存 Key，手机端直接编辑或批量处理图片");
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

        panel.addView(label("批量文件夹"), lpMatchWrap(0, 12, 0, 0));
        Button folderButton = secondaryButton("选择文件夹");
        folderButton.setOnClickListener(v -> pickFolder());
        panel.addView(folderButton, lpMatchWrap(0, 6, 0, 8));

        folderInfoView = hint("未选择文件夹");
        panel.addView(folderInfoView);
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

        panel.addView(hint("智能高质量会自动识别比例，并用补边适配模型稳定尺寸；完成后裁回原尺寸。"));
        panel.addView(spinnerRow("上传模式", new String[]{"智能高质量", "高质JPG", "无损PNG", "原图"}, 0,
                (parent, position) -> {
                    if (position == 1) uploadMode = "jpg95";
                    else if (position == 2) uploadMode = "png";
                    else if (position == 3) uploadMode = "original";
                    else uploadMode = "smart";
                }));
        panel.addView(spinnerRow("长边", new String[]{"1536", "2048", "3072", "4096"}, 3,
                (parent, position) -> maxSide = Integer.parseInt((String) parent.getItemAtPosition(position))));
        panel.addView(spinnerRow("超时", new String[]{"75", "120", "180", "300", "600"}, 3,
                (parent, position) -> timeoutSeconds = Integer.parseInt((String) parent.getItemAtPosition(position))));
        panel.addView(spinnerRow("处理次数", new String[]{"1", "2", "3", "4", "5", "10"}, 0,
                (parent, position) -> repeatCount = Integer.parseInt((String) parent.getItemAtPosition(position))));

        LinearLayout row = horizontal();
        editButton = primaryButton("开始编辑");
        editButton.setOnClickListener(v -> startEdit());
        row.addView(editButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        batchButton = secondaryButton("批量处理");
        batchButton.setOnClickListener(v -> startBatchEdit());
        row.addView(batchButton, lpWeighted(1, 8, 0, 0, 0));

        pauseButton = secondaryButton("暂停");
        pauseButton.setEnabled(false);
        pauseButton.setOnClickListener(v -> togglePause());
        row.addView(pauseButton, lpWeighted(0.8f, 8, 0, 0, 0));
        panel.addView(row, lpMatchWrap(0, 10, 0, 0));
        return panel;
    }

    private View buildOutputPanel() {
        LinearLayout panel = panel();
        panel.addView(label("输出"));

        privatePreviewCheck = new CheckBox(this);
        privatePreviewCheck.setText("私密模式：隐藏当前图片和结果图片");
        privatePreviewCheck.setChecked(false);
        privatePreviewCheck.setOnCheckedChangeListener((buttonView, isChecked) -> applyPreviewPrivacy());
        panel.addView(privatePreviewCheck, lpMatchWrap(0, 6, 0, 8));

        outputPreview = new ImageView(this);
        outputPreview.setBackgroundColor(Color.rgb(238, 242, 247));
        outputPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(outputPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
        ));

        outputPathView = hint("还没有结果");
        panel.addView(outputPathView, lpMatchWrap(0, 8, 0, 0));

        currentInfoView = hint("当前处理：无");
        panel.addView(currentInfoView, lpMatchWrap(0, 8, 0, 0));

        outputInfoView = hint("输出图片：无");
        panel.addView(outputInfoView, lpMatchWrap(0, 4, 0, 0));
        return panel;
    }

    private LinearLayout spinnerRow(String title, String[] values, int selected, SelectionHandler handler) {
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
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                handler.onSelected(parent, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT));
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

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    private void startEdit() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String prompt = promptInput.getText().toString().trim();
        if (!validateCommon(apiKey, prompt)) {
            return;
        }
        if (selectedImageUri == null) {
            toast("请先选择图片");
            return;
        }
        maybeSaveKey(apiKey);
        runSingleEditRequest(apiKey, prompt, selectedImageUri, repeatCount);
    }

    private void startBatchEdit() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String prompt = promptInput.getText().toString().trim();
        if (!validateCommon(apiKey, prompt)) {
            return;
        }
        if (selectedFolderUri == null) {
            toast("请先选择批量文件夹");
            return;
        }
        maybeSaveKey(apiKey);
        runBatchRequest(apiKey, prompt, selectedFolderUri, repeatCount);
    }

    private boolean validateCommon(String apiKey, String prompt) {
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return false;
        }
        if (prompt.isEmpty()) {
            toast("请输入提示词");
            return false;
        }
        if (prompt.length() > 512) {
            toast("提示词最多 512 个字符");
            return false;
        }
        return true;
    }

    private void startApiTest() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return;
        }
        maybeSaveKey(apiKey);
        runSingleRequest(() -> {
            byte[] png = createTestPng();
            mainHandler.post(() -> currentInfoView.setText(
                    "当前处理：" + describeOutputBytes("api_test.png", png)
            ));
            UploadFile testFile = new UploadFile("image", "api_test.png", "image/png", png);
            return sendEditRequest(apiKey, "把蓝色方块改成红色方块", testFile, timeoutSeconds);
        });
    }

    private void runSingleRequest(RequestTask task) {
        setBusy(true, "请求中...", false);
        executor.execute(() -> {
            try {
                byte[] result = task.run();
                File out = saveOutput(result);
                mainHandler.post(() -> {
                    setBusy(false, "完成", false);
                    outputPreview.setImageURI(Uri.fromFile(out));
                    applyPreviewPrivacy();
                    outputPathView.setText(out.getAbsolutePath());
                    outputInfoView.setText("输出图片：" + describeFile(out));
                    toast("已保存结果");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "失败", false);
                    toast(safeMessage(e));
                });
            }
        });
    }

    private void runSingleEditRequest(String apiKey, String prompt, Uri imageUri, int repeats) {
        int total = Math.max(1, repeats);
        setBusy(true, "请求中...", false);
        executor.execute(() -> {
            File lastOut = null;
            try {
                for (int attempt = 1; attempt <= total; attempt++) {
                    int currentAttempt = attempt;
                    mainHandler.post(() -> statusView.setText(
                            total == 1 ? "请求中..." : "处理中 " + currentAttempt + "/" + total
                    ));
                    byte[] result = editImage(apiKey, prompt, imageUri);
                    lastOut = saveOutput(result, total == 1 ? "" : String.format(Locale.US, "-%02d", attempt));
                    File displayOut = lastOut;
                    mainHandler.post(() -> {
                        outputPreview.setImageURI(Uri.fromFile(displayOut));
                        applyPreviewPrivacy();
                        outputPathView.setText(displayOut.getAbsolutePath());
                        outputInfoView.setText("输出图片：" + describeFile(displayOut));
                        statusView.setText(total == 1 ? "完成" : "已完成 " + currentAttempt + "/" + total);
                    });
                }
                File finalOut = lastOut;
                mainHandler.post(() -> {
                    setBusy(false, total == 1 ? "完成" : "完成：生成 " + total + " 张", false);
                    if (finalOut != null) {
                        outputPathView.setText(finalOut.getAbsolutePath());
                    }
                    toast(total == 1 ? "已保存结果" : "已保存 " + total + " 张结果");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "失败", false);
                    toast(safeMessage(e));
                });
            }
        });
    }

    private void runBatchRequest(String apiKey, String prompt, Uri folderUri, int repeats) {
        setBusy(true, "准备批量处理...", true);
        executor.execute(() -> {
            int success = 0;
            int totalRepeats = Math.max(1, repeats);
            List<String> failures = new ArrayList<>();
            Uri outputDirUri = null;
            try {
                List<BatchImage> images = listFolderImages(folderUri);
                if (images.isEmpty()) {
                    throw new Exception("文件夹里没有 png/jpg/jpeg/webp 图片");
                }
                outputDirUri = getOrCreateOutputDir(folderUri);
                Uri finalOutputDirUri = outputDirUri;
                mainHandler.post(() -> {
                    folderInfoView.setText("找到 " + images.size() + " 张图片；输出到 " + OUTPUT_FOLDER_NAME);
                    outputPathView.setText(finalOutputDirUri.toString());
                });

                for (int i = 0; i < images.size(); i++) {
                    BatchImage image = images.get(i);
                    for (int repeat = 1; repeat <= totalRepeats; repeat++) {
                        waitIfPaused(image.name);
                        int currentRepeat = repeat;
                        int totalJobs = images.size() * totalRepeats;
                        int currentJob = i * totalRepeats + repeat;
                        mainHandler.post(() -> {
                            statusView.setText("批量处理中 " + currentJob + "/" + totalJobs
                                    + ": " + image.name + " 第 " + currentRepeat + "/" + totalRepeats + " 次");
                            inputPreview.setImageURI(image.uri);
                            applyPreviewPrivacy();
                            currentInfoView.setText("当前处理：" + describeUri(image.uri, image.name, image.size));
                        });
                        try {
                            byte[] result = editImage(apiKey, prompt, image.uri);
                            String outputName = batchOutputName(image.name, currentRepeat, totalRepeats);
                            Uri outUri = saveOutputDocument(outputDirUri, outputName, result);
                            success++;
                            mainHandler.post(() -> {
                                outputPathView.setText(outUri.toString());
                                outputInfoView.setText("输出图片：" + describeOutputBytes(outputName, result));
                            });
                        } catch (Exception exc) {
                            failures.add(image.name + " 第 " + currentRepeat + "/" + totalRepeats
                                    + " 次 | " + safeMessage(exc));
                        }
                    }
                }

                Uri reportUri = null;
                if (!failures.isEmpty()) {
                    reportUri = saveFailureReport(outputDirUri, images.size() * totalRepeats, success, failures);
                }
                Uri finalReportUri = reportUri;
                int finalSuccess = success;
                int totalJobs = images.size() * totalRepeats;
                mainHandler.post(() -> {
                    setBusy(false, "批量完成：成功 " + finalSuccess + " / " + totalJobs
                            + "，失败 " + failures.size(), false);
                    if (failures.isEmpty()) {
                        toast("批量完成，全部成功");
                    } else {
                        toast("批量完成，有 " + failures.size() + " 张失败，失败清单已保存");
                        outputPathView.setText(finalReportUri == null ? outputPathView.getText() : finalReportUri.toString());
                    }
                });
            } catch (InterruptedException exc) {
                mainHandler.post(() -> setBusy(false, "已停止", false));
            } catch (Exception exc) {
                mainHandler.post(() -> {
                    setBusy(false, "失败", false);
                    toast(safeMessage(exc));
                });
            }
        });
    }

    private byte[] editImage(String apiKey, String prompt, Uri imageUri) throws Exception {
        mainHandler.post(() -> currentInfoView.setText("当前处理：" + describeUri(imageUri)));
        PreparedUpload upload = prepareUploadFile(imageUri);
        mainHandler.post(() -> currentInfoView.setText(
                "当前处理：" + describeUri(imageUri) + "\n上传适配：" + upload.summary
        ));
        byte[] result = sendEditRequest(apiKey, prompt, upload.file, timeoutSeconds);
        return cropResultIfNeeded(result, upload);
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

    private PreparedUpload prepareUploadFile(Uri uri) throws Exception {
        String originalName = getDisplayName(uri);
        if (originalName == null || originalName.trim().isEmpty()) {
            originalName = "image.jpg";
        }
        String originalMime = getContentResolver().getType(uri);
        if (originalMime == null) {
            originalMime = "application/octet-stream";
        }

        int[] size = getImageSize(uri);
        boolean canUseOriginal = "original".equals(uploadMode)
                || ("smart".equals(uploadMode)
                && size != null
                && Math.max(size[0], size[1]) <= maxSide
                && isAligned(size[0])
                && isAligned(size[1]));
        if (canUseOriginal) {
            UploadFile file = new UploadFile("image", safeFileName(originalName), originalMime, readAll(open(uri)));
            String summary = "原图上传 " + sizeText(size);
            return PreparedUpload.original(file, size, summary);
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

        NormalizedSize normalized = chooseNormalizedSize(scaled.getWidth(), scaled.getHeight());
        Bitmap uploadBitmap = padBitmap(scaled, normalized.width, normalized.height);
        if (uploadBitmap != scaled) {
            scaled.recycle();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filename;
        String mime;
        boolean forcedPng = normalized.needsPadding || "png".equals(uploadMode);
        boolean usePng = forcedPng || ("smart".equals(uploadMode) && uploadBitmap.hasAlpha());
        if (usePng) {
            uploadBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            filename = "image.png";
            mime = "image/png";
        } else {
            uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            filename = "image.jpg";
            mime = "image/jpeg";
        }
        uploadBitmap.recycle();

        UploadFile file = new UploadFile("image", filename, mime, out.toByteArray());
        String summary = normalized.summary + "，上传 " + normalized.width + "x" + normalized.height
                + "，返回后裁回 " + normalized.contentWidth + "x" + normalized.contentHeight;
        return new PreparedUpload(
                file,
                normalized.contentWidth,
                normalized.contentHeight,
                normalized.width,
                normalized.height,
                normalized.offsetX,
                normalized.offsetY,
                normalized.needsPadding,
                summary
        );
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

    private NormalizedSize chooseNormalizedSize(int width, int height) {
        RatioSpec ratio = nearestCommonRatio(width, height);
        int targetWidth = 0;
        int targetHeight = 0;
        String summary;

        if (ratio != null) {
            int unit = Math.max(ceilDiv(width, ratio.width), ceilDiv(height, ratio.height));
            unit = alignUp(unit, DIMENSION_ALIGNMENT);
            targetWidth = ratio.width * unit;
            targetHeight = ratio.height * unit;
            if (targetWidth <= maxSide && targetHeight <= maxSide) {
                summary = "自动识别 " + ratio.label + "，补边到 16 倍数";
            } else {
                targetWidth = 0;
                targetHeight = 0;
                summary = "自动补边到 16 倍数";
            }
        } else {
            summary = "自动补边到 16 倍数";
        }

        if (targetWidth <= 0 || targetHeight <= 0) {
            targetWidth = Math.min(maxSide, alignUp(width, DIMENSION_ALIGNMENT));
            targetHeight = Math.min(maxSide, alignUp(height, DIMENSION_ALIGNMENT));
        }

        int offsetX = Math.max(0, (targetWidth - width) / 2);
        int offsetY = Math.max(0, (targetHeight - height) / 2);
        boolean needsPadding = targetWidth != width || targetHeight != height;
        if (!needsPadding) {
            summary = "尺寸已稳定";
        }
        return new NormalizedSize(
                targetWidth,
                targetHeight,
                width,
                height,
                offsetX,
                offsetY,
                needsPadding,
                summary
        );
    }

    private RatioSpec nearestCommonRatio(int width, int height) {
        double sourceRatio = width / (double) height;
        RatioSpec best = null;
        double bestDiff = Double.MAX_VALUE;
        for (RatioSpec ratio : COMMON_RATIOS) {
            double value = ratio.width / (double) ratio.height;
            double diff = Math.abs(sourceRatio - value) / value;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = ratio;
            }
        }
        return bestDiff <= RATIO_MATCH_TOLERANCE ? best : null;
    }

    private Bitmap padBitmap(Bitmap source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }
        Bitmap padded = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        if (source.hasAlpha()) {
            canvas.drawColor(Color.TRANSPARENT);
        } else {
            canvas.drawColor(source.getPixel(0, 0));
        }
        int left = Math.max(0, (targetWidth - source.getWidth()) / 2);
        int top = Math.max(0, (targetHeight - source.getHeight()) / 2);
        canvas.drawBitmap(source, left, top, null);
        return padded;
    }

    private byte[] cropResultIfNeeded(byte[] result, PreparedUpload upload) {
        if (!upload.needsCrop) {
            return result;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(result, 0, result.length);
        if (decoded == null) {
            return result;
        }
        try {
            if (decoded.getWidth() == upload.contentWidth && decoded.getHeight() == upload.contentHeight) {
                return result;
            }
            if (decoded.getWidth() < upload.offsetX + upload.contentWidth
                    || decoded.getHeight() < upload.offsetY + upload.contentHeight) {
                return result;
            }
            Bitmap cropped = Bitmap.createBitmap(
                    decoded,
                    upload.offsetX,
                    upload.offsetY,
                    upload.contentWidth,
                    upload.contentHeight
            );
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
            cropped.recycle();
            return out.toByteArray();
        } catch (Exception ignored) {
            return result;
        } finally {
            decoded.recycle();
        }
    }

    private boolean isAligned(int value) {
        return value > 0 && value % DIMENSION_ALIGNMENT == 0;
    }

    private int alignUp(int value, int alignment) {
        return ((value + alignment - 1) / alignment) * alignment;
    }

    private int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
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

    private List<BatchImage> listFolderImages(Uri treeUri) throws Exception {
        List<BatchImage> images = new ArrayList<>();
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
            if (cursor == null) {
                return images;
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                if (!isSupportedImage(name, mime)) {
                    continue;
                }
                Uri imageUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                images.add(new BatchImage(imageUri, name == null ? "image" : name, mime, size));
            }
        }
        return images;
    }

    private boolean isSupportedImage(String name, String mime) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp");
    }

    private Uri getOrCreateOutputDir(Uri treeUri) throws Exception {
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (OUTPUT_FOLDER_NAME.equals(name)
                            && DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    }
                }
            }
        }
        Uri created = DocumentsContract.createDocument(
                getContentResolver(),
                rootDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                OUTPUT_FOLDER_NAME
        );
        if (created == null) {
            throw new Exception("无法创建输出文件夹");
        }
        return created;
    }

    private Uri saveOutputDocument(Uri outputDirUri, String name, byte[] bytes) throws Exception {
        Uri outUri = DocumentsContract.createDocument(
                getContentResolver(),
                outputDirUri,
                "image/png",
                name
        );
        if (outUri == null) {
            throw new Exception("无法创建输出图片");
        }
        try (OutputStream out = getContentResolver().openOutputStream(outUri, "w")) {
            if (out == null) {
                throw new Exception("无法写入输出图片");
            }
            out.write(bytes);
        }
        return outUri;
    }

    private Uri saveFailureReport(Uri outputDirUri, int total, int success, List<String> failures) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        Uri reportUri = DocumentsContract.createDocument(
                getContentResolver(),
                outputDirUri,
                "text/plain",
                "batch-failed-" + stamp + ".txt"
        );
        if (reportUri == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Step Image Edit 2 批量处理失败清单\n\n");
        builder.append("总数: ").append(total).append('\n');
        builder.append("成功: ").append(success).append('\n');
        builder.append("失败: ").append(failures.size()).append("\n\n");
        for (String failure : failures) {
            builder.append(failure).append('\n');
        }
        try (OutputStream out = getContentResolver().openOutputStream(reportUri, "w")) {
            if (out != null) {
                out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return reportUri;
    }

    private void waitIfPaused(String nextName) throws InterruptedException {
        synchronized (pauseLock) {
            while (pauseRequested) {
                mainHandler.post(() -> statusView.setText("已暂停，下一张待处理：" + nextName));
                pauseLock.wait();
            }
        }
    }

    private void togglePause() {
        synchronized (pauseLock) {
            if (pauseRequested) {
                pauseRequested = false;
                pauseButton.setText("暂停");
                statusView.setText("继续处理...");
                pauseLock.notifyAll();
            } else {
                pauseRequested = true;
                pauseButton.setText("继续");
                statusView.setText("已请求暂停：当前图片完成后暂停");
            }
        }
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
        return saveOutput(bytes, "");
    }

    private File saveOutput(byte[] bytes, String suffix) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) {
            dir = getFilesDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建输出目录");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File out = new File(dir, "step-edit-" + stamp + suffix + ".png");
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
        if (!rememberKeyCheck.isChecked()) {
            return;
        }
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

    private void setBusy(boolean busy, String status, boolean allowPause) {
        pauseRequested = false;
        editButton.setEnabled(!busy);
        batchButton.setEnabled(!busy);
        pauseButton.setEnabled(busy && allowPause);
        pauseButton.setText("暂停");
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusView.setText(status);
    }

    private void applyPreviewPrivacy() {
        boolean hidden = privatePreviewCheck != null && privatePreviewCheck.isChecked();
        int visibility = hidden ? View.INVISIBLE : View.VISIBLE;
        if (inputPreview != null) {
            inputPreview.setVisibility(visibility);
        }
        if (outputPreview != null) {
            outputPreview.setVisibility(visibility);
        }
    }

    private String describeUri(Uri uri) {
        return describeUri(uri, getDisplayName(uri), getSize(uri));
    }

    private String describeUri(Uri uri, String name, long knownSize) {
        if (name == null) {
            name = "已选择图片";
        }
        String format = formatName(getContentResolver().getType(uri), name);
        int[] size = getImageSize(uri);
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" | ").append(format);
        if (size != null) {
            builder.append(" | ").append(size[0]).append("x").append(size[1]);
        }
        if (knownSize > 0) {
            builder.append(" | ").append(formatBytes(knownSize));
        }
        return builder.toString();
    }

    private String describeFile(File file) {
        String name = file.getName();
        String format = formatName(null, name);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" | ").append(format);
        if (options.outWidth > 0 && options.outHeight > 0) {
            builder.append(" | ").append(options.outWidth).append("x").append(options.outHeight);
        }
        builder.append(" | ").append(formatBytes(file.length()));
        return builder.toString();
    }

    private String describeOutputBytes(String name, byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, options);
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" | PNG");
        if (options.outWidth > 0 && options.outHeight > 0) {
            builder.append(" | ").append(options.outWidth).append("x").append(options.outHeight);
        }
        builder.append(" | ").append(formatBytes(bytes.length));
        return builder.toString();
    }

    private int[] getImageSize(Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) {
            BitmapFactory.decodeStream(in, null, options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                return new int[]{options.outWidth, options.outHeight};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long getSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private InputStream open(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            throw new Exception("无法打开图片");
        }
        return in;
    }

    private void takeReadPermission(Uri uri, int sourceFlags) {
        int flags = sourceFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some document providers do not grant persistable permissions.
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private String formatName(String mime, String name) {
        if (mime != null && mime.startsWith("image/")) {
            return mime.substring("image/".length()).toUpperCase(Locale.US);
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            return lower.substring(dot + 1).toUpperCase(Locale.US);
        }
        return "UNKNOWN";
    }

    private String formatBytes(long bytes) {
        return String.format(Locale.US, "%.2f MB", bytes / 1024f / 1024f);
    }

    private String sizeText(int[] size) {
        if (size == null || size.length < 2) {
            return "尺寸未知";
        }
        return size[0] + "x" + size[1];
    }

    private String safeFileName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "image.jpg" : cleaned;
    }

    private String safeStem(String name) {
        String cleaned = safeFileName(name);
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0) {
            cleaned = cleaned.substring(0, dot);
        }
        return cleaned.isEmpty() ? "image" : cleaned;
    }

    private String batchOutputName(String sourceName, int repeat, int totalRepeats) {
        String stem = safeStem(sourceName);
        if (totalRepeats <= 1) {
            return stem + "-edited.png";
        }
        return stem + "-edited-" + String.format(Locale.US, "%02d", repeat) + ".png";
    }

    private String safeMessage(Exception exc) {
        String message = exc.getMessage();
        return message == null || message.trim().isEmpty() ? "请求失败" : message;
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
        button.setAllCaps(false);
        button.setBackgroundResource(getResources().getIdentifier("button_primary", "drawable", getPackageName()));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.rgb(30, 42, 58));
        button.setAllCaps(false);
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

    private LinearLayout.LayoutParams lpWeighted(float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
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

    private interface SelectionHandler {
        void onSelected(AdapterView<?> parent, int position);
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

    private static class PreparedUpload {
        final UploadFile file;
        final int contentWidth;
        final int contentHeight;
        final int uploadWidth;
        final int uploadHeight;
        final int offsetX;
        final int offsetY;
        final boolean needsCrop;
        final String summary;

        PreparedUpload(
                UploadFile file,
                int contentWidth,
                int contentHeight,
                int uploadWidth,
                int uploadHeight,
                int offsetX,
                int offsetY,
                boolean needsCrop,
                String summary
        ) {
            this.file = file;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.uploadWidth = uploadWidth;
            this.uploadHeight = uploadHeight;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.needsCrop = needsCrop;
            this.summary = summary;
        }

        static PreparedUpload original(UploadFile file, int[] size, String summary) {
            int width = size == null || size.length < 2 ? 0 : size[0];
            int height = size == null || size.length < 2 ? 0 : size[1];
            return new PreparedUpload(file, width, height, width, height, 0, 0, false, summary);
        }
    }

    private static class NormalizedSize {
        final int width;
        final int height;
        final int contentWidth;
        final int contentHeight;
        final int offsetX;
        final int offsetY;
        final boolean needsPadding;
        final String summary;

        NormalizedSize(
                int width,
                int height,
                int contentWidth,
                int contentHeight,
                int offsetX,
                int offsetY,
                boolean needsPadding,
                String summary
        ) {
            this.width = width;
            this.height = height;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.needsPadding = needsPadding;
            this.summary = summary;
        }
    }

    private static class RatioSpec {
        final String label;
        final int width;
        final int height;

        RatioSpec(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }

    private static class BatchImage {
        final Uri uri;
        final String name;
        final String mime;
        final long size;

        BatchImage(Uri uri, String name, String mime, long size) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }
}
