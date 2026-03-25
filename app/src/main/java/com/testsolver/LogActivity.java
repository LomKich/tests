
package com.testsolver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.content.Intent;
import android.net.Uri;
import java.io.File;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Button btn = new Button(this);
        btn.setText("Export Logs");

        btn.setOnClickListener(v -> {
            File file = new File("/storage/emulated/0/Download/HYPE/log.txt");
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            startActivity(Intent.createChooser(intent, "Send log"));
        });

        setContentView(btn);
    }
}
