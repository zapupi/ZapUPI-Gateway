# ZapUPI Android SDK

ZapUPI payment gateway integration library for Android.

## Installation

### Step 1 — Add JitPack repository

In your root `build.gradle`:
```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2 — Add dependency

In your app `build.gradle`:
```gradle
dependencies {
    implementation 'com.github.YOUR_USERNAME:ZapUPI-Android:1.0.0'
}
```

### Step 3 — Add to AndroidManifest.xml

Inside `<application>` tag:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/provider_paths"/>
</provider>
```

### Step 4 — Add provider_paths.xml

Create `res/xml/provider_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="cache" path="." />
</paths>
```

## Usage

```java
// Initialize
ZapUPI zapUPI = new ZapUPI();
ZapUpiWebView zapUpiWebView = new ZapUpiWebView(this,
    new ZapUpiWebView.OnPaymentResult() {
        public void onSuccess(String orderId) { }
        public void onFailed(String orderId) { }
        public void onTimeout(String orderId) { }
        public void onCancel(String orderId) { }
        public void onPageLoaded(String url) { }
    }
);

// Create Order
HashMap<String, Object> orderData = new HashMap<>();
orderData.put("zap_key", "YOUR_ZAP_KEY");
orderData.put("order_id", "ORDER_123");
orderData.put("amount", "1");
orderData.put("customer_mobile", "9999999999");
orderData.put("remark", "Payment");

zapUPI.createOrder(orderData, new ZapUPI.OnResponse() {
    public void onResponse(String paymentUrl, String orderId, HashMap<String, Object> data) {
        zapUpiWebView.loadPayment(paymentUrl, orderId);
    }
    public void onError(String message) {
        // Handle error
    }
});

// Check Order Status
HashMap<String, Object> statusData = new HashMap<>();
statusData.put("zap_key", "YOUR_ZAP_KEY");
statusData.put("order_id", "ORDER_123");

zapUPI.getOrderStatus(statusData, new ZapUPI.OnResponse() {
    public void onResponse(String paymentUrl, String orderId, HashMap<String, Object> data) {
        String status = String.valueOf(data.get("status"));
        String utr    = String.valueOf(data.get("utr"));
    }
    public void onError(String message) {
        // Handle error
    }
});
```

## License

MIT License
