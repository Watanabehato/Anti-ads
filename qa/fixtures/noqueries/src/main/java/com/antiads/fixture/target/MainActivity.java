package com.antiads.fixture.target;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent observation target. Never interprets policy to control sensors. */
public final class MainActivity extends Activity {
    private static final String TAG = "NoQueriesFixture";
    private static final Uri POLICY_URI = Uri.parse("content://com.antiads.app.config");
    private static final int[] TYPES = {1, 4, 9, 10, 11, 5, 8};
    // A stuck Binder call cannot create replacement threads or accumulate requests,
    // even if the Activity is recreated. No Activity is retained by this worker.
    private static final AtomicBoolean QUERY_PENDING = new AtomicBoolean();
    private static final ExecutorService QUERY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "noqueries-manual-read");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile String queryResult;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<SensorRow> rows = new ArrayList<>();
    private final String sessionId = UUID.randomUUID().toString();
    private final long createdAt = SystemClock.elapsedRealtime();
    private SensorManager sensorManager;
    private SensorEventListener listener;
    private TextView lifecycleView;
    private TextView queryView;
    private Button startButton;
    private Button stopButton;
    private Button readButton;
    private boolean resumed;
    private boolean samplingRequested;
    private long registrationSeq;
    private long unregisterCount;
    private long resumedAt;
    private long pausedAt;
    private String lifecycle = "CREATED";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render();
            main.postDelayed(this, 500);
        }
    };

    private static final class SensorRow {
        final int type;
        final Sensor sensor;
        final TextView view;
        String registerResult = "NOT_ATTEMPTED";
        String state;
        long count;
        long lastEventAt;
        long registeredAt;
        long unregisteredAt;
        boolean registered;

        SensorRow(int type, Sensor sensor, TextView view, String discoveryError) {
            this.type = type;
            this.sensor = sensor;
            this.view = view;
            state = discoveryError != null ? discoveryError : sensor == null ? "MISSING" : "IDLE";
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorManager = getSystemService(SensorManager.class);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);
        applyInsets(scroll, content, padding);

        text(content, getString(R.string.title), 22);
        text(content, getString(R.string.description), 15);
        text(content, getString(R.string.identity, getPackageName(), Process.myUid(),
                Process.myPid(), Build.VERSION.SDK_INT, sessionId), 14);
        lifecycleView = text(content, "", 14);
        startButton = button(content, R.string.start);
        stopButton = button(content, R.string.stop);
        startButton.setOnClickListener(v -> startSampling());
        stopButton.setOnClickListener(v -> {
            stopSampling("USER_STOP");
            render();
        });
        for (int type : TYPES) {
            Sensor sensor = null;
            String discoveryError = null;
            try {
                if (sensorManager != null) sensor = sensorManager.getDefaultSensor(type);
                else discoveryError = "SENSOR_MANAGER_UNAVAILABLE";
            } catch (RuntimeException error) {
                discoveryError = "DISCOVERY_ERROR:" + error.getClass().getSimpleName();
            }
            rows.add(new SensorRow(type, sensor, text(content, "", 14), discoveryError));
        }
        text(content, getString(R.string.query_description), 15);
        readButton = button(content, R.string.read_once);
        readButton.setOnClickListener(v -> readPolicyOnce());
        queryView = text(content, "", 14);
        queryView.setTextIsSelectable(true);
        render();
        logLifecycle("CREATE");
    }

    @SuppressWarnings("deprecation") // API29 fallback; API30+ uses typed insets.
    private void applyInsets(ScrollView scroll, LinearLayout content, int padding) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(padding + left, padding + top, padding + right, padding + bottom);
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private TextView text(LinearLayout parent, String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(LinearLayout parent, int label) {
        Button view = new Button(this);
        view.setText(label);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void startSampling() {
        if (!resumed || samplingRequested) return;
        samplingRequested = true;
        final long generation = ++registrationSeq;
        listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (!samplingRequested || generation != registrationSeq) return;
                for (SensorRow row : rows) {
                    if (row.registered && row.type == event.sensor.getType()) {
                        row.count++;
                        row.lastEventAt = SystemClock.elapsedRealtime();
                        break;
                    }
                }
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Accuracy callbacks are not sensor data and do not increment counts.
            }
        };
        for (SensorRow row : rows) {
            row.count = 0;
            row.lastEventAt = 0;
            row.registeredAt = 0;
            row.unregisteredAt = 0;
            if (row.sensor == null || sensorManager == null) continue;
            try {
                row.registered = sensorManager.registerListener(listener, row.sensor,
                        SensorManager.SENSOR_DELAY_NORMAL, main);
                row.registerResult = Boolean.toString(row.registered);
                row.state = row.registered ? "REGISTERED" : "REGISTER_FAILED";
                if (row.registered) row.registeredAt = SystemClock.elapsedRealtime();
            } catch (RuntimeException error) {
                row.registered = false;
                row.registerResult = "THREW:" + error.getClass().getSimpleName();
                row.state = "REGISTER_ERROR";
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logLifecycle("START");
        render();
    }

    private void stopSampling(String reason) {
        if (!samplingRequested) return;
        samplingRequested = false; // Queued callbacks from the old listener are discarded.
        String unregisterError = null;
        try {
            if (sensorManager != null && listener != null) sensorManager.unregisterListener(listener);
        } catch (RuntimeException error) {
            unregisterError = error.getClass().getSimpleName();
        }
        unregisterCount++;
        long now = SystemClock.elapsedRealtime();
        for (SensorRow row : rows) {
            if (row.registered) {
                row.state = unregisterError == null ? "UNREGISTERED:" + reason
                        : "UNREGISTER_ERROR:" + unregisterError;
                row.unregisteredAt = now;
                row.registered = false;
            }
        }
        listener = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logLifecycle(reason);
    }

    private void readPolicyOnce() {
        if (!QUERY_PENDING.compareAndSet(false, true)) return;
        final Context applicationContext = getApplicationContext();
        final long started = SystemClock.elapsedRealtime();
        queryResult = "REQUEST_STARTED elapsed_ms=" + started;
        QUERY_EXECUTOR.execute(() -> {
            String result;
            try {
                // Exactly one call per explicit press. No resolution probes or retries.
                Bundle reply = applicationContext.getContentResolver().call(POLICY_URI,
                        "get_policy_v1", applicationContext.getPackageName(), null);
                result = describeReply(reply);
            } catch (RuntimeException error) {
                result = "EXCEPTION " + error.getClass().getName() + ": "
                        + bounded(error.getMessage());
            }
            long finished = SystemClock.elapsedRealtime();
            queryResult = "method=get_policy_v1\narg=" + applicationContext.getPackageName()
                    + "\nuid=" + Process.myUid() + " pid=" + Process.myPid()
                    + "\nstart_elapsed_ms=" + started + " end_elapsed_ms=" + finished
                    + " duration_ms=" + (finished - started) + "\n" + result;
            QUERY_PENDING.set(false);
        });
        render();
    }

    @SuppressWarnings("deprecation") // Inspect actual primitive types, including malformed replies.
    private static String describeReply(Bundle reply) {
        if (reply == null) return "NULL_BUNDLE";
        StringBuilder result = new StringBuilder("BUNDLE (raw, not a protection verdict)");
        for (String key : new String[]{"ok", "error", "payload"}) {
            result.append('\n').append(key).append('=');
            if (!reply.containsKey(key)) {
                result.append("<absent>");
                continue;
            }
            Object value = reply.get(key);
            if (value == null) result.append("null");
            else if (value instanceof String || value instanceof Boolean) {
                result.append(bounded(value.toString()));
            } else result.append("UNEXPECTED_TYPE:").append(value.getClass().getName());
        }
        return result.toString();
    }

    private static String bounded(String value) {
        if (value == null) return "<null>";
        return value.length() <= 8192 ? value : value.substring(0, 8192) + " [TRUNCATED]";
    }

    private void render() {
        lifecycleView.setText(getString(R.string.lifecycle, lifecycle, resumed, samplingRequested,
                registrationSeq, unregisterCount, createdAt, resumedAt, pausedAt,
                SystemClock.elapsedRealtime()));
        startButton.setEnabled(resumed && !samplingRequested);
        stopButton.setEnabled(samplingRequested);
        for (SensorRow row : rows) {
            String name = row.sensor == null ? getString(R.string.missing_sensor) : row.sensor.getName();
            row.view.setText(getString(R.string.sensor_row, row.type, name,
                    row.sensor != null ? "exists=true" : "exists=false", row.registerResult,
                    row.state, row.count, row.lastEventAt, row.registeredAt, row.unregisteredAt));
        }
        readButton.setEnabled(!QUERY_PENDING.get());
        readButton.setText(QUERY_PENDING.get() ? R.string.query_wait : R.string.read_once);
        String current = queryResult;
        queryView.setText(current == null ? getString(R.string.not_read) : current);
    }

    private void logLifecycle(String event) {
        Log.i(TAG, "event=" + event + " sessionId=" + sessionId + " pid=" + Process.myPid()
                + " registrationSeq=" + registrationSeq + " unregisterCount=" + unregisterCount
                + " resumed=" + resumed + " elapsed_ms=" + SystemClock.elapsedRealtime());
    }

    @Override protected void onResume() {
        super.onResume();
        lifecycle = "RESUMED";
        resumed = true;
        resumedAt = SystemClock.elapsedRealtime();
        logLifecycle("RESUME");
        main.removeCallbacks(refresh);
        main.post(refresh); // Only redraws memory; never polls the Provider.
    }

    @Override protected void onPause() {
        resumed = false;
        lifecycle = "PAUSED";
        pausedAt = SystemClock.elapsedRealtime();
        stopSampling("ON_PAUSE");
        main.removeCallbacks(refresh);
        logLifecycle("PAUSE");
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopSampling("ON_DESTROY");
        main.removeCallbacks(refresh);
        lifecycle = "DESTROYED";
        logLifecycle("DESTROY");
        super.onDestroy();
    }
}
