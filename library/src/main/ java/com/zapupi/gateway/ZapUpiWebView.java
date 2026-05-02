package com.zapupi.gateway;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ZapUpiWebView {

    private static final String SUCCESS_URL = "https://zapupi.com/payment?s=s";
    private static final String FAILED_URL  = "https://zapupi.com/payment?s=f";
    private static final String TIMEOUT_URL = "https://zapupi.com/payment?s=t";

    public interface OnPaymentResult {
        void onSuccess(String orderId);
        void onFailed(String orderId);
        void onTimeout(String orderId);
        void onCancel(String orderId);
        void onPageLoaded(String url);
    }

    private final Context context;
    private final WebView webView;
    private final FrameLayout container;
    private final OnPaymentResult paymentResult;
    private final OnBackPressedCallback backPressedCallback;

    private String currentOrderId = "";
    private boolean callbackFired = false;

    @SuppressLint("SetJavaScriptEnabled")
    public ZapUpiWebView(Context context, final OnPaymentResult paymentResult) {
        this.context       = context;
        this.paymentResult = paymentResult;

        container = new FrameLayout(context);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        container.setLayoutParams(containerParams);
        container.setBackgroundColor(Color.WHITE);
        container.setVisibility(FrameLayout.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            container.setElevation(10f);
        }

        webView = new WebView(context);
        FrameLayout.LayoutParams webViewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(webViewParams);
        container.addView(webView);

        ViewGroup rootView = ((Activity) context).findViewById(android.R.id.content);
        rootView.addView(container);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(90);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // navigator.share override karne ke liye JS Interface
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void shareImageToGPay(final String base64Data) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            String base64 = base64Data;
                            if (base64.contains(",")) {
                                base64 = base64.substring(base64.indexOf(",") + 1);
                            }

                            byte[] imageBytes = android.util.Base64.decode(
                                base64, android.util.Base64.DEFAULT);

                            File cacheDir = ZapUpiWebView.this.context.getCacheDir();
                            File imageFile = new File(cacheDir, "payment_qr_share.png");
                            FileOutputStream fos = new FileOutputStream(imageFile);
                            fos.write(imageBytes);
                            fos.flush();
                            fos.close();

                            final Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                                ZapUpiWebView.this.context,
                                ZapUpiWebView.this.context.getPackageName() + ".provider",
                                imageFile
                            );

                            final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("image/png");
                            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            shareIntent.setPackage("com.google.android.apps.nbu.paisa.user");

                            ((Activity) ZapUpiWebView.this.context).runOnUiThread(new Runnable() {
                                public void run() {
                                    try {
                                        ZapUpiWebView.this.context.startActivity(shareIntent);
                                    } catch (Exception e) {
                                        shareIntent.setPackage(null);
                                        try {
                                            ZapUpiWebView.this.context.startActivity(
                                                Intent.createChooser(shareIntent, "Share QR"));
                                        } catch (Exception ex) {
                                            showToast("Share failed");
                                        }
                                    }
                                }
                            });

                        } catch (Exception e) {
                            showToast("Share failed: " + e.getMessage());
                        }
                    }
                }).start();
            }
        }, "AndroidShare");

        // Download listener
        webView.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url.startsWith("data:image")) {
                    saveQRToGallery(url);
                } else if (url.startsWith("blob:")) {
                    fetchBlobAsBase64(url);
                } else {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ZapUpiWebView.this.context.startActivity(intent);
                    } catch (Exception e) {
                        showToast("Cannot open file");
                    }
                }
            }
        });

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleUrl(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // about:blank pe kuch mat karo
                if ("about:blank".equals(url)) return;

                // Page load ho gaya — ab visible karo
                container.setVisibility(FrameLayout.VISIBLE);
                backPressedCallback.setEnabled(true);

                // onPageLoaded callback trigger karo
                ZapUpiWebView.this.paymentResult.onPageLoaded(url);

                // navigator.share override
                view.evaluateJavascript(
                    "(function() {" +
                    "  navigator.canShare = function() { return true; };" +
                    "  navigator.share = function(data) {" +
                    "    return new Promise(function(resolve, reject) {" +
                    "      try {" +
                    "        if (data && data.files && data.files.length > 0) {" +
                    "          var file = data.files[0];" +
                    "          var reader = new FileReader();" +
                    "          reader.onloadend = function() {" +
                    "            AndroidShare.shareImageToGPay(reader.result);" +
                    "            resolve();" +
                    "          };" +
                    "          reader.onerror = function() { reject(new Error('Read failed')); };" +
                    "          reader.readAsDataURL(file);" +
                    "        } else {" +
                    "          reject(new Error('No files'));" +
                    "        }" +
                    "      } catch(e) { reject(e); }" +
                    "    });" +
                    "  };" +
                    "})();",
                    null
                );
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // Back press callback
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showCancelDialog();
            }
        };

        ((AppCompatActivity) context).getOnBackPressedDispatcher()
                .addCallback((AppCompatActivity) context, backPressedCallback);
    }

    public void loadPayment(String paymentUrl, String orderId) {
        this.currentOrderId = orderId;
        this.callbackFired  = false;
        container.setVisibility(FrameLayout.GONE);   // onPageFinished pe visible hoga
        backPressedCallback.setEnabled(false);
        setStatusBarBlack();
        webView.loadUrl(paymentUrl);
    }

    public void loadPayment(String paymentUrl) {
        loadPayment(paymentUrl, "");
    }

    private boolean handleUrl(String url) {
        if (url.startsWith(SUCCESS_URL)) {
            closeAndCallback("success");
            return true;
        }
        if (url.startsWith(FAILED_URL)) {
            closeAndCallback("failed");
            return true;
        }
        if (url.startsWith(TIMEOUT_URL)) {
            closeAndCallback("timeout");
            return true;
        }
        if (url.startsWith("upi://")
                || url.startsWith("paytmmp://")
                || url.startsWith("phonepe://")
                || url.startsWith("gpay://")
                || url.startsWith("tez://")
                || url.startsWith("intent://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                context.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    private void closeAndCallback(final String type) {
        if (callbackFired) return;
        callbackFired = true;

        final String orderId = currentOrderId;

        ((Activity) context).runOnUiThread(new Runnable() {
            public void run() {
                webView.stopLoading();
                closeWebView();
                if ("success".equals(type)) {
                    ZapUpiWebView.this.paymentResult.onSuccess(orderId);
                } else if ("failed".equals(type)) {
                    ZapUpiWebView.this.paymentResult.onFailed(orderId);
                } else {
                    ZapUpiWebView.this.paymentResult.onTimeout(orderId);
                }
            }
        });
    }

    public void cancelPayment() {
        if (callbackFired) return;
        callbackFired = true;

        final String orderId = currentOrderId;

        ((Activity) context).runOnUiThread(new Runnable() {
            public void run() {
                webView.stopLoading();
                closeWebView();
                ZapUpiWebView.this.paymentResult.onCancel(orderId);
            }
        });
    }

    private void closeWebView() {
        container.setVisibility(FrameLayout.GONE);
        backPressedCallback.setEnabled(false);
        restoreStatusBar();
        webView.stopLoading();
        webView.loadUrl("about:blank");
    }

    public boolean isVisible() {
        return container.getVisibility() == FrameLayout.VISIBLE;
    }

    private void fetchBlobAsBase64(final String blobUrl) {
        final String js =
            "javascript:(function() {" +
            "  var xhr = new XMLHttpRequest();" +
            "  xhr.open('GET', '" + blobUrl + "', true);" +
            "  xhr.responseType = 'blob';" +
            "  xhr.onload = function() {" +
            "    var reader = new FileReader();" +
            "    reader.onloadend = function() {" +
            "      window.Android.onBlobReceived(reader.result);" +
            "    };" +
            "    reader.readAsDataURL(xhr.response);" +
            "  };" +
            "  xhr.send();" +
            "})();";

        ((Activity) context).runOnUiThread(new Runnable() {
            public void run() {
                webView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onBlobReceived(String base64Data) {
                        saveQRToGallery(base64Data);
                    }
                }, "Android");
                webView.loadUrl(js);
            }
        });
    }

    private void saveQRToGallery(final String base64Data) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String base64 = base64Data;
                    if (base64.contains(",")) {
                        base64 = base64.substring(base64.indexOf(",") + 1);
                    }

                    byte[] imageBytes = android.util.Base64.decode(
                        base64, android.util.Base64.DEFAULT);

                    String fileName = "QR_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(new Date()) + ".png";

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/ZapUPI");
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);

                        Uri uri = ((Activity) context).getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                        if (uri != null) {
                            OutputStream os = ((Activity) context)
                                .getContentResolver().openOutputStream(uri);
                            os.write(imageBytes);
                            os.flush();
                            os.close();

                            values.clear();
                            values.put(MediaStore.Images.Media.IS_PENDING, 0);
                            ((Activity) context).getContentResolver()
                                .update(uri, values, null, null);
                        }
                    } else {
                        File dir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES), "ZapUPI");
                        if (!dir.exists()) dir.mkdirs();

                        File file = new File(dir, fileName);
                        FileOutputStream fos = new FileOutputStream(file);
                        fos.write(imageBytes);
                        fos.flush();
                        fos.close();

                        ((Activity) context).sendBroadcast(new Intent(
                            Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                            Uri.fromFile(file)));
                    }

                    showToast("QR saved to Gallery!");

                } catch (final Exception e) {
                    showToast("Save failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void showToast(final String msg) {
        ((Activity) context).runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showCancelDialog() {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(16));

        GradientDrawable bgDrawable = new GradientDrawable();
        bgDrawable.setColor(Color.WHITE);
        bgDrawable.setCornerRadius(dp(16));
        root.setBackground(bgDrawable);

        TextView title = new TextView(context);
        title.setText("Cancel Payment?");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Color.parseColor("#1C1B1F"));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(12));
        root.addView(title, titleParams);

        TextView message = new TextView(context);
        message.setText("Are you sure you want to cancel this payment?");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setTextColor(Color.parseColor("#49454F"));
        message.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgParams.setMargins(0, 0, 0, dp(24));
        root.addView(message, msgParams);

        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(dp(-24), 0, dp(-24), 0);
        root.addView(divider, dividerParams);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(dp(-24), 0, dp(-24), dp(-16));

        TextView btnCancel = new TextView(context);
        btnCancel.setText("Cancel");
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnCancel.setTextColor(Color.parseColor("#B3261E"));
        btnCancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnCancel.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                cancelPayment();
            }
        });

        View vDivider = new View(context);
        vDivider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams vDividerParams = new LinearLayout.LayoutParams(
                dp(1), ViewGroup.LayoutParams.MATCH_PARENT);

        TextView btnWait = new TextView(context);
        btnWait.setText("Wait");
        btnWait.setGravity(Gravity.CENTER);
        btnWait.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnWait.setTextColor(Color.parseColor("#6750A4"));
        btnWait.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnWait.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams waitParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnWait.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnRow.addView(btnCancel, cancelParams);
        btnRow.addView(vDivider, vDividerParams);
        btnRow.addView(btnWait, waitParams);
        root.addView(btnRow, btnRowParams);

        dialog.setContentView(root);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams lp =
                    dialog.getWindow().getAttributes();
            lp.width = (int) (context.getResources()
                    .getDisplayMetrics().widthPixels * 0.85f);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            dialog.getWindow().setAttributes(lp);
        }

        dialog.show();
    }

    private int dp(int value) {
        return (int) (value * context.getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private void setStatusBarBlack() {
        Activity activity = (Activity) context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setStatusBarColor(Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.getWindow().getDecorView().setSystemUiVisibility(0);
            }
        }
    }

    private void restoreStatusBar() {
        Activity activity = (Activity) context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int originalColor = getThemeStatusBarColor(activity);
            activity.getWindow().setStatusBarColor(originalColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
                if (isColorLight(originalColor)) {
                    flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                activity.getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        }
    }

    private int getThemeStatusBarColor(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            activity.getTheme().resolveAttribute(
                android.R.attr.statusBarColor, typedValue, true);
            if (typedValue.data != 0) return typedValue.data;
            activity.getTheme().resolveAttribute(
                R.attr.colorPrimaryDark, typedValue, true);
            if (typedValue.data != 0) return typedValue.data;
        }
        return Color.BLACK;
    }

    private boolean isColorLight(int color) {
        double darkness = 1 - (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5;
    }
}