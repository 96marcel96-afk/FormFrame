package de.formframe.bodyprogress;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private static final int FILE_CHOOSER_REQUEST = 42;
    private static final int FOLDER_CHOOSER_REQUEST = 43;
    private static final int BACKUP_SAVE_REQUEST = 44;
    private static final String PREFS = "formframe_settings";
    private static final String FOLDER_URI = "photo_folder_uri";
    private static final String FOLDER_NAME = "photo_folder_name";
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> pendingFileSelection;
    private byte[] pendingBackupBytes;
    private String pendingBackupName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new androidx.webkit.WebViewClientCompat() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.addJavascriptInterface(new FormFrameBridge(), "FormFrameNative");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (pendingFileSelection != null) pendingFileSelection.onReceiveValue(null);
                pendingFileSelection = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException exception) {
                    pendingFileSelection = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean requestsVideo = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) requestsVideo = true;
                }
                if (!requestsVideo) {
                    request.deny();
                    return;
                }
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingWebPermission = request;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                }
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && pendingWebPermission != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && pendingFileSelection != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            pendingFileSelection.onReceiveValue(results);
            pendingFileSelection = null;
            return;
        }
        if (requestCode == BACKUP_SAVE_REQUEST) {
            byte[] bytes = pendingBackupBytes;
            String fileName = pendingBackupName;
            pendingBackupBytes = null;
            pendingBackupName = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && bytes != null) {
                Uri destination = data.getData();
                fileExecutor.execute(() -> {
                    boolean success = false;
                    try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) throw new IllegalStateException("Could not open backup destination");
                        output.write(bytes);
                        output.flush();
                        success = true;
                    } catch (Exception ignored) { }
                    notifyBackupSaved(success, fileName, false);
                });
            } else {
                notifyBackupSaved(false, fileName, true);
            }
            return;
        }
        if (requestCode == FOLDER_CHOOSER_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(tree, flags);
                } catch (SecurityException ignored) { }
                String name = folderDisplayName(tree);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(FOLDER_URI, tree.toString()).putString(FOLDER_NAME, name).apply();
                notifyFolderChanged(name);
            } else {
                notifyFolderChanged(getPhotoFolderName());
            }
        }
    }

    private void requestBackupSave(String dataUrl, String fileName) {
        final byte[] bytes;
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Invalid backup data");
            bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        } catch (Exception exception) {
            notifyBackupSaved(false, fileName, false);
            return;
        }
        runOnUiThread(() -> {
            pendingBackupBytes = bytes;
            pendingBackupName = fileName;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            try {
                startActivityForResult(intent, BACKUP_SAVE_REQUEST);
            } catch (ActivityNotFoundException exception) {
                pendingBackupBytes = null;
                pendingBackupName = null;
                notifyBackupSaved(false, fileName, false);
            }
        });
    }

    private void notifyBackupSaved(boolean success, String fileName, boolean canceled) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(
                "window.onBackupSaved && window.onBackupSaved(" + success + ","
                        + JSONObject.quote(fileName == null ? "" : fileName) + "," + canceled + ")", null));
    }

    private String folderDisplayName(Uri tree) {
        String documentId = DocumentsContract.getTreeDocumentId(tree);
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        try (Cursor cursor = getContentResolver().query(document,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) { }
        int colon = documentId.lastIndexOf(':');
        String fallback = colon >= 0 ? documentId.substring(colon + 1) : documentId;
        return fallback.isEmpty() ? "Ausgewählter Ordner" : fallback;
    }

    private String getPhotoFolderName() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(FOLDER_NAME, "");
    }

    private void notifyFolderChanged(String name) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(
                "window.onPhotoFolderChanged && window.onPhotoFolderChanged(" + JSONObject.quote(name) + ")", null));
    }

    private Uri getFolderTree() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FOLDER_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    private void writePhoto(String dataUrl, String fileName) {
        Uri tree = getFolderTree();
        if (tree == null) {
            notifyPhotoSaved(false, fileName);
            return;
        }
        fileExecutor.execute(() -> {
            boolean success = false;
            try {
                int comma = dataUrl.indexOf(',');
                if (comma < 0) throw new IllegalArgumentException("Invalid image data");
                String header = dataUrl.substring(0, comma);
                String mime = header.startsWith("data:") ? header.substring(5).split(";", 2)[0] : "image/jpeg";
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                ContentResolver resolver = getContentResolver();
                String treeId = DocumentsContract.getTreeDocumentId(tree);
                Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
                deleteNamedChild(tree, fileName);
                Uri file = DocumentsContract.createDocument(resolver, directory, mime, fileName);
                if (file == null) throw new IllegalStateException("Could not create photo file");
                try (OutputStream output = resolver.openOutputStream(file, "w")) {
                    if (output == null) throw new IllegalStateException("Could not open photo file");
                    output.write(bytes);
                    output.flush();
                }
                success = true;
            } catch (Exception ignored) { }
            notifyPhotoSaved(success, fileName);
        });
    }

    private void deleteNamedChild(Uri tree, String fileName) throws Exception {
        ContentResolver resolver = getContentResolver();
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (fileName.equals(cursor.getString(nameColumn))) {
                    Uri existing = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn));
                    DocumentsContract.deleteDocument(resolver, existing);
                }
            }
        }
    }

    private void deletePhotoFile(String fileName) {
        Uri tree = getFolderTree();
        if (tree == null) return;
        fileExecutor.execute(() -> {
            try { deleteNamedChild(tree, fileName); } catch (Exception ignored) { }
        });
    }

    private void notifyPhotoSaved(boolean success, String fileName) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(
                "window.onPhotoSaved && window.onPhotoSaved(" + success + "," + JSONObject.quote(fileName) + ")", null));
    }

    private final class FormFrameBridge {
        @JavascriptInterface public String getPhotoFolderName() { return MainActivity.this.getPhotoFolderName(); }
        @JavascriptInterface public void choosePhotoFolder() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                try { startActivityForResult(intent, FOLDER_CHOOSER_REQUEST); }
                catch (ActivityNotFoundException exception) { notifyFolderChanged(getPhotoFolderName()); }
            });
        }
        @JavascriptInterface public void clearPhotoFolder() {
            Uri oldTree = getFolderTree();
            if (oldTree != null) {
                try { getContentResolver().releasePersistableUriPermission(oldTree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (SecurityException ignored) { }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(FOLDER_URI).remove(FOLDER_NAME).apply();
            notifyFolderChanged("");
        }
        @JavascriptInterface public void savePhoto(String dataUrl, String fileName) { writePhoto(dataUrl, fileName); }
        @JavascriptInterface public void saveBackup(String dataUrl, String fileName) { requestBackupSave(dataUrl, fileName); }
        @JavascriptInterface public void deletePhoto(String fileName) { deletePhotoFile(fileName); }
    }

    @Override
    protected void onDestroy() {
        if (pendingFileSelection != null) {
            pendingFileSelection.onReceiveValue(null);
            pendingFileSelection = null;
        }
        if (webView != null) webView.destroy();
        fileExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
