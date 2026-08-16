package com.deepseek.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String TAG = "DeepSeekHarness";
    private static final String URL_HOME = "http://127.0.0.1:3080";
    // bin.js 相对 dshroot 目录的路径（dshroot 可能位于外部公共目录或内部 fallback）
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    // 外部 dshroot 公共目录名（挂在 /sdcard 下，卸载不丢；node 二进制/凭证仍留内部）
    private static final String EXT_DSHROOT_ROOT = "DeepSeekHarness";
    // 官方维护、需随 APK 更新的路径前缀：即使外部 dshroot 已有同名文件也强制覆盖
    // （避免"保留 AI 修改"策略挡住官方修复，例如 shizuku 插件的三层补丁）。
    private static final String[] FORCE_OVERWRITE_PREFIXES = {
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-android/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
    };
    // 外部 dshroot 解压完成标记（App 在 dshroot 补齐后写入；清空/重置时随目录删除）。
    // 用于识别「解压中途被打断」：即使 REVISION 一致也强制补齐缺失文件。
    private static final String DSHROOT_COMPLETE = ".complete";
    private static final String PREFS = "dsh_setup";
    private static final int REQ_STORAGE = 200;
    private static final int REQ_NOTIFICATION = 201;
    private static final int REQ_SHIZUKU = 300;

    private WebView webView;
    private TextView statusView;
    private ProgressBar progressBar;
    private ImageView splashLogo;
    private TextView splashBrand;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 运行时确定的 dshroot 目录（外部公共目录优先，失败回退内部 files/payload/dshroot）
    private File dshrootDir = null;
    private boolean watchdogStarted = false;
    private long lastRespawnAt = 0L;
    // 引擎 node 进程（控制台"重启引擎"用）
    private Process nodeProcess = null;

    // 权限界面
    private final List<PermRow> permRows = new ArrayList<>();
    private File rishDex;


    private interface StatusProvider { boolean granted(); }
    private static class PermRow {
        TextView status;
        StatusProvider provider;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);
        webView.setBackgroundColor(Color.parseColor("#0b0f1a"));
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            private int errorRetries = 0;

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                         android.webkit.WebResourceError error) {
                // 主框架加载失败（如 ERR_CONNECTION_REFUSED）时自动重试，直到服务器就绪
                if (request != null && request.isForMainFrame() && errorRetries < 120) {
                    errorRetries++;
                    final WebView wv = view;
                    view.postDelayed(new Runnable() {
                        @Override public void run() { wv.loadUrl(URL_HOME); }
                    }, 2500L);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                errorRetries = 0;
                injectMobileAssets(view);
            }
        });

        statusView = new TextView(this);
        statusView.setText("正在启动 DeepSeek Harness…");
        statusView.setTextColor(Color.parseColor("#e6edf3"));
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(12), dp(24), dp(12));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);

        // 提取 rish dex（DSH 的 shizuku_shell 插件执行命令用，与 payload 解压解耦）
        rishDex = extractRishDex();

        // Shizuku API：监听 binder 与授权结果（实现授权弹窗）
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() { probeShizuku(); }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() { shizukuOk = false; refreshAllStatuses(); }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        shizukuOk = true;
                    }
                    refreshAllStatuses();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listener init failed", t);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("setup_done", false)) {
            showEngineScreen();
            startEngine();
        } else {
            showPermissionScreen();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().scaledDensity);
    }

    // ============ 权限引导界面 ============
    private void detachView(View v) {
        if (v != null && v.getParent() != null) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private void showEngineScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0f1a"));
        // 成员视图（webView/statusView/progressBar）可能已挂在旧容器上，先全部摘下，避免重复挂载崩溃。
        detachView(webView);
        detachView(statusView);
        detachView(progressBar);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        // 鲸鱼 logo
        splashLogo = new ImageView(this);
        splashLogo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(92), dp(92));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.bottomMargin = dp(22);
        box.addView(splashLogo, llp);

        // 品牌名
        splashBrand = new TextView(this);
        splashBrand.setText("DeepSeek Harness");
        splashBrand.setTextColor(Color.parseColor("#f0f6fc"));
        splashBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        splashBrand.setTypeface(null, android.graphics.Typeface.BOLD);
        splashBrand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = dp(26);
        box.addView(splashBrand, blp);

        // 状态文字
        box.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 进度条（深色主题：亮蓝进度 + 暗灰轨道）
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(Color.parseColor("#4d6bfe"));
        progressBar.setProgressTintList(tint);
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#1f2733")));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(260), dp(6));
        pbp.topMargin = dp(18);
        pbp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(progressBar, pbp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        root.addView(box, bp);

        // 浮动退出按钮（右上角）：点击确认后退出 deepdive
        Button exitBtn = new Button(this);
        exitBtn.setText("退出");
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        exitBtn.setTextColor(Color.WHITE);
        exitBtn.setAllCaps(false);
        exitBtn.setBackgroundColor(Color.parseColor("#66000000"));
        exitBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams ebp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ebp.gravity = Gravity.TOP | Gravity.END;
        ebp.topMargin = dp(28);
        ebp.rightMargin = dp(12);
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmExit(); }
        });
        root.addView(exitBtn, ebp);

        setContentView(root);
    }

    /** 退出确认对话框（浮动按钮与系统返回键共用） */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出 deepdive")
                .setMessage("确定要退出吗？服务器将停止运行。")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============ 控制台（替换首次权限页，跟随系统深/浅色）============
    private boolean isDark() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }
    private int cBg() { return Color.parseColor(isDark() ? "#0b0f1a" : "#f7f8fb"); }
    private int cCard() { return Color.parseColor(isDark() ? "#161c2a" : "#ffffff"); }
    private int cText() { return Color.parseColor(isDark() ? "#e6edf3" : "#1f2328"); }
    private int cSub() { return Color.parseColor(isDark() ? "#8b98a9" : "#6b7280"); }
    private int cGreen() { return Color.parseColor("#1f9d6b"); }
    private int cRed() { return Color.parseColor("#d9503f"); }

    private long deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        long total = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) total += deleteRecursive(c);
        }
        total += f.length();
        if (!f.delete()) {
            // 删除失败（通常是目录仍非空，因子项删除失败）。再递归扫一遍重试。
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) for (File c : children) total += deleteRecursive(c);
            }
            f.delete();
        }
        return total;
    }

    // 捕获未处理异常，写到外部崩溃日志（便于无 adb 时排查闪退）
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), EXT_DSHROOT_ROOT);
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "crash.log");
                    FileOutputStream fos = new FileOutputStream(f, true);
                    String s = "\n==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + " thread=" + t.getName() + " ====\n";
                    fos.write(s.getBytes("UTF-8"));
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    fos.write(sw.toString().getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    // 清理外部公共目录下遗留的 .trash-* 垃圾目录（清空数据 rename 后后台删除未完成）。
    private void cleanupTrashDirs(File externalRoot) {
        File[] children = externalRoot.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory() && c.getName().startsWith(".trash-")) {
                deleteRecursive(c);
            }
        }
    }

    private void showPermissionScreen() {
        permRows.clear();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(this);
        title.setText("首次使用 · 配置手机权限");
        title.setTextColor(cText());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("在进入 DeepSeek Harness 之前，请先授权以下能力。\n配好后点底部「开始使用」才会解压运行时。");
        subtitle.setTextColor(cSub());
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        col.addView(subtitle);

        // 权限项
        addPermRow(col, "存储权限", "读写手机文件、导入导出内容。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
                                && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPermissions(new String[]{
                                "android.permission.READ_EXTERNAL_STORAGE",
                                "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                    }
                });

        addPermRow(col, "所有文件访问", "访问手机所有文件（Android 11 及以上需单独授权，11 以下由存储权限覆盖）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT >= 30) {
                            return Environment.isExternalStorageManager();
                        } else {
                            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
                        }
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                } catch (Exception e2) {
                                    Log.w(TAG, "无法打开所有文件访问设置", e2);
                                }
                            }
                        } else {
                            requestPermissions(new String[]{
                                    "android.permission.READ_EXTERNAL_STORAGE",
                                    "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                        }
                    }
                });

        addPermRow(col, "悬浮窗", "让 AI 和工具能在其它应用之上显示内容。",
                new StatusProvider() {
                    @Override public boolean granted() { return Settings.canDrawOverlays(MainActivity.this); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    }
                });

        addPermRow(col, "修改系统设置", "允许读写系统设置（亮度、音量、常亮等）。",
                new StatusProvider() {
                    @Override public boolean granted() { return Settings.System.canWrite(MainActivity.this); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    }
                });

        addPermRow(col, "使用情况访问", "查看应用使用时长与统计信息。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
                        return mode == AppOpsManager.MODE_ALLOWED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    }
                });

        addPermRow(col, "安装未知来源应用", "允许安装 APK（侧载、AI 帮你装应用）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT < 26) return true;
                        return getPackageManager().canRequestPackageInstalls();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    }
                });

        addPermRow(col, "忽略电池优化", "后台常驻不被系统杀掉（保持服务在线）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        return pm.isIgnoringBatteryOptimizations(getPackageName());
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    }
                });

        addPermRow(col, "通知权限", "接收 AI 完成、提醒等通知。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT < 33) return true;
                        return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
                            } else {
                                // 已授权，跳到应用通知设置
                                try {
                                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                                    startActivity(i);
                                } catch (Exception e) {
                                    openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                }
                            }
                        } else {
                            openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        }
                    }
                });

        addPermRow(col, "Shizuku 特权", "让 AI 以系统级权限执行操作（安装/卸载、改系统设置等）。",
                new StatusProvider() {
                    @Override public boolean granted() { return shizukuOk != null && shizukuOk; }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showShizukuDialog();
                    }
                });

        // 开始使用按钮
        Button start = new Button(this);
        start.setText("开始使用");
        start.setTextColor(Color.WHITE);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        start.setBackgroundColor(Color.parseColor("#4d6bfe"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(20);
        start.setLayoutParams(lp);
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("setup_done", true).apply();
                showEngineScreen();
                startEngine();
            }
        });
        col.addView(start);

        TextView skip = new TextView(this);
        skip.setText("部分权限可稍后在系统设置中开启");
        skip.setTextColor(cSub());
        skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(10), 0, 0);
        col.addView(skip);

        setContentView(scroll);
        refreshAllStatuses();
        probeShizuku();
    }

    private void addPermRow(LinearLayout parent, String title, String desc,
                            final StatusProvider provider, final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(cCard());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        left.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(cSub());
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setPadding(0, dp(3), 0, 0);
        left.addView(d);

        TextView status = new TextView(this);
        status.setText("检测中…");
        status.setTextColor(cSub());
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10), 0, dp(4), 0);

        row.addView(left);
        row.addView(status);
        row.setOnClickListener(click);
        parent.addView(row);

        PermRow pr = new PermRow();
        pr.status = status;
        pr.provider = provider;
        permRows.add(pr);
    }

    private void refreshAllStatuses() {
        // 线程安全：Shizuku binder 回调、后台线程都可能调用；setText 必须在 UI 线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override public void run() { refreshAllStatuses(); }
            });
            return;
        }
        for (PermRow pr : permRows) {
            boolean g = false;
            try { g = pr.provider.granted(); } catch (Throwable ignored) {}
            pr.status.setText(g ? "已授权" : "未授权");
            pr.status.setTextColor(g ? cGreen() : cRed());
        }
    }

    private void openSystemSetting(String action) {
        try {
            Intent i = new Intent(action);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(action);
                startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "无法打开设置: " + action, e2);
            }
        }
    }

    private void openShizukuApp() {
        String[] pkgs = {"moe.shizuku.privileged.api", "rikka.shizuku"};
        for (String p : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                try { startActivity(i); return; } catch (Exception ignored) {}
            }
        }
        Log.w(TAG, "未找到 Shizuku 应用，请手动打开并授权");
    }

    private void showShizukuDialog() {
        boolean installed = false;
        for (String p : new String[]{"moe.shizuku.privileged.api", "rikka.shizuku"}) {
            try { getPackageManager().getPackageInfo(p, 0); installed = true; break; } catch (Exception ignored) {}
        }
        boolean binderOk = false;
        try { binderOk = Shizuku.pingBinder(); } catch (Throwable ignored) {}

        if (shizukuOk != null && shizukuOk) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("Shizuku 特权");
            b.setMessage("Shizuku 已授权，本应用可以执行系统级操作。");
            b.setNegativeButton("关闭", null);
            b.show();
        } else if (binderOk) {
            // 服务在运行但未授权 → 直接弹 Shizuku 授权对话框
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } catch (Throwable t) {
                Log.w(TAG, "Shizuku requestPermission failed", t);
                fallbackShizukuDialog(installed);
            }
        } else {
            fallbackShizukuDialog(installed);
        }
    }

    private void fallbackShizukuDialog(boolean installed) {
        String msg;
        if (installed) {
            msg = "Shizuku 服务未运行。\n\n请先打开 Shizuku 应用并启动服务，然后回来点击「重新检测」；服务启动后本应用会自动弹出授权对话框。";
        } else {
            msg = "未检测到 Shizuku 应用。请先安装 Shizuku（官方版），再回来授权。";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Shizuku 特权");
        b.setMessage(msg);
        if (installed) {
            b.setPositiveButton("去启动 Shizuku", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { openShizukuApp(); }
            });
        }
        b.setNeutralButton("重新检测", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { probeShizuku(); }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    // Shizuku 检测（Shizuku API，异步）
    private volatile Boolean shizukuOk = null;

    private void probeShizuku() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = shizukuAvailable();
                shizukuOk = ok;
                ui.post(new Runnable() { @Override public void run() { refreshAllStatuses(); } });
            }
        }, "shizuku-probe").start();
    }

    private boolean shizukuAvailable() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private File extractRishDex() {
        try {
            File dir = new File(getFilesDir(), "rish");
            if (!dir.exists()) dir.mkdirs();
            File dex = new File(dir, "rish_shizuku.dex");
            if (dex.exists() && dex.length() > 0) return dex;
            InputStream in = getAssets().open("rish_shizuku.dex");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract rish dex failed", e);
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshAllStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        refreshAllStatuses();
        // 从 Shizuku/设置页返回时重新检测
        if (permRows != null && !permRows.isEmpty()) probeShizuku();
    }

    // ============ 引擎启动（原逻辑）============
    private void startEngine() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File files = getFilesDir();
                    File payload = new File(files, "payload");
                    File done = new File(payload, ".extracted");

                    // dshroot 放外部公共目录（/sdcard/DeepSeekHarness/dshroot），卸载不丢；
                    // 写失败则回退到内部 files/payload/dshroot（失去持久化但仍可用）。
                    File externalRoot = new File(Environment.getExternalStorageDirectory(), EXT_DSHROOT_ROOT);
                    boolean useExternal = externalDshrootWritable(externalRoot);

                    // 后台清理上次「清空」遗留的 .trash-* 目录（rename 后后台删除未完成），不阻塞启动。
                    if (useExternal) {
                        final File extCleanup = externalRoot;
                        new Thread(new Runnable() {
                            @Override public void run() { cleanupTrashDirs(extCleanup); }
                        }, "trash-cleanup").start();
                    }

                    if (!done.exists()) {
                        // 关键：先解压内部关键运行时（node/.so/dshhome/bin/rish），再解压外部 dshroot。
                        // 外部 dshroot 13000+ 文件经 FUSE 写入慢，若中途被打断，只要内部已就位
                        // 引擎仍能启动；外部缺的文件由下面的 dshrootNeedsSync 幂等补齐。
                        extractPayload(payload, null, "internal");
                        done.createNewFile();
                    }

                    // 补齐外部 dshroot：REVISION 不匹配（重装）或 .complete 缺失（中断）都补。
                    // 补是幂等的（REVISION/白名单覆盖，其余已存在跳过），不重复写已成功解压的文件。
                    if (useExternal && dshrootNeedsSync(externalRoot)) {
                        boolean revisionChanged = dshrootRevisionChanged(externalRoot);
                        extractPayload(payload, externalRoot, "dshroot");
                        writeDshrootComplete(externalRoot);
                        if (revisionChanged) refreshInternalConfig(payload);
                    }

                    dshrootDir = useExternal
                        ? new File(externalRoot, "dshroot")
                        : new File(payload, "dshroot");

                    // 兜底：确保 dshroot 确实就位。例如首次用外部模式解压后，
                    // 用户撤销了「所有文件访问」权限导致本次回退内部，但内部 dshroot 为空。
                    if (!new File(dshrootDir, REL_BINJS).exists()) {
                        Log.w(TAG, "dshroot missing at " + dshrootDir + ", repopulating");
                        extractPayload(payload, useExternal ? externalRoot : null, "dshroot");
                        if (useExternal) writeDshrootComplete(externalRoot);
                    }

                    applyLinks(payload);
                    setExecutables(payload);
                    if (healthOk()) { loadHome(); return; }
                    showIndeterminate("正在启动 DeepSeek Harness…");
                    spawnNode(payload);
                    waitForServer();
                } catch (Throwable t) {
                    Log.e(TAG, "engine error", t);
                    setStatus("引擎启动失败：" + String.valueOf(t.getMessage()));
                }
            }
        }, "engine-boot").start();
    }

    // 探测外部公共目录是否可写（不需要"所有文件访问"时也能降级内部）
    private boolean externalDshrootWritable(File externalRoot) {
        try {
            if (!externalRoot.exists() && !externalRoot.mkdirs()) return false;
            File probe = new File(externalRoot, ".probe");
            if (!probe.createNewFile()) return false;
            probe.delete();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "external dshroot not writable, fallback to internal", t);
            return false;
        }
    }

    private String readAssetText(String asset) throws IOException {
        InputStream in = getAssets().open(asset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String readFileText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String builtinDshrootRevision() {
        try {
            return readAssetText("dshroot_revision.txt").trim();
        } catch (Throwable t) {
            Log.w(TAG, "read dshroot revision failed", t);
            return "";
        }
    }

    private String externalDshrootRevision(File externalRoot) {
        File revFile = new File(externalRoot, "dshroot/REVISION");
        try {
            return revFile.exists() ? readFileText(revFile).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean dshrootRevisionChanged(File externalRoot) {
        String builtin = builtinDshrootRevision();
        String external = externalDshrootRevision(externalRoot);
        return !builtin.isEmpty() && !builtin.equals(external);
    }

    // 外部 dshroot 是否需要补齐：REVISION 不匹配（重装）或缺完成标记（解压被打断）。
    private boolean dshrootNeedsSync(File externalRoot) {
        if (dshrootRevisionChanged(externalRoot)) return true;
        File complete = new File(externalRoot, "dshroot/" + DSHROOT_COMPLETE);
        return !complete.exists();
    }

    private void writeDshrootComplete(File externalRoot) {
        File complete = new File(externalRoot, "dshroot/" + DSHROOT_COMPLETE);
        try {
            FileOutputStream fos = new FileOutputStream(complete);
            fos.write(builtinDshrootRevision().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "write dshroot complete marker failed", t);
        }
    }

    private void extractPayload(File destInternal, File externalRoot, String mode) throws IOException {
        // mode: "internal" = 只解压内部条目（runtime/bin/dshhome/rish，不含 dshroot）；
        //       "dshroot"  = 只解压 dshroot 条目（外部优先，回退内部）。
        final boolean internalOnly = "internal".equals(mode);
        final boolean dshrootOnly = "dshroot".equals(mode);
        if (!destInternal.exists() && !destInternal.mkdirs()) throw new IOException("mkdir failed: " + destInternal);
        final int total = countPayloadEntries(mode);
        if (total > 0) setProgress(0, "首次启动 · 正在解压运行时 0/" + total + " 个文件…");
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int processed = 0;
        int written = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            processed++;

            File target;
            boolean skipIfExists = false;
            if (isDshroot && externalRoot != null) {
                target = new File(externalRoot, name);
                // 外部 dshroot：REVISION 与官方白名单路径总是覆盖；其他已有文件跳过（保留 AI 运行时修改）。
                boolean isRevision = name.equals("dshroot/REVISION");
                boolean forceOverwrite = isForceOverwrite(name);
                skipIfExists = !isRevision && !forceOverwrite && target.exists();
            } else {
                target = new File(destInternal, name);
            }

            if (skipIfExists) {
                zis.closeEntry();
                updateProgress(processed, total, written);
                continue;
            }

            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            FileOutputStream fos = new FileOutputStream(target);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
            written++;
            updateProgress(processed, total, written);
        }
        zis.close();
        Log.i(TAG, "extracted " + written + " entries (external=" + (externalRoot != null) + ", mode=" + mode + ")");
    }

    // 判断某条目是否属于官方强制覆盖白名单（外部 dshroot 也随 APK 更新）。
    private boolean isForceOverwrite(String name) {
        for (String p : FORCE_OVERWRITE_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    // dshhome 里随 APK 更新的官方配置文件（凭证 .credentials.yaml、会话数据 storages/ 等不在内）。
    private static final String[] DSHHOME_CONFIG_PATHS = {
        "dshhome/cordis.patch.yml",
        "dshhome/settings.yaml",
        "dshhome/profiles/web/cordis.patch.yml",
        "dshhome/profiles/web/cordis.yml",
        "dshhome/profiles/web/package.json",
        "dshhome/profiles/web/pnpm-workspace.yaml"
    };

    // 重装后把 dshhome 的官方配置文件从 payload.zip 覆盖到内部（凭证/会话保留）。
    private void refreshInternalConfig(File payload) throws IOException {
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int updated = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            boolean isConfig = false;
            for (String p : DSHHOME_CONFIG_PATHS) {
                if (name.equals(p)) { isConfig = true; break; }
            }
            if (!isConfig) { zis.closeEntry(); continue; }
            File target = new File(payload, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            FileOutputStream fos = new FileOutputStream(target);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
            updated++;
        }
        zis.close();
        Log.i(TAG, "refreshed " + updated + " dshhome config files");
    }

    // 预扫 payload.zip 统计要处理的条目数（只读 entry 头，不写盘），供进度条使用。
    private int countPayloadEntries(String mode) throws IOException {
        final boolean internalOnly = "internal".equals(mode);
        final boolean dshrootOnly = "dshroot".equals(mode);
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int n = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            n++;
            zis.closeEntry();
        }
        zis.close();
        return n;
    }

    private void updateProgress(int processed, int total, int written) {
        if (total <= 0) return;
        if (processed != total && processed % 200 != 0) return;
        int pct = (int)(processed * 100L / total);
        setProgress(pct, "首次启动 · 正在解压运行时 " + processed + "/" + total + " 个文件…");
    }

    private void applyLinks(File payload) throws IOException {
        File lib = new File(payload, "runtime/lib");
        File linksFile = new File(lib, "LINKS.txt");
        if (!linksFile.exists()) return;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(linksFile), "UTF-8"));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\t+");
            if (parts.length < 2) continue;
            String linkName = parts[0].trim();
            String target = parts[1].trim();
            File link = new File(lib, linkName);
            File src = new File(lib, target);
            if (!link.exists() && src.exists()) {
                try {
                    Os.link(src.getAbsolutePath(), link.getAbsolutePath());
                    n++;
                } catch (ErrnoException e1) {
                    try {
                        Os.symlink(target, link.getAbsolutePath());
                        n++;
                    } catch (ErrnoException e2) {
                        try { copyFile(src, link); n++; } catch (IOException e3) {
                            Log.w(TAG, "link failed " + linkName, e3);
                        }
                    }
                }
            }
        }
        r.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[128 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
    }

    private void setExecutables(File payload) {
        String[] execs = {"runtime/bin/node", "bin/bash"};
        for (String p : execs) {
            File f = new File(payload, p);
            if (f.exists()) f.setExecutable(true, false);
        }
    }

    private void spawnNode(File payload) throws IOException {
        File node = new File(payload, "runtime/bin/node");
        File binjs = new File(dshrootDir, REL_BINJS);
        File lib = new File(payload, "runtime/lib");
        File home = new File(payload, "dshhome");
        File bin = new File(payload, "bin");
        File tmp = new File(getCacheDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();

        if (!node.exists()) throw new IOException("node binary missing");
        if (!binjs.exists()) throw new IOException("dsh bin.js missing");
        if (!node.canExecute()) node.setExecutable(true, false);

        ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                "web", "--host", "127.0.0.1", "--port", "3080");
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("PATH", bin.getAbsolutePath() + ":" +
                new File(payload, "runtime/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "xterm");
        env.put("SHIZUKU_DEX", rishDex != null ? rishDex.getAbsolutePath() : "");
        env.put("SHIZUKU_APP_ID", "com.deepseek.harness");
        pb.redirectErrorStream(true);

        final Process proc = pb.start();
        nodeProcess = proc;
        final File logFile = new File(getFilesDir(), "dsh-web.log");
        new Thread(new Runnable() {
            @Override public void run() {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(logFile, true);
                    InputStream is = proc.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) {
                        fos.write(b, 0, n);
                        fos.flush();
                        String s = new String(b, 0, n, "UTF-8");
                        for (String line : s.split("\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) Log.i(TAG, "node: " + t);
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "log reader error", e);
                } finally {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                }
            }
        }, "node-log").start();
    }

    private boolean healthOk() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_HOME + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void waitForServer() {
        long start = System.currentTimeMillis();
        long deadline = start + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (healthOk()) { loadHome(); return; }
            long waited = (System.currentTimeMillis() - start) / 1000;
            setStatus("正在启动 DeepSeek Harness…（已等待 " + waited + " 秒）");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        setStatus("引擎启动超时，请重启应用");
        loadHome();
    }

    /** node 看门狗：node 进程死亡且服务不可用时自动重启引擎并刷新页面 */
    private void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    try {
                        if (nodeProcess == null) continue;
                        boolean serverUp = healthOk();
                        boolean nodeAlive = nodeProcess.isAlive();
                        if (!serverUp && !nodeAlive) {
                            long now = System.currentTimeMillis();
                            if (now - lastRespawnAt < 20000) continue; // 避免风车重启
                            lastRespawnAt = now;
                            Log.w(TAG, "node died, respawning engine");
                            spawnNode(new File(getFilesDir(), "payload"));
                            final WebView wv = webView;
                            ui.post(new Runnable() {
                                @Override public void run() { wv.loadUrl(URL_HOME); }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "watchdog error", t);
                    }
                }
            }
        }, "node-watchdog").start();
    }

    /** 读取 APK 资产下的移动端 CSS/JS 并强制注入 WebView（不依赖服务器 dist，确保生效） */
    private void injectMobileAssets(WebView view) {
        try {
            byte[] css = readAssetBytes("mobile.css");
            byte[] js = readAssetBytes("mobile.js");
            String cssB64 = android.util.Base64.encodeToString(css, android.util.Base64.NO_WRAP);
            String jsB64 = android.util.Base64.encodeToString(js, android.util.Base64.NO_WRAP);
            final String script = "(function(){try{if(!document.getElementById('dd-mobile-css')){var s=document.createElement('style');s.id='dd-mobile-css';s.textContent=atob('"
                    + cssB64 + "');document.head.appendChild(s);}}catch(e){}try{if(!document.getElementById('dd-mobile-js')){var j=document.createElement('script');j.id='dd-mobile-js';j.textContent=atob('"
                    + jsB64 + "');document.body.appendChild(j);}catch(e){}})();";
            view.evaluateJavascript(script, null);
        } catch (Throwable t) {
            Log.w(TAG, "inject mobile assets failed", t);
        }
    }

    private byte[] readAssetBytes(String name) throws IOException {
        java.io.InputStream in = getAssets().open(name);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private void loadHome() {
        startWatchdog();
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setVisibility(View.GONE);
                if (splashLogo != null) splashLogo.setVisibility(View.GONE);
                if (splashBrand != null) splashBrand.setVisibility(View.GONE);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
                webView.loadUrl(URL_HOME);
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { statusView.setText(s); }
        });
    }

    private void setProgress(final int percent, final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(percent);
                }
                if (s != null) statusView.setText(s);
            }
        });
    }

    private void showIndeterminate(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(true);
                    progressBar.setVisibility(View.VISIBLE);
                }
                if (s != null) statusView.setText(s);
            }
        });
    }

    private void hideProgress() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 有历史先回退（可关掉侧边栏/返回上一页）；没有历史则询问是否退出
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        confirmExit();
    }
}
