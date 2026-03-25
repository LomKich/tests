
package com.testsolver;

import android.os.Environment;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {

    private static final String DIR = "HYPE";
    private static final String FILE = "log.txt";
    private static final long MAX_SIZE = 1024 * 1024; // 1MB

    private static File getFile() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE);
    }

    private static void rotate(File file) {
        if (file.length() > MAX_SIZE) {
            File old = new File(file.getParent(), "log_old.txt");
            file.renameTo(old);
        }
    }

    public static void log(String text) {
        try {
            File file = getFile();
            rotate(file);

            FileWriter fw = new FileWriter(file, true);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            fw.append(time + " | " + text + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    public static void error(String text, Exception e) {
        log(text + " ERROR: " + e.toString());
    }
}
