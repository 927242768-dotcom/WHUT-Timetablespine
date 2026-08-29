package com.whut.timetable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 武理教务系统统一认证和课表同步页面。
 *
 * 用户名、密码始终由学校网页自行处理。本 Activity 只在已经登录的官方教务系统页面中，
 * 调用该页面本身正在使用的课表接口，并把结果保存到 App 私有存储。
 */
public class ImportActivity extends Activity {
    private static final String SCHOOL_HOST = "jwxt.whut.edu.cn";
    private static final String SCHOOL_HOME =
            "https://jwxt.whut.edu.cn/jwapp/sys/homeapp/home/index.html?contextPath=/jwapp#/";

    private WebView webView;
    private TextView statusText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean syncInProgress = false;
    private final Runnable autoSyncRunnable = this::startSync;

    private static final String SYNC_SCRIPT = """
            (async () => {
              const bridge = window.WhutImportBridge;
              const base = '/jwapp/sys/homeapp/api/home/';
              const fail = (message) => {
                try { bridge.onData(JSON.stringify({ok:false, message:String(message || '同步失败')})); } catch (_) {}
              };
              const ensureApiResult = (data) => {
                if (data && data.code !== undefined && String(data.code) !== '0') {
                  throw new Error(data.msg || '教务系统返回错误');
                }
                return data;
              };
              const parseResponse = async (response) => {
                const type = (response.headers.get('content-type') || '').toLowerCase();
                if (!response.ok) throw new Error('网络请求失败：' + response.status);
                if (response.redirected || type.includes('text/html')) {
                  throw new Error('登录状态尚未完成或已失效，请先完成统一认证');
                }
                return ensureApiResult(await response.json());
              };
              const get = async (path, params = {}) => {
                const query = new URLSearchParams();
                Object.entries(params).forEach(([key, value]) => {
                  if (value !== undefined && value !== null) query.set(key, String(value));
                });
                const url = base + path + (query.toString() ? '?' + query.toString() : '');
                return parseResponse(await fetch(url, {
                  method: 'GET',
                  credentials: 'include',
                  cache: 'no-cache',
                  headers: {'Fetch-Api': 'true'}
                }));
              };
              const post = async (path, params = {}) => {
                const body = new URLSearchParams();
                Object.entries(params).forEach(([key, value]) => {
                  if (value !== undefined && value !== null) body.set(key, String(value));
                });
                return parseResponse(await fetch(base + path, {
                  method: 'POST',
                  credentials: 'include',
                  cache: 'no-cache',
                  headers: {
                    'Fetch-Api': 'true',
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                  },
                  body: body.toString()
                }));
              };

              try {
                const currentUserResult = await get('currentUser.do');
                const currentUser = currentUserResult && currentUserResult.datas;
                if (!currentUser || !currentUser.userType) {
                  throw new Error('没有读取到当前登录用户，请重新登录');
                }

                const userType = currentUser.userType;
                if (userType !== 'student' && userType !== 'teacher') {
                  throw new Error('当前账号角色暂不支持课表同步：' + userType);
                }

                const termResult = await get('kb/xnxq.do');
                const terms = Array.isArray(termResult.datas) ? termResult.datas : [];
                const term = terms.find(item => item && item.selected) || terms[0];
                if (!term || !term.itemCode) throw new Error('没有读取到当前学期');
                const termCode = term.itemCode;

                const weekResult = await get('getTermWeeks.do', {termCode});
                const weeks = Array.isArray(weekResult.datas) ? weekResult.datas : [];
                const rolePath = userType === 'teacher' ? 'teacher/' : 'student/';

                let sections = [];
                try {
                  const sectionResult = await get(rolePath + 'getSections.do', {
                    termCode,
                    campusCode: ''
                  });
                  sections = Array.isArray(sectionResult.datas) ? sectionResult.datas : [];
                } catch (_) {
                  sections = [];
                }

                const loadWeek = async (week) => {
                  const scheduleResult = await post(rolePath + 'getMyScheduleDetail.do', {
                    termCode,
                    campusCode: '',
                    type: 'week',
                    week: week.serialNumber
                  });
                  const data = scheduleResult.datas || {};
                  const arranged = Array.isArray(data.arrangedList) ? data.arrangedList : [];
                  return {
                    week,
                    items: arranged.map(item => Object.assign({}, item, {
                      _whutWeek: {
                        serialNumber: week.serialNumber,
                        name: week.name,
                        startDate: week.startDate,
                        endDate: week.endDate,
                        curWeek: !!week.curWeek
                      }
                    }))
                  };
                };

                const weekSchedules = [];
                if (weeks.length) {
                  // 每次最多并行四周，既提高速度，也避免对学校服务器产生过大瞬时请求。
                  for (let i = 0; i < weeks.length; i += 4) {
                    const batch = await Promise.all(weeks.slice(i, i + 4).map(loadWeek));
                    weekSchedules.push(...batch);
                  }
                } else {
                  // 极少数特殊学期没有周列表时退化为学期课表。
                  const scheduleResult = await post(rolePath + 'getMyScheduleDetail.do', {
                    termCode,
                    campusCode: '',
                    type: 'term'
                  });
                  const data = scheduleResult.datas || {};
                  weekSchedules.push({
                    week: {serialNumber: 1, name: '本学期', curWeek: true},
                    items: Array.isArray(data.arrangedList) ? data.arrangedList : []
                  });
                }

                let exams = [];
                if (userType === 'student') {
                  try {
                    const examResult = await get('student/exams.do', {termCode});
                    exams = Array.isArray(examResult.datas) ? examResult.datas : [];
                  } catch (_) {
                    exams = [];
                  }
                }

                bridge.onData(JSON.stringify({
                  ok: true,
                  schemaVersion: 2,
                  syncedAt: new Date().toISOString(),
                  userType,
                  term,
                  weeks,
                  sections,
                  weekSchedules,
                  exams
                }));
              } catch (error) {
                fail(error && error.message ? error.message : error);
              }
            })();
            """;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 249, 254));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(0, bars.top, 0, 0);
            return insets;
        });

        root.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        root.addView(webView, webParams);
        setContentView(root);

        configureWebView();
        webView.loadUrl(SCHOOL_HOME);
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.setBackgroundColor(Color.rgb(247, 249, 254));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(34);
        back.setTextColor(Color.rgb(29, 36, 50));
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("统一认证");
        title.setTextSize(17);
        title.setTextColor(Color.rgb(26, 32, 44));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBox.addView(title);

        statusText = new TextView(this);
        statusText.setText("登录成功后将自动同步");
        statusText.setTextSize(12);
        statusText.setTextColor(Color.rgb(116, 125, 145));
        titleBox.addView(statusText);

        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.setMinimumHeight(dp(64));
        return header;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " WhutTimetable/1.2");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new ImportBridge(), "WhutImportBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncInProgress = false;
                handler.removeCallbacks(autoSyncRunnable);
                if (isTrustedSchoolPage()) {
                    statusText.setText("正在确认登录状态");
                    handler.postDelayed(autoSyncRunnable, 650);
                } else {
                    statusText.setText("正在等待登录");
                }
            }
        });
    }

    private void startSync() {
        if (webView == null || syncInProgress) return;
        if (!isTrustedSchoolPage()) {
            statusText.setText("正在等待登录");
            return;
        }
        syncInProgress = true;
        statusText.setText("正在同步课表");
        webView.evaluateJavascript(SYNC_SCRIPT, null);
    }

    private boolean isTrustedSchoolPage() {
        if (webView == null || webView.getUrl() == null) return false;
        Uri uri = Uri.parse(webView.getUrl());
        return "https".equalsIgnoreCase(uri.getScheme())
                && SCHOOL_HOST.equalsIgnoreCase(uri.getHost());
    }

    private final class ImportBridge {
        @JavascriptInterface
        public void onData(String json) {
            runOnUiThread(() -> handleSyncResult(json));
        }
    }

    private void handleSyncResult(String json) {
        if (!isTrustedSchoolPage()) {
            scheduleRetry("正在等待登录");
            return;
        }

        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("ok", false)) {
                scheduleRetry(root.optString("message", "正在等待登录"));
                return;
            }

            File output = new File(getFilesDir(), MainActivity.IMPORT_FILE_NAME);
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            }

            CookieManager.getInstance().flush();
            statusText.setText("同步完成");
            setResult(RESULT_OK, new Intent());
            Toast.makeText(this, "同步成功", Toast.LENGTH_SHORT).show();
            finish();
        } catch (JSONException | IOException e) {
            scheduleRetry("保存失败，正在重试");
        }
    }

    private void scheduleRetry(String message) {
        syncInProgress = false;
        if (statusText != null) {
            String normalized = message == null ? "" : message;
            boolean waitingLogin = normalized.contains("登录") || normalized.contains("认证") || normalized.contains("currentUser");
            statusText.setText(waitingLogin ? "正在等待登录" : "同步未完成，正在重试");
        }
        handler.removeCallbacks(autoSyncRunnable);
        if (isTrustedSchoolPage()) handler.postDelayed(autoSyncRunnable, 1600);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("WhutImportBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
