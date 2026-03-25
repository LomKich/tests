package com.testsolver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.InputStream;

public class AddQuestionActivity extends AppCompatActivity {

    private static final int PICK_IMAGE = 101;
    private static final int REQ_PERM   = 102;

    private ImageView ivPreview;
    private EditText  etQuestion, etAnswer;
    private Spinner   spinType;
    private Button    btnPick, btnSave;
    private TextView  tvHint;
    private ProgressBar progressBar;

    private TextRecognizer recognizer;
    private AnswerDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_question);

        recognizer  = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        db          = new AnswerDatabase();
        db.load(this);

        ivPreview   = findViewById(R.id.iv_preview);
        etQuestion  = findViewById(R.id.et_question);
        etAnswer    = findViewById(R.id.et_answer);
        spinType    = findViewById(R.id.spin_type);
        btnPick     = findViewById(R.id.btn_pick_image);
        btnSave     = findViewById(R.id.btn_save_question);
        tvHint      = findViewById(R.id.tv_hint);
        progressBar = findViewById(R.id.progress_bar);

        String[] types = {"text", "radio", "checkbox", "match"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, types);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinType.setAdapter(adapter);

        btnPick.setOnClickListener(v -> checkPermAndPick());
        btnSave.setOnClickListener(v -> saveQuestion());
    }

    // ─── Permission ───────────────────────────────────────────────────────────

    private void checkPermAndPick() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openGallery();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openGallery();
        } else {
            Toast.makeText(this, "Нужен доступ к галерее", Toast.LENGTH_LONG).show();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    // ─── Image picking ────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            processImage(data.getData());
        }
    }

    private void processImage(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        tvHint.setText("Распознаю текст...");
        btnPick.setEnabled(false);
        btnSave.setEnabled(false);

        try {
            InputStream is = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (is != null) is.close();

            // Preview (scaled down)
            Bitmap preview = Bitmap.createScaledBitmap(bmp,
                    800, (int)(bmp.getHeight() * 800f / bmp.getWidth()), true);
            ivPreview.setImageBitmap(preview);
            ivPreview.setVisibility(View.VISIBLE);

            InputImage image = InputImage.fromBitmap(bmp, 0);
            recognizer.process(image)
                .addOnSuccessListener(result -> {
                    String cleaned = cleanOcr(result.getText());
                    etQuestion.setText(cleaned);
                    tvHint.setText("✅ Текст распознан — отредактируй вопрос и напиши ответ");
                    progressBar.setVisibility(View.GONE);
                    btnPick.setEnabled(true);
                    btnSave.setEnabled(true);
                })
                .addOnFailureListener(e -> {
                    tvHint.setText("❌ OCR не удался: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    btnPick.setEnabled(true);
                    btnSave.setEnabled(true);
                });

        } catch (Exception e) {
            tvHint.setText("❌ Не удалось открыть файл");
            progressBar.setVisibility(View.GONE);
            btnPick.setEnabled(true);
            btnSave.setEnabled(true);
        }
    }

    /** Strip ads, short noise lines, question-number markers like "34 из 55" */
    private String cleanOcr(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.length() < 4) continue;
            if (t.matches(".*[Рр]еклама.*")) continue;
            if (t.matches("\\d{1,2}\\s+(из|иэ)\\s+\\d{1,2}.*")) continue;
            if (t.matches(".*onlinetestpad.*")) continue;
            sb.append(t).append(" ");
        }
        return sb.toString().trim().toLowerCase();
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    private void saveQuestion() {
        String question = etQuestion.getText().toString().trim();
        String answer   = etAnswer.getText().toString().trim();
        String type     = (String) spinType.getSelectedItem();

        if (question.isEmpty()) {
            Toast.makeText(this, "Введи текст вопроса", Toast.LENGTH_SHORT).show();
            return;
        }
        if (answer.isEmpty()) {
            Toast.makeText(this, "Введи ответ", Toast.LENGTH_SHORT).show();
            return;
        }

        db.addUserAnswer(this, question, type, answer);
        Toast.makeText(this, "✅ Вопрос сохранён!", Toast.LENGTH_SHORT).show();

        etQuestion.setText("");
        etAnswer.setText("");
        ivPreview.setVisibility(View.GONE);
        tvHint.setText("Добавлено! Выбери следующий скриншот или нажми назад.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.close();
    }
}
