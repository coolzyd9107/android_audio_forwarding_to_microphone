package com.coolzyd.audioforwarder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.content.pm.ResolveInfo;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_AUDIO = 10;
    private static final int REQUEST_MEDIA_PROJECTION = 12;
    private static final int REQUEST_RECORD_AUDIO = 13;
    private static final String PREFS = "audio_forwarder";
    private static final String PREF_URI = "selected_uri";
    private static final String PREF_NAME = "selected_name";

    private Uri selectedUri;
    private String selectedName;
    private TextView fileLabel;
    private CheckBox audibleOutput;
    private TextView gainLabel;
    private Spinner sourceAppSpinner;
    private Spinner targetAppSpinner;
    private LinearLayout fileSourcePanel;
    private LinearLayout appSourcePanel;
    private boolean captureApplicationAudio;
    private int forwardingGain = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUri = preferences.getString(PREF_URI, null);
        selectedName = preferences.getString(PREF_NAME, null);
        forwardingGain = preferences.getInt("forwarding_gain", 100);
        captureApplicationAudio = preferences.getBoolean("capture_app_audio", false);
        if (savedUri != null) {
            selectedUri = Uri.parse(savedUri);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Audio Forwarder");
        title.setTextSize(24);
        root.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("选择本地文件，或捕获音乐应用正在播放的音频，并转发到指定应用的麦克风采集流。");
        description.setTextSize(15);
        description.setPadding(0, dp(8), 0, dp(12));
        root.addView(description, matchWrap());

        RadioGroup sourceMode = new RadioGroup(this);
        sourceMode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton fileMode = new RadioButton(this);
        fileMode.setId(1);
        fileMode.setText("本地文件");
        sourceMode.addView(fileMode, new RadioGroup.LayoutParams(0,
                RadioGroup.LayoutParams.WRAP_CONTENT, 1));
        RadioButton appMode = new RadioButton(this);
        appMode.setId(2);
        appMode.setText("应用音频");
        sourceMode.addView(appMode, new RadioGroup.LayoutParams(0,
                RadioGroup.LayoutParams.WRAP_CONTENT, 1));
        sourceMode.check(captureApplicationAudio ? 2 : 1);
        root.addView(sourceMode, matchWrap());

        fileSourcePanel = new LinearLayout(this);
        fileSourcePanel.setOrientation(LinearLayout.VERTICAL);
        Button choose = new Button(this);
        choose.setText("选择音频文件");
        choose.setOnClickListener(view -> chooseAudio());
        fileSourcePanel.addView(choose, matchWrap());

        fileLabel = new TextView(this);
        fileLabel.setTextSize(14);
        fileLabel.setPadding(0, dp(12), 0, dp(12));
        fileSourcePanel.addView(fileLabel, matchWrap());
        updateFileLabel();

        audibleOutput = new CheckBox(this);
        audibleOutput.setText("同时在本机播放所选文件");
        fileSourcePanel.addView(audibleOutput, matchWrap());
        root.addView(fileSourcePanel, matchWrap());

        appSourcePanel = new LinearLayout(this);
        appSourcePanel.setOrientation(LinearLayout.VERTICAL);
        TextView sourceLabel = new TextView(this);
        sourceLabel.setText("音频来源应用");
        appSourcePanel.addView(sourceLabel, matchWrap());
        sourceAppSpinner = new Spinner(this);
        appSourcePanel.addView(sourceAppSpinner, matchWrap());
        TextView captureNote = new TextView(this);
        captureNote.setText("Android 10+ 会请求系统捕获授权。来源应用须允许音频捕获；系统授权期间的音乐仍按来源应用自身设置播放。");
        captureNote.setTextSize(13);
        captureNote.setPadding(0, dp(8), 0, dp(8));
        appSourcePanel.addView(captureNote, matchWrap());
        root.addView(appSourcePanel, matchWrap());
        populateApplicationLists(preferences);
        sourceMode.setOnCheckedChangeListener((group, checkedId) -> {
            captureApplicationAudio = checkedId == 2;
            fileSourcePanel.setVisibility(captureApplicationAudio ? View.GONE : View.VISIBLE);
            appSourcePanel.setVisibility(captureApplicationAudio ? View.VISIBLE : View.GONE);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("capture_app_audio", captureApplicationAudio).apply();
        });
        fileSourcePanel.setVisibility(captureApplicationAudio ? View.GONE : View.VISIBLE);
        appSourcePanel.setVisibility(captureApplicationAudio ? View.VISIBLE : View.GONE);

        TextView targetLabel = new TextView(this);
        targetLabel.setText("麦克风目标应用");
        targetLabel.setPadding(0, dp(12), 0, 0);
        root.addView(targetLabel, matchWrap());
        targetAppSpinner = new Spinner(this);
        root.addView(targetAppSpinner, matchWrap());
        populateTargetApplications(preferences);

        gainLabel = new TextView(this);
        gainLabel.setTextSize(14);
        gainLabel.setPadding(0, dp(10), 0, dp(2));
        root.addView(gainLabel, matchWrap());
        SeekBar gainControl = new SeekBar(this);
        gainControl.setMax(200);
        gainControl.setProgress(forwardingGain);
        root.addView(gainControl, matchWrap());
        updateGainLabel();
        gainControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                forwardingGain = progress;
                updateGainLabel();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("forwarding_gain", forwardingGain).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Intent intent = new Intent(MainActivity.this, ForwardingService.class)
                        .setAction(ForwardingService.ACTION_SET_GAIN)
                        .putExtra(ForwardingService.EXTRA_GAIN, forwardingGain / 100.0f);
                startService(intent);
            }
        });

        Button start = new Button(this);
        start.setText("开始转发");
        start.setOnClickListener(view -> startForwarding());
        root.addView(start, matchWrap());

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(view -> stopForwarding());
        root.addView(stop, matchWrap());

        TextView moduleNote = new TextView(this);
        moduleNote.setText("目标应用须加入 libxposed 模块作用域；仅支持 Java AudioRecord 采集。Android 应用音频捕获还取决于来源应用的捕获策略。");
        moduleNote.setTextSize(13);
        moduleNote.setPadding(0, dp(20), 0, 0);
        root.addView(moduleNote, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void populateApplicationLists(SharedPreferences preferences) {
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, getLaunchableApplications());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceAppSpinner.setAdapter(adapter);
        String savedSource = preferences.getString("source_package", null);
        selectPackage(sourceAppSpinner, savedSource);
        sourceAppSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppChoice choice = (AppChoice) parent.getItemAtPosition(position);
                preferences.edit().putString("source_package", choice.packageName).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void populateTargetApplications(SharedPreferences preferences) {
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, getLaunchableApplications());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetAppSpinner.setAdapter(adapter);
        String savedTarget = preferences.getString("target_package", null);
        selectPackage(targetAppSpinner, savedTarget);
        targetAppSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppChoice choice = (AppChoice) parent.getItemAtPosition(position);
                preferences.edit().putString("target_package", choice.packageName).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private java.util.ArrayList<AppChoice> getLaunchableApplications() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.Map<String, AppChoice> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(getPackageManager());
            apps.put(packageName, new AppChoice(label == null ? packageName : label.toString(), packageName));
        }
        return new java.util.ArrayList<>(apps.values());
    }

    private void selectPackage(Spinner spinner, String packageName) {
        if (packageName == null) {
            return;
        }
        for (int i = 0; i < spinner.getCount(); i++) {
            if (((AppChoice) spinner.getItemAtPosition(i)).packageName.equals(packageName)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static final class AppChoice {
        final String label;
        final String packageName;

        AppChoice(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startCaptureService(resultCode, data);
            } else {
                Toast.makeText(this, "未授予应用音频捕获权限", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_PICK_AUDIO || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        selectedUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers do not offer persistable grants.
        }
        selectedName = queryDisplayName(selectedUri);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_URI, selectedUri.toString())
                .putString(PREF_NAME, selectedName)
                .apply();
        updateFileLabel();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (Exception ignored) {
            // Fall back to the URI when the provider has no display name.
        }
        return uri.getLastPathSegment() == null ? "已选择音频文件" : uri.getLastPathSegment();
    }

    private void updateFileLabel() {
        if (fileLabel == null) {
            return;
        }
        fileLabel.setText(selectedName == null ? "尚未选择文件" : selectedName);
    }

    private void updateGainLabel() {
        if (gainLabel != null) {
            gainLabel.setText("麦克风转发增益：" + forwardingGain + "%（0%静音；本机播放音量由系统控制）");
        }
    }

    private void startForwarding() {
        AppChoice target = (AppChoice) targetAppSpinner.getSelectedItem();
        if (target == null) {
            Toast.makeText(this, "没有可选的目标应用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captureApplicationAudio) {
            AppChoice source = (AppChoice) sourceAppSpinner.getSelectedItem();
            if (source == null) {
                Toast.makeText(this, "没有可选的音频来源应用", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "应用音频捕获需要 Android 10 或更高版本", Toast.LENGTH_LONG).show();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                return;
            }
            requestMediaProjection();
            return;
        }
        if (selectedUri == null) {
            Toast.makeText(this, "请先选择音频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ForwardingService.class)
                .setAction(ForwardingService.ACTION_START)
                .setData(selectedUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(ForwardingService.EXTRA_TARGET_PACKAGE, target.packageName)
                .putExtra(ForwardingService.EXTRA_AUDIBLE, audibleOutput.isChecked())
                .putExtra(ForwardingService.EXTRA_GAIN, forwardingGain / 100.0f);
        startForwardingService(intent);
        Toast.makeText(this, "正在启动音频转发", Toast.LENGTH_SHORT).show();
    }

    private void requestMediaProjection() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestMediaProjection();
        }
    }

    private void startCaptureService(int resultCode, Intent projectionData) {
        AppChoice source = (AppChoice) sourceAppSpinner.getSelectedItem();
        AppChoice target = (AppChoice) targetAppSpinner.getSelectedItem();
        if (source == null || target == null) {
            return;
        }
        Intent intent = new Intent(this, ForwardingService.class)
                .setAction(ForwardingService.ACTION_START_CAPTURE)
                .putExtra(ForwardingService.EXTRA_SOURCE_PACKAGE, source.packageName)
                .putExtra(ForwardingService.EXTRA_TARGET_PACKAGE, target.packageName)
                .putExtra(ForwardingService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(ForwardingService.EXTRA_PROJECTION_DATA, projectionData)
                .putExtra(ForwardingService.EXTRA_GAIN, forwardingGain / 100.0f);
        startForwardingService(intent);
        Toast.makeText(this, "正在捕获所选应用的媒体音频", Toast.LENGTH_SHORT).show();
    }

    private void startForwardingService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopForwarding() {
        startService(new Intent(this, ForwardingService.class).setAction(ForwardingService.ACTION_STOP));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
