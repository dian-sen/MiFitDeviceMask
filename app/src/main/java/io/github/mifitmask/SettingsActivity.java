package io.github.mifitmask;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.libxposed.service.XposedService;

/**
 * 模块设置页（原生控件，程序化布局，无资源依赖）。
 * 经 XposedService 写 RemotePreferences，框架自动同步到目标进程；
 * hook 侧动态读取，机型改名即时生效，层开关与探测日志需重启目标进程。
 */
public class SettingsActivity extends Activity implements MaskApp.Listener {

    private static final String TAG_CUSTOM = "custom";
    private static final int REQ_PICK_FACE = 4711;

    private SharedPreferences mPrefs;

    private Switch mSwAll;
    private Switch mSwNet;
    private Switch mSwFaceReq;
    private Switch mSwDebug;
    private Switch mSwBtName;
    private Switch mSwConnected;
    private RadioGroup mRgTarget;
    private EditText mEtModel;
    private EditText mEtProductId;
    private EditText mEtDeviceSource;
    private EditText mEtOrigModel;
    private EditText mEtOrigProductId;
    private EditText mEtFaceId;
    private Button mBtnPick;
    private TextView mTvInstall;
    private EditText mEtStoreFaceId;
    private Button mBtnStoreFetch;
    private Button mBtnStoreDl;
    private TextView mTvStore;
    private TextView mTvStatus;
    private Button mBtnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((MaskApp) getApplication()).addListener(this, true);
        setContentView(buildUi());
        loadToUi();
    }

    @Override
    protected void onDestroy() {
        ((MaskApp) getApplication()).removeListener(this);
        super.onDestroy();
    }

    // ---------- 服务状态 ----------

    @Override
    public void onServiceReady(XposedService service) {
        SharedPreferences prefs = null;
        try {
            prefs = service.getRemotePreferences(Prefs.GROUP);
        } catch (Throwable t) {
            // 保持 null，走未连接分支
        }
        mPrefs = prefs;
        runOnUiThread(this::loadToUi);
    }

    @Override
    public void onServiceLost() {
        mPrefs = null;
        runOnUiThread(this::loadToUi);
    }

    // ---------- UI 构建 ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 245, 248));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("MiFit 机型伪装");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(20, 20, 24));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("v" + versionName() + " · libxposed API 102 · 目标：小米运动健康"
                + "（com.mi.health / com.xiaomi.wearable）· 仅供测试");
        subtitle.setTextSize(12);
        subtitle.setTextColor(Color.rgb(120, 120, 128));
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);

        mTvStatus = new TextView(this);
        mTvStatus.setTextSize(13);
        mTvStatus.setPadding(0, 0, 0, dp(12));
        root.addView(mTvStatus);

        mSwAll = addSwitch(root, "启用机型伪装", "总开关：关闭后所有 hook 放行原逻辑");
        root.addView(sectionLabel("目标机型（表盘商店按此机型返回市场）"));
        mRgTarget = new RadioGroup(this);
        mRgTarget.setOrientation(RadioGroup.VERTICAL);
        for (DeviceProfiles.Profile p : DeviceProfiles.PRESETS) {
            mRgTarget.addView(makeRadio(p.id, p.displayName + "（" + p.model + "）"));
        }
        mRgTarget.addView(makeRadio(TAG_CUSTOM, "自定义机型"));
        root.addView(mRgTarget);

        root.addView(sectionLabel("自定义参数（选择「自定义机型」时生效；model 必填，填 App 内部代号）"));
        mEtModel = addLabeledInput(root, "内部代号，如 miwear.watch.q69cn（手环 10）",
                "目标机型 · model");
        mEtProductId = addLabeledInput(root, "数值（如 34835）；可留空", "目标机型 · productId");
        mEtDeviceSource = addLabeledInput(root, "数值；可留空", "目标机型 · deviceSource");

        root.addView(sectionLabel("当前机型标识（值匹配用；留空 = 观察模式，只记录不改写）"));
        mEtOrigModel = addLabeledInput(root, "你手环的代号：n67cn（小米手环 9 Pro，日志实证）",
                "当前机型 · model（已连接设备 Device.getModel）");
        mEtOrigProductId = addLabeledInput(root, "你手环的 productId 数值（日志 OBSERVE Device.getProductId = ? 待确认）",
                "当前机型 · productId（已连接设备）");

        mSwBtName = addSwitch(root, "改写蓝牙设备名（危险，默认关）",
                "把形似穿戴设备的蓝牙名改成目标机型；曾导致添加/连接失败", Color.rgb(170, 50, 20));
        mSwConnected = addSwitch(root, "本地机型改写（危险，默认关）",
                "改写 Product 目录与 Device 标识；改写值会被 App 写入本地库造成持久污染（关闭模块也不恢复，需清除数据），默认只观察不改写", Color.rgb(170, 50, 20));

        mSwNet = addSwitch(root, "重写表盘商店请求参数（网络层·已废弃）",
                "小米运动健康业务请求全加密+签名，网络层改写不可行；保留开关仅兼容，请勿开启");
        mSwFaceReq = addSwitch(root, "改写表盘请求参数（推荐开启）",
                "在请求组装的明文参数处做值匹配替换（当前机型代号/productId → 目标机型），不影响连接与本地数据");
        mSwDebug = addSwitch(root, "调试日志",
                "在 Xposed 日志输出探测信息（候选类/方法/取值），供迭代固化 hook 点");

        root.addView(sectionLabel("本地表盘安装（绕过商店市场，装任意来源的表盘文件）"));
        mEtFaceId = addLabeledInput(root, "faceId（可留空，默认按文件大小生成）",
                "本地表盘 · faceId");
        mBtnPick = new Button(this);
        mBtnPick.setText("选择表盘文件（.bin）并请求安装");
        mBtnPick.setOnClickListener(v -> pickFaceFile());
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = dp(4);
        root.addView(mBtnPick, pickLp);
        mTvInstall = new TextView(this);
        mTvInstall.setTextSize(12);
        mTvInstall.setTextColor(Color.rgb(60, 60, 96));
        mTvInstall.setPadding(dp(4), dp(6), 0, 0);
        root.addView(mTvInstall);

        root.addView(sectionLabel("表盘商店直取（官方公开接口，免登录；任意机型+faceId → 下载并直装）"));
        mEtStoreFaceId = addLabeledInput(root, "表盘数字 faceId（如 120917300001；来源见 README）",
                "商店直取 · 表盘 faceId");
        mBtnStoreFetch = new Button(this);
        mBtnStoreFetch.setText("从官方商店获取目标机型的该表盘并安装");
        mBtnStoreFetch.setOnClickListener(v -> storeFetchInstall(false));
        root.addView(mBtnStoreFetch, pickLp);
        mBtnStoreDl = new Button(this);
        mBtnStoreDl.setText("仅下载表盘文件到手机（不安装）");
        mBtnStoreDl.setOnClickListener(v -> storeFetchInstall(true));
        root.addView(mBtnStoreDl, pickLp);
        mTvStore = new TextView(this);
        mTvStore.setTextSize(12);
        mTvStore.setTextColor(Color.rgb(60, 60, 96));
        mTvStore.setPadding(dp(4), dp(6), 0, 0);
        root.addView(mTvStore);
        TextView codeRule = new TextView(this);
        codeRule.setTextSize(11);
        codeRule.setTextColor(Color.rgb(110, 110, 120));
        codeRule.setPadding(dp(4), dp(8), 0, 0);
        codeRule.setText("机型代号规则（未列明机型可自行推导，选「自定义机型」填写）："
                + "年份字母（M=2023 N=2024 O=2025 P=2026…）+ 两位数字（第二位：6=手环标准/NFC、"
                + "7=手环 Pro、2=Watch S、5=Redmi Watch）+ 地区后缀（cn=国行、gl=全球等）。"
                + "例：小米手环 10 Pro ≈ miwear.watch.o67cn；代号是否有效可用上方「商店直取」验证"
                + "（能查到表盘即有效）。");
        root.addView(codeRule);

        mBtnSave = new Button(this);
        mBtnSave.setText("保存设置");
        mBtnSave.setOnClickListener(v -> save());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(16);
        root.addView(mBtnSave, btnLp);

        TextView tips = new TextView(this);
        tips.setText("使用说明：\n"
                + "1. 在 LSPosed 中启用本模块，作用域勾选「小米运动健康」，重启手机或重启目标应用进程；\n"
                + "2. 修改机型后：强停「小米运动健康」→ 重新打开 → 设备页断开并重连手环，再进入表盘商店；\n"
                + "3. 若商店未变化，开启调试日志后用 adb logcat 抓取 LSPosed 日志反馈探测信息；\n"
                + "4. 回滚：关闭总开关（或停用模块）→ 强停目标应用 → 必要时清除其数据后重新配对。");
        tips.setTextSize(12);
        tips.setTextColor(Color.rgb(90, 90, 96));
        tips.setPadding(0, dp(16), 0, 0);
        root.addView(tips);

        return scroll;
    }

    private Switch addSwitch(LinearLayout root, String title, String desc) {
        return addSwitch(root, title, desc, Color.rgb(20, 20, 24));
    }

    private Switch addSwitch(LinearLayout root, String title, String desc, int titleColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout card = boxed(box);

        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(15);
        sw.setTextColor(titleColor);
        card.addView(sw);

        TextView tv = new TextView(this);
        tv.setText(desc);
        tv.setTextSize(11);
        tv.setTextColor(Color.rgb(120, 120, 128));
        tv.setPadding(dp(4), dp(2), 0, 0);
        card.addView(tv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(card, lp);
        return sw;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.rgb(60, 60, 66));
        tv.setPadding(dp(4), dp(14), 0, dp(6));
        return tv;
    }

    private RadioButton makeRadio(String tag, String label) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextSize(14);
        rb.setTextColor(Color.rgb(20, 20, 24));
        rb.setTag(tag);
        rb.setPadding(dp(8), dp(6), dp(8), dp(6));
        return rb;
    }

    private EditText addInput(LinearLayout root, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13);
        et.setSingleLine(true);
        LinearLayout card = boxed(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        root.addView(card, lp);
        return et;
    }

    /** 白底圆角卡片容器（圆角用背景色近似，避免引入 drawable 资源）。 */
    private LinearLayout boxed(View child) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(Color.WHITE);
        if (child.getParent() != null) {
            ((android.view.ViewGroup) child.getParent()).removeView(child);
        }
        card.addView(child);
        return card;
    }

    /** 带标签的输入行：标签在上（明确参数名），输入框在下。 */
    private EditText addLabeledInput(LinearLayout root, String hint, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(Color.rgb(60, 60, 90));
        tv.setPadding(dp(2), dp(2), 0, dp(2));
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13);
        et.setSingleLine(true);
        LinearLayout card = boxed(tv);
        card.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        root.addView(card, lp);
        return et;
    }

    // ---------- 装载 / 保存 ----------

    private void loadToUi() {
        // 服务绑定回调可能早于 UI 构建/晚于销毁到达（v1.4.1 修复 NPE 闪退）
        if (mTvStatus == null || mBtnSave == null) {
            return;
        }
        boolean connected = mPrefs != null;
        if (connected) {
            mTvStatus.setText("● 已连接 LSPosed 服务，设置写入后自动同步到目标进程");
            mTvStatus.setTextColor(Color.rgb(24, 140, 80));
            mSwAll.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_ENABLE_ALL));
            mSwNet.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_ENABLE_NET_REWRITE, false));
            mSwFaceReq.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_FACE_REQUEST, true));
            mSwDebug.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_DEBUG_LOG, true));
            mSwBtName.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_BT_NAME, false));
            mSwConnected.setChecked(Prefs.isEnabled(mPrefs, Prefs.KEY_REWRITE_CONNECTED, false));            String target = Prefs.getString(mPrefs, Prefs.KEY_MASK_TARGET).trim();
            if (target.isEmpty()) {
                target = Prefs.DEFAULT_TARGET;
            }
            selectRadio(target);
            mEtModel.setText(Prefs.getString(mPrefs, Prefs.KEY_CUSTOM_MODEL));
            mEtProductId.setText(Prefs.getString(mPrefs, Prefs.KEY_CUSTOM_PRODUCT_ID));
            mEtDeviceSource.setText(Prefs.getString(mPrefs, Prefs.KEY_CUSTOM_DEVICE_SOURCE));
            mEtOrigModel.setText(Prefs.getString(mPrefs, Prefs.KEY_ORIG_MODEL));
            mEtOrigProductId.setText(Prefs.getString(mPrefs, Prefs.KEY_ORIG_PRODUCT_ID));
        } else {
            mTvStatus.setText("○ 未连接 LSPosed 服务：请先在 LSPosed 启用本模块并重启手机，再打开本页保存设置");
            mTvStatus.setTextColor(Color.rgb(200, 90, 40));
        }
        mBtnSave.setEnabled(connected);
    }

    private void selectRadio(String tag) {
        for (int i = 0; i < mRgTarget.getChildCount(); i++) {
            RadioButton rb = (RadioButton) mRgTarget.getChildAt(i);
            if (tag.equals(rb.getTag())) {
                mRgTarget.check(rb.getId());
                return;
            }
        }
    }

    private String selectedTag() {
        RadioButton checked = findViewById(mRgTarget.getCheckedRadioButtonId());
        return checked == null ? null : (String) checked.getTag();
    }

    private void save() {
        if (mPrefs == null) {
            Toast.makeText(this, "未连接 LSPosed 服务，无法保存", Toast.LENGTH_LONG).show();
            return;
        }
        String tag = selectedTag();
        if (tag == null) {
            tag = Prefs.DEFAULT_TARGET;
        }
        String customModel = mEtModel.getText().toString().trim();
        if (TAG_CUSTOM.equals(tag) && customModel.isEmpty()) {
            Toast.makeText(this, "自定义机型必须填写 model", Toast.LENGTH_LONG).show();
            return;
        }
        mPrefs.edit()
                .putBoolean(Prefs.KEY_ENABLE_ALL, mSwAll.isChecked())
                .putBoolean(Prefs.KEY_ENABLE_NET_REWRITE, mSwNet.isChecked())
                .putBoolean(Prefs.KEY_REWRITE_FACE_REQUEST, mSwFaceReq.isChecked())
                .putBoolean(Prefs.KEY_DEBUG_LOG, mSwDebug.isChecked())
                .putBoolean(Prefs.KEY_REWRITE_BT_NAME, mSwBtName.isChecked())
                .putBoolean(Prefs.KEY_REWRITE_CONNECTED, mSwConnected.isChecked())
                .putString(Prefs.KEY_MASK_TARGET, tag)
                .putString(Prefs.KEY_CUSTOM_MODEL, customModel)
                .putString(Prefs.KEY_CUSTOM_PRODUCT_ID,
                        mEtProductId.getText().toString().trim())
                .putString(Prefs.KEY_CUSTOM_DEVICE_SOURCE,
                        mEtDeviceSource.getText().toString().trim())
                .putString(Prefs.KEY_ORIG_MODEL,
                        mEtOrigModel.getText().toString().trim())
                .putString(Prefs.KEY_ORIG_PRODUCT_ID,
                        mEtOrigProductId.getText().toString().trim())
                .apply();
        Toast.makeText(this,
                "已保存。请强停「小米运动健康」后重开（必要时断开重连手环）再查看表盘商店",
                Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 表盘商店直取：按当前选中机型 + faceId 从官方公开接口获取；downloadOnly=true 时仅下载。 */
    private void storeFetchInstall(boolean downloadOnly) {
        if (mPrefs == null) {
            Toast.makeText(this, "未连接 LSPosed 服务，无法入队", Toast.LENGTH_LONG).show();
            return;
        }
        String faceId = mEtStoreFaceId.getText().toString().trim();
        if (faceId.isEmpty() || !faceId.matches("\\d+")) {
            Toast.makeText(this, "请填写数字 faceId（表盘 ID）", Toast.LENGTH_LONG).show();
            return;
        }
        String tag = selectedTag();
        String model;
        if (TAG_CUSTOM.equals(tag)) {
            model = mEtModel.getText().toString().trim();
            if (model.isEmpty()) {
                Toast.makeText(this, "自定义机型需先填写 model", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            DeviceProfiles.Profile p = DeviceProfiles.byId(tag);
            model = p == null ? "" : p.model;
        }
        if (model.isEmpty()) {
            Toast.makeText(this, "无法确定目标机型代号", Toast.LENGTH_LONG).show();
            return;
        }
        FaceStoreFetch.PrefsAccess pa = new FaceStoreFetch.PrefsAccess() {
            @Override
            public int getInt(String key, int def) {
                return Prefs.getInt(mPrefs, key, def);
            }

            @Override
            public void putInt(String key, int value) {
                mPrefs.edit().putInt(key, value).apply();
            }
        };
        mTvStore.setText("发起中…");
        FaceStoreFetch.fetchAndEnqueue(getApplicationContext(), pa, model, faceId,
                s -> runOnUiThread(() -> mTvStore.setText(s)), downloadOnly);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadToUi();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 选择表盘文件 → 复制到中继目录 → 写 prefs 指令（目标进程轮询执行）。 */
    private void pickFaceFile() {
        if (mPrefs == null) {
            Toast.makeText(this, "未连接 LSPosed 服务，无法发起安装", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_FACE);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FACE || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        try {
            String name = "face_" + System.currentTimeMillis() + ".bin";
            File relay = new File(getCacheDir(), "relay");
            if (!relay.exists()) {
                relay.mkdirs();
            }
            File out = new File(relay, name);
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            String faceId = mEtFaceId.getText().toString().trim().replace("|", "").replace("\"", "");
            int token = Prefs.getInt(mPrefs, Prefs.KEY_INSTALL_TOKEN, 0) + 1;
            // 安装指令（经 Provider 查询同步到目标进程）：token|faceId|fileName
            String cmd = token + "|" + faceId + "|" + name;
            java.nio.file.Files.write(new File(relay, "command.json").toPath(),
                    cmd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mTvInstall.setText("已发起安装请求（token=" + token + "），等待小米运动健康执行…");
            startResultPolling(token);
        } catch (Throwable t) {
            Toast.makeText(this, "文件处理失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 轮询 Provider 结果（目标进程经 insert 写回）。 */
    private void startResultPolling(int token) {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final Runnable[] r = new Runnable[1];
        r[0] = () -> {
            try {
                android.database.Cursor c = getContentResolver().query(
                        android.net.Uri.parse("content://io.github.mifitmask.files/result"),
                        null, null, null, null);
                String res = "";
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            res = c.getString(0);
                        }
                    } finally {
                        c.close();
                    }
                }
                if (!res.isEmpty()) {
                    mTvInstall.setText(res);
                }
                if (res.startsWith("进行中") || res.isEmpty()) {
                    h.postDelayed(r[0], 2000);
                }
            } catch (Throwable t) {
                h.postDelayed(r[0], 2000);
            }
        };
        h.postDelayed(r[0], 2000);
    }
}
