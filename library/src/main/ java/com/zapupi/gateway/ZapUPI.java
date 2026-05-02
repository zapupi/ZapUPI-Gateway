package com.zapupi.gateway;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class ZapUPI {

    private static final String BASE_URL    = "https://pay.zapupi.com/api";
    private static final String SUCCESS_URL = "https://zapupi.com/payment?s=s";
    private static final String FAILED_URL  = "https://zapupi.com/payment?s=f";
    private static final String TIMEOUT_URL = "https://zapupi.com/payment?s=t";

    public interface OnResponse {
        void onResponse(String paymentUrl, String orderId, HashMap<String, Object> data);
        void onError(String message);
    }

    // Create Order
    public void createOrder(final HashMap<String, Object> orderData,
                            final OnResponse callback) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    for (Map.Entry<String, Object> entry : orderData.entrySet()) {
                        body.put(entry.getKey(), entry.getValue());
                    }
                    body.put("success_url", SUCCESS_URL);
                    body.put("failed_url",  FAILED_URL);
                    body.put("timeout_url", TIMEOUT_URL);

                    String responseStr = postRequest("/create-order", body.toString());
                    final JSONObject json = new JSONObject(responseStr);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            if ("success".equals(json.optString("status"))) {
                                String paymentUrl = json.optString("payment_url", "");
                                String orderId    = json.optString("order_id", "");
                                HashMap<String, Object> map = jsonToMap(json);
                                callback.onResponse(paymentUrl, orderId, map);
                            } else {
                                callback.onError(json.optString("message", "Unknown error"));
                            }
                        }
                    });

                } catch (final Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // Order Status
    public void getOrderStatus(final HashMap<String, Object> statusData,
                               final OnResponse callback) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    for (Map.Entry<String, Object> entry : statusData.entrySet()) {
                        body.put(entry.getKey(), entry.getValue());
                    }

                    String responseStr = postRequest("/order-status", body.toString());
                    final JSONObject json = new JSONObject(responseStr);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            if ("success".equals(json.optString("status"))) {
                                String orderId = "";
                                HashMap<String, Object> map = new HashMap<String, Object>();
                                try {
                                    JSONObject order = json.getJSONObject("data");
                                    orderId = order.optString("order_id", "");
                                    map = jsonToMap(order);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                callback.onResponse("", orderId, map);
                            } else {
                                callback.onError(json.optString("message", "Unknown error"));
                            }
                        }
                    });

                } catch (final Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // JSONObject ko HashMap mein convert karo
    private HashMap<String, Object> jsonToMap(JSONObject json) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        try {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.get(key));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    // Common POST helper
    private String postRequest(String endpoint, String jsonBody) throws Exception {
        URL url = new URL(BASE_URL + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        Scanner scanner = new Scanner(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                "UTF-8"
        );

        StringBuilder sb = new StringBuilder();
        while (scanner.hasNextLine()) {
            sb.append(scanner.nextLine());
        }
        scanner.close();
        return sb.toString();
    }
}
