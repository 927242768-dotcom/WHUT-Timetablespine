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
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
    public static final String EXTRA_SYNC_MODE = "sync_mode";
    public static final String MODE_LIVE = "live";

    private static final String SCHOOL_HOST = "jwxt.whut.edu.cn";
    private static final String SCHOOL_HOME =
            "https://jwxt.whut.edu.cn/jwapp/sys/homeapp/home/index.html?contextPath=/jwapp#/";
    private static final String LIVE_HOST = "classroom.lgzk.whut.edu.cn";
    private static final String LIVE_HOME =
            "https://classroom.lgzk.whut.edu.cn/coursepage?tenant_code=223";
    private static final String LIVE_CAS =
            "https://yjapi.lgzk.whut.edu.cn/casapi/index.php?r=auth/login&auType=cmc&tenant_code=223&forward=";
    private static final int STAGE_TIMETABLE = 0;
    private static final int STAGE_LIVE = 1;
    private static final String LIVE_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36 WhutTimetable/1.6.0";

    private WebView webView;
    private TextView statusText;
    private LinearLayout syncOverlay;
    private TextView syncOverlayTitle;
    private TextView syncOverlayDetail;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean syncInProgress = false;
    private boolean liveOnlyMode = false;
    private int syncStage = STAGE_TIMETABLE;
    private int liveRetryCount = 0;
    private boolean liveLoginLaunched = false;
    private JSONObject pendingImport;
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
                  // 每次最多并行八周：比旧版四周一批明显更快，同时避免一次性向学校服务器发出全部请求。
                  for (let i = 0; i < weeks.length; i += 8) {
                    const batch = await Promise.all(weeks.slice(i, i + 8).map(loadWeek));
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
                  schemaVersion: 3,
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

    /**
     * 智播学堂“我的课程”同步脚本。
     * 登录凭据仅在学校页面内部参与请求，桥接回 App 的结果中不会包含 token/cookie。
     */
    private String buildLiveSyncScript() {
        String weeksJson = "[]";
        if (pendingImport != null && pendingImport.optJSONArray("weeks") != null) {
            weeksJson = pendingImport.optJSONArray("weeks").toString();
        }
        return """
                (async () => {
                  const bridge = window.WhutImportBridge;
                  const sourceWeeks = __WEEKS__;
                  const send = (payload) => {
                    try { bridge.onLiveData(JSON.stringify(payload)); } catch (_) {}
                  };
                  const authError = (message) => {
                    const error = new Error(message || '智播学堂登录尚未完成');
                    error.authRequired = true;
                    throw error;
                  };
                  const getCookie = (name) => {
                    const prefix = name + '=';
                    const entry = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith(prefix));
                    return entry ? entry.slice(prefix.length) : '';
                  };
                  const getToken = () => {
                    let raw = getCookie('_token');
                    try { raw = decodeURIComponent(raw || ''); } catch (_) {}
                    const serialized = raw.match(/"_token";i:\\d+;s:\\d+:"([^"]+)"/);
                    if (serialized && serialized[1]) raw = serialized[1];
                    if (!raw) raw = new URLSearchParams(location.search).get('token') || '';
                    return raw;
                  };
                  const formatDate = (date) => {
                    const pad = n => String(n).padStart(2, '0');
                    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
                  };
                  const normalizeWeeks = () => {
                    const usable = Array.isArray(sourceWeeks) ? sourceWeeks.filter(w => w && w.startDate && w.endDate) : [];
                    if (usable.length) return usable.map(w => ({startDate:String(w.startDate), endDate:String(w.endDate)}));
                    const now = new Date(), day = now.getDay() || 7;
                    const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                    monday.setDate(monday.getDate() - day + 1);
                    const sunday = new Date(monday); sunday.setDate(sunday.getDate() + 6);
                    return [{startDate:formatDate(monday), endDate:formatDate(sunday)}];
                  };
                  const readJson = async (response) => {
                    if (response.status === 401 || response.status === 403) authError('智播学堂登录已失效');
                    if (!response.ok) throw new Error('智播学堂请求失败：' + response.status);
                    const type = (response.headers.get('content-type') || '').toLowerCase();
                    if (type.includes('text/html')) authError('智播学堂正在等待统一认证');
                    return response.json();
                  };

                  try {
                    const userResponse = await fetch('/userapi/v1/infosimple', {
                      credentials:'include', cache:'no-store', headers:{Accept:'application/json'}
                    });
                    const userJson = await readJson(userResponse);
                    const user = userJson && (userJson.params || (userJson.data && userJson.data.params) || userJson.data);
                    if (!user || !user.id) authError('没有读取到智播学堂登录用户');

                    const tenantCode = String(user.tenant_id || user.tenant_code || 223);
                    const token = getToken();
                    if (!token) authError('没有读取到智播学堂登录凭据');
                    const weeks = normalizeWeeks();

                    const loadWeek = async (week) => {
                      const query = new URLSearchParams({
                        user_id:String(user.id),
                        tenant_id:tenantCode,
                        start_at:week.startDate,
                        end_at:week.endDate,
                        token
                      });
                      const response = await fetch('/courseapi/v2/schedule/get-week-schedules?' + query.toString(), {
                        credentials:'include', cache:'no-store', headers:{Accept:'application/json'}
                      });
                      const json = await readJson(response);
                      const days = (json && json.result && Array.isArray(json.result.list)) ? json.result.list :
                        (json && json.data && json.data.result && Array.isArray(json.data.result.list) ? json.data.result.list : []);
                      const out = [];
                      days.forEach(day => {
                        const courses = day && Array.isArray(day.course) ? day.course : [];
                        courses.forEach(item => {
                          if (!item) return;
                          out.push({
                            courseId:String(item.course_id == null ? '' : item.course_id),
                            subId:String(item.id == null ? (item.sub_id == null ? '' : item.sub_id) : item.id),
                            title:String(item.course_title || item.title || '直播课堂'),
                            room:String(item.room_name || ''),
                            teacher:String(item.lecturer_name || item.teacher_name || ''),
                            startAt:Number(item.start_at || 0),
                            endAt:Number(item.end_at || 0),
                            status:Number(item.status || 0),
                            playbackStatus:item.playback_status == null ? null : item.playback_status,
                            multiType:String(item.multi_type || ''),
                            oliveType:String(item.olive_type || item.sub_type || ''),
                            publicType:item.is_public == null ? null : item.is_public,
                            reviewType:item.sub_review_type == null ? null : item.sub_review_type,
                            early:item.early == null ? null : item.early,
                            tenantCode,
                            sourceDay:String((day && (day.day || day.date)) || ''),
                            weekStart:week.startDate,
                            weekEnd:week.endDate
                          });
                        });
                      });
                      return out;
                    };

                    const collected = [];
                    // 直播课堂同样按八周一批并行同步，减少整学期等待时间。
                    for (let i = 0; i < weeks.length; i += 8) {
                      const batch = await Promise.all(weeks.slice(i, i + 8).map(loadWeek));
                      batch.forEach(list => collected.push(...list));
                    }
                    const unique = [];
                    const seen = new Set();
                    collected.forEach(item => {
                      const key = [item.courseId,item.subId,item.startAt,item.endAt].join('|');
                      if (!seen.has(key)) { seen.add(key); unique.push(item); }
                    });
                    unique.sort((a,b) => (a.startAt || 0) - (b.startAt || 0));
                    send({
                      ok:true,
                      syncedAt:new Date().toISOString(),
                      tenantCode,
                      user:{id:String(user.id), name:String(user.realname || user.name || '')},
                      classes:unique
                    });
                  } catch (error) {
                    send({
                      ok:false,
                      authRequired:!!(error && error.authRequired),
                      message:String(error && error.message ? error.message : error || '直播课堂同步失败')
                    });
                  }
                })();
                """.replace("__WEEKS__", weeksJson);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveOnlyMode = MODE_LIVE.equals(getIntent().getStringExtra(EXTRA_SYNC_MODE));
        syncStage = liveOnlyMode ? STAGE_LIVE : STAGE_TIMETABLE;
        if (liveOnlyMode) {
            pendingImport = readExistingImport();
            if (pendingImport == null || pendingImport.optJSONArray("weekSchedules") == null) {
                Toast.makeText(this, "请先同步教务课表，再单独同步直播课堂", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        getWindow().setStatusBarColor(Color.rgb(247, 249, 254));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(0, bars.top, 0, 0);
            return insets;
        });

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        page.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        page.addView(webView, webParams);

        syncOverlay = createSyncOverlay();
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        overlayParams.gravity = Gravity.CENTER;
        overlayParams.setMargins(dp(28), 0, dp(28), 0);
        root.addView(syncOverlay, overlayParams);
        setContentView(root);

        configureWebView();
        if (liveOnlyMode) {
            webView.getSettings().setUserAgentString(LIVE_DESKTOP_UA);
            statusText.setText("正在连接智播学堂");
            webView.loadUrl(LIVE_HOME);
        } else {
            webView.loadUrl(SCHOOL_HOME);
        }
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
        title.setText(liveOnlyMode ? "直播课堂同步" : "统一认证");
        title.setTextSize(17);
        title.setTextColor(Color.rgb(26, 32, 44));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBox.addView(title);

        statusText = new TextView(this);
        statusText.setText(liveOnlyMode ? "登录后只同步智播学堂直播课堂" : "登录成功后将自动同步教务课表");
        statusText.setTextSize(12);
        statusText.setTextColor(Color.rgb(116, 125, 145));
        titleBox.addView(statusText);

        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.setMinimumHeight(dp(64));
        return header;
    }

    private LinearLayout createSyncOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(26), dp(24), dp(26), dp(22));
        box.setVisibility(View.GONE);
        box.setElevation(dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(246, 255, 255, 255));
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.rgb(222, 230, 246));
        box.setBackground(background);

        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressParams.bottomMargin = dp(12);
        box.addView(progress, progressParams);

        syncOverlayTitle = new TextView(this);
        syncOverlayTitle.setTextSize(20);
        syncOverlayTitle.setTextColor(Color.rgb(25, 35, 53));
        syncOverlayTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        syncOverlayTitle.setGravity(Gravity.CENTER);
        box.addView(syncOverlayTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        syncOverlayDetail = new TextView(this);
        syncOverlayDetail.setTextSize(13);
        syncOverlayDetail.setTextColor(Color.rgb(116, 125, 145));
        syncOverlayDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(7);
        box.addView(syncOverlayDetail, detailParams);
        return box;
    }

    private void showSyncOverlay(String title, String detail) {
        if (syncOverlay == null) return;
        syncOverlayTitle.setText(title);
        syncOverlayDetail.setText(detail);
        syncOverlay.setVisibility(View.VISIBLE);
        syncOverlay.bringToFront();
    }

    private void hideSyncOverlay() {
        if (syncOverlay != null) syncOverlay.setVisibility(View.GONE);
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
        settings.setUserAgentString(settings.getUserAgentString() + " WhutTimetable/1.6.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new ImportBridge(), "WhutImportBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleSchoolNavigation(view, request == null ? null : request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleSchoolNavigation(view, url == null ? null : Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncInProgress = false;
                handler.removeCallbacks(autoSyncRunnable);
                hideSyncOverlay();
                if (syncStage == STAGE_TIMETABLE) {
                    if (isTrustedSchoolPage()) {
                        statusText.setText("正在确认教务登录状态");
                        handler.postDelayed(autoSyncRunnable, 350);
                    } else {
                        statusText.setText("正在等待统一认证");
                    }
                } else {
                    if (isTrustedLivePage()) {
                        statusText.setText("正在确认智播学堂登录状态");
                        handler.postDelayed(autoSyncRunnable, 450);
                    } else {
                        statusText.setText("正在完成智播学堂统一认证");
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!liveOnlyMode || request == null || !request.isForMainFrame()) return;
                Uri failed = request.getUrl();
                String host = failed == null ? null : failed.getHost();
                if (host != null && (host.equalsIgnoreCase("yjapi.lgzk.whut.edu.cn")
                        || host.equalsIgnoreCase("classroom.lgzk.whut.edu.cn"))) {
                    hideSyncOverlay();
                    finishLiveImport(false, "智播学堂统一认证连接失败，请稍后在直播课堂页面重试");
                }
            }
        });
    }

    /**
     * 武理部分统一认证链路仍会先返回 http://zhlgd.whut.edu.cn/... 再 301 到 HTTPS。
     * Android 9+ WebView 默认禁止明文 HTTP，因此在真正发出 HTTP 请求前，仅对武汉理工大学域名
     * 原地升级为 HTTPS。这样既兼容学校旧跳转，也无需为整个 App 开启 cleartextTraffic。
     */
    private boolean handleSchoolNavigation(WebView view, Uri uri) {
        if (view == null || uri == null) return false;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("http".equalsIgnoreCase(scheme)
                && host != null
                && (host.equalsIgnoreCase("whut.edu.cn")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".whut.edu.cn"))) {
            Uri secureUri = uri.buildUpon().scheme("https").build();
            if (statusText != null && syncStage == STAGE_LIVE) {
                statusText.setText("正在进入武理统一认证");
            }
            view.loadUrl(secureUri.toString());
            return true;
        }
        return false;
    }

    private void startSync() {
        if (webView == null || syncInProgress) return;
        if (syncStage == STAGE_LIVE) {
            startLiveSync();
            return;
        }
        if (!isTrustedSchoolPage()) {
            statusText.setText("正在等待统一认证");
            return;
        }
        syncInProgress = true;
        statusText.setText("正在同步教务课表");
        showSyncOverlay("正在同步教务课表", "正在读取当前学期、教学周、课程与考试安排…");
        webView.evaluateJavascript(SYNC_SCRIPT, null);
    }

    private void startLiveSync() {
        if (webView == null || syncInProgress) return;
        if (!isTrustedLivePage()) {
            statusText.setText("正在完成智播学堂统一认证");
            return;
        }
        syncInProgress = true;
        statusText.setText("正在同步直播课堂");
        showSyncOverlay("正在同步直播课堂", "正在读取智播学堂“我的课程”…");
        webView.evaluateJavascript(buildLiveSyncScript(), null);
    }

    private boolean isTrustedSchoolPage() {
        if (webView == null || webView.getUrl() == null) return false;
        Uri uri = Uri.parse(webView.getUrl());
        return "https".equalsIgnoreCase(uri.getScheme())
                && SCHOOL_HOST.equalsIgnoreCase(uri.getHost());
    }

    private boolean isTrustedLivePage() {
        if (webView == null || webView.getUrl() == null) return false;
        Uri uri = Uri.parse(webView.getUrl());
        return "https".equalsIgnoreCase(uri.getScheme())
                && LIVE_HOST.equalsIgnoreCase(uri.getHost());
    }

    private String liveLoginUrl() {
        return LIVE_CAS + Uri.encode(LIVE_HOME);
    }

    private final class ImportBridge {
        @JavascriptInterface
        public void onData(String json) {
            runOnUiThread(() -> handleSyncResult(json));
        }

        @JavascriptInterface
        public void onLiveData(String json) {
            runOnUiThread(() -> handleLiveSyncResult(json));
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
            mergeExistingLiveData(root);
            pendingImport = root;
            CookieManager.getInstance().flush();
            finishScheduleImport();
        } catch (JSONException e) {
            scheduleRetry("课表数据解析失败，正在重试");
        }
    }

    private void handleLiveSyncResult(String json) {
        if (!isTrustedLivePage()) {
            scheduleLiveRetry("正在等待智播学堂登录");
            return;
        }
        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok", false)) {
                boolean authRequired = result.optBoolean("authRequired", false);
                String message = result.optString("message", "直播课堂同步未完成");
                syncInProgress = false;
                if (authRequired && !liveLoginLaunched) {
                    liveLoginLaunched = true;
                    statusText.setText("正在通过统一认证登录智播学堂");
                    handler.removeCallbacks(autoSyncRunnable);
                    webView.loadUrl(liveLoginUrl());
                    return;
                }
                scheduleLiveRetry(message);
                return;
            }

            if (pendingImport == null) pendingImport = new JSONObject();
            pendingImport.put("schemaVersion", 3);
            pendingImport.put("liveClassrooms", result.optJSONArray("classes") == null
                    ? new org.json.JSONArray() : result.optJSONArray("classes"));
            pendingImport.put("liveSyncedAt", result.optString("syncedAt", ""));
            pendingImport.put("liveTenantCode", result.optString("tenantCode", "223"));
            pendingImport.put("liveSyncStatus", "ok");
            if (result.optJSONObject("user") != null) {
                pendingImport.put("liveUser", result.optJSONObject("user"));
            }
            finishLiveImport(true, null);
        } catch (JSONException e) {
            scheduleLiveRetry("直播课堂数据解析失败");
        }
    }

    private void scheduleLiveRetry(String message) {
        syncInProgress = false;
        liveRetryCount++;
        handler.removeCallbacks(autoSyncRunnable);
        if (liveRetryCount <= 4 && isTrustedLivePage()) {
            statusText.setText("直播课堂同步未完成，正在重试");
            showSyncOverlay("正在重试直播课堂", "智播学堂暂时没有返回完整数据…");
            handler.postDelayed(autoSyncRunnable, 900);
            return;
        }
        finishLiveImport(false, message);
    }

    private void finishScheduleImport() {
        hideSyncOverlay();
        try {
            writeImport(pendingImport);
            CookieManager.getInstance().flush();
            statusText.setText("教务课表同步完成");
            setResult(RESULT_OK, new Intent());
            Toast.makeText(this, "课表同步成功", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            syncInProgress = false;
            statusText.setText("保存课表失败，请重试");
        }
    }

    private void finishLiveImport(boolean liveSuccess, String liveError) {
        if (isFinishing()) return;
        hideSyncOverlay();
        if (pendingImport == null) pendingImport = readExistingImport();
        if (pendingImport == null) pendingImport = new JSONObject();
        try {
            if (!liveSuccess) {
                pendingImport.put("liveSyncStatus", "error");
                pendingImport.put("liveSyncError", liveError == null ? "直播课堂暂未同步" : liveError);
            } else {
                pendingImport.remove("liveSyncError");
            }
            writeImport(pendingImport);
            CookieManager.getInstance().flush();
            statusText.setText(liveSuccess ? "直播课堂同步完成" : "直播课堂同步失败");
            setResult(RESULT_OK, new Intent());
            Toast.makeText(this,
                    liveSuccess ? "直播课堂同步成功" : "直播课堂同步失败，课表数据不受影响",
                    Toast.LENGTH_SHORT).show();
            finish();
        } catch (JSONException | IOException e) {
            syncInProgress = false;
            statusText.setText("保存直播课堂结果失败，请重试");
        }
    }

    private void writeImport(JSONObject root) throws IOException {
        File output = new File(getFilesDir(), MainActivity.IMPORT_FILE_NAME);
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private JSONObject readExistingImport() {
        File input = new File(getFilesDir(), MainActivity.IMPORT_FILE_NAME);
        if (!input.exists()) return null;
        try (FileInputStream stream = new FileInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void mergeExistingLiveData(JSONObject target) {
        JSONObject existing = readExistingImport();
        if (existing == null) return;
        String[] keys = new String[]{
                "liveClassrooms", "liveSyncedAt", "liveTenantCode", "liveSyncStatus", "liveSyncError", "liveUser"
        };
        for (String key : keys) {
            if (!existing.has(key)) continue;
            try {
                target.put(key, existing.get(key));
            } catch (JSONException ignored) {
            }
        }
    }

    private void scheduleRetry(String message) {
        syncInProgress = false;
        if (statusText != null) {
            String normalized = message == null ? "" : message;
            boolean waitingLogin = normalized.contains("登录") || normalized.contains("认证") || normalized.contains("currentUser");
            statusText.setText(waitingLogin ? "正在等待统一认证" : "课表同步未完成，正在重试");
            if (waitingLogin) hideSyncOverlay();
            else showSyncOverlay("正在重试教务课表", "学校接口暂时没有返回完整数据，正在自动重试…");
        }
        handler.removeCallbacks(autoSyncRunnable);
        if (syncStage == STAGE_TIMETABLE && isTrustedSchoolPage()) {
            handler.postDelayed(autoSyncRunnable, 1600);
        } else if (syncStage == STAGE_LIVE && isTrustedLivePage()) {
            handler.postDelayed(autoSyncRunnable, 900);
        }
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
