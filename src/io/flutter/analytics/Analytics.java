/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.analytics;

import com.intellij.util.concurrency.QueueProcessor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// TODO(devoncarew): Investigate sending send view activations for Dart or Flutter views.

/**
 * Lightweight Google Analytics integration.
 */
public class Analytics {
  private static final String analyticsUrl = "https://www.google-analytics.com/collect";
  private static final String applicationName = "Flutter IntelliJ Plugin";
  private static final String trackingId = "UA-67589403-7";

  private static final int maxExceptionLength = 512;

  @NotNull
  private final String clientId;
  @NotNull
  private final String pluginVersion;
  private Transport transport = new HttpTransport();
  private final ThrottlingBucket bucket = new ThrottlingBucket(20);
  private boolean myCanSend = false;

  public Analytics(@NotNull String clientId, @NotNull String pluginVersion) {
    this.clientId = clientId;
    this.pluginVersion = pluginVersion;
  }

  public boolean canSend() {
    return myCanSend;
  }

  public void setCanSend(boolean value) {
    this.myCanSend = value;
  }

  /**
   * Public for testing.
   */
  public void setTransport(Transport transport) {
    this.transport = transport;
  }

  public void sendScreenView(@NotNull String viewName) {
    final Map<String, String> args = new HashMap<>();
    args.put("cd", viewName);
    sendPayload("screenview", args);
  }

  public void sendEvent(String category, String action) {
    final Map<String, String> args = new HashMap<>();
    args.put("ec", category);
    args.put("ea", action);
    sendPayload("event", args);
  }

  public void sendTiming(String category, String variable, long timeMillis) {
    final Map<String, String> args = new HashMap<>();
    args.put("utc", category);
    args.put("utv", variable);
    args.put("utt", Long.toString(timeMillis));
    sendPayload("timing", args);
  }

  /**
   * Note: we never send the exception's message here - that can potentially contain PII.
   */
  @SuppressWarnings("SameParameterValue")
  public void sendException(Throwable throwable, boolean isFatal) {
    final StringWriter stringWriter = new StringWriter();
    final PrintWriter printWriter = new PrintWriter(stringWriter);

    printWriter.println(throwable.getClass().getName() + ":");
    for (StackTraceElement element : throwable.getStackTrace()) {
      printWriter.println(element.toString());
    }

    String description = stringWriter.toString().trim();
    if (description.length() > maxExceptionLength) {
      description = description.substring(0, maxExceptionLength);
    }

    final Map<String, String> args = new HashMap<>();
    args.put("exd", description);
    if (isFatal) {
      args.put("'exf'", "1");
    }
    sendPayload("exception", args);
  }

  private void sendPayload(@NotNull String hitType, @NotNull Map<String, String> args) {
    if (!canSend()) {
      return;
    }

    if (!bucket.removeDrop()) {
      return;
    }

    args.put("an", applicationName);
    args.put("av", pluginVersion);

    args.put("v", "1"); // protocol version
    args.put("tid", trackingId);
    args.put("cid", clientId);
    args.put("t", hitType);

    final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    args.put("sr", screenSize.width + "x" + screenSize.height);
    final String language = System.getProperty("user.language");
    if (language != null) {
      args.put("ul", language);
    }

    transport.send(analyticsUrl, args);
  }

  public interface Transport {
    void send(String url, Map<String, String> values);
  }

  private static class HttpTransport implements Transport {
    private final QueueProcessor<Runnable> sendingQueue = QueueProcessor.createRunnableQueueProcessor();

    private static String createUserAgent() {
      final String locale = Locale.getDefault().toString();
      final String os = System.getProperty("os.name");
      return "Mozilla/5.0 (" + os + "; " + os + "; " + os + "; " + locale + ")";
    }

    @Override
    public void send(String url, Map<String, String> values) {
      sendingQueue.add(() -> {
        try {
          final StringBuilder postData = new StringBuilder();
          for (Map.Entry<String, String> param : values.entrySet()) {
            if (postData.length() != 0) {
              postData.append('&');
            }
            postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(param.getValue(), "UTF-8"));
          }
          final byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);
          final HttpURLConnection conn = (HttpURLConnection)new URL(url).openConnection();
          conn.setRequestMethod("POST");
          conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
          conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
          conn.setRequestProperty("User-Agent", createUserAgent());
          conn.setDoOutput(true);
          conn.getOutputStream().write(postDataBytes);

          final InputStream in = conn.getInputStream();
          //noinspection ResultOfMethodCallIgnored
          in.read();
          in.close();
        }
        catch (IOException ignore) {
        }
      });
    }
  }
}