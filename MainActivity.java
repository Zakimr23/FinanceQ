package com.financeq.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.app.Notification;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int PERMISSION_REQUEST = 1002;
    private static final int SAVE_FILE_REQUEST = 1003;
    private String pendingSaveContent = null;
    private static final String CHANNEL_ID = "financeq_reminders";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestRuntimePermissions();
        createNotificationChannel();

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                boolean wantsImage = true;
                String[] accept = fileChooserParams != null ? fileChooserParams.getAcceptTypes() : null;
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && (a.contains("json") || a.contains("csv") || a.contains("text"))) {
                            wantsImage = false;
                        }
                    }
                }

                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentIntent);

                if (wantsImage) {
                    contentIntent.setType("image/*");
                    Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    chooserIntent.putExtra(Intent.EXTRA_TITLE, "Pilih Foto atau Kamera");
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ cameraIntent });
                } else {
                    contentIntent.setType("*/*");
                    contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "application/json", "text/plain", "application/octet-stream" });
                    chooserIntent.putExtra(Intent.EXTRA_TITLE, "Pilih File Backup (.json)");
                }

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.CAMERA);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES);
                }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Pengingat Harian FinanceQ",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Pengingat pencatatan keuangan harian");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    private String cleanMime(String mime) {
        if (mime == null || mime.trim().isEmpty()) return "application/octet-stream";
        int i = mime.indexOf(';');
        return (i > 0 ? mime.substring(0, i) : mime).trim();
    }

    public class AndroidBridge {
        private Context ctx;
        AndroidBridge(Context ctx) { this.ctx = ctx; }

        // Dipanggil dari JavaScript: simpan backup/CSV ke folder Download
        @JavascriptInterface
        public void saveFile(final String filename, final String content, final String mime) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, cleanMime(mime));
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("tidak bisa membuat file");
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    toast("Tersimpan di folder Download: " + filename);
                } catch (Exception e) {
                    toast("Gagal menyimpan file: " + e.getMessage());
                }
            } else {
                // Android 9 ke bawah: biar pengguna pilih lokasi simpan (tanpa izin tambahan)
                pendingSaveContent = content;
                final String name = filename;
                final String type = cleanMime(mime);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType(type);
                            i.putExtra(Intent.EXTRA_TITLE, name);
                            startActivityForResult(i, SAVE_FILE_REQUEST);
                        } catch (Exception e) {
                            toast("Gagal membuka pemilih lokasi simpan");
                        }
                    }
                });
            }
        }

        @JavascriptInterface
        public void showNotification(String title, String message) {
            NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);

            builder.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true);

            manager.notify((int) (System.currentTimeMillis() % 10000), builder.build());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_FILE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingSaveContent != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(pendingSaveContent.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    toast("File berhasil disimpan");
                } catch (Exception e) {
                    toast("Gagal menyimpan file: " + e.getMessage());
                }
            }
            pendingSaveContent = null;
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{ Uri.parse(dataString) };
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
