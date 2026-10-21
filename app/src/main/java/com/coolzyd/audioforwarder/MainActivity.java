package com.coolzyd.audioforwarder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_AUDIO = 10;
    private static final int REQUEST_NOTIFICATIONS = 11;
    private static final String PREFS = "audio_forwarder";
    private static final String PREF_URI = "selected_uri";
    private static final String PREF_NAME = "selected_name";

    private Uri selectedUri;
    private String selectedName;
    private TextView fileLabel;
    private CheckBox audibleOutput;
    private TextView gainLabel;
    private int forwardingGain = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUri = preferences.getString(PREF_URI, null);
        selectedName = preferences.getString(PREF_NAME, null);
        forwardingGain = preferences.getInt("forwarding_gain", 100);
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
        description.setText("将音频文件送入使用 Android AudioRecord 的麦克风采集流。");
        description.setTextSize(15);
        description.setPadding(0, dp(8), 0, dp(20));
        root.addView(description, matchWrap());

        Button choose = new Button(this);
        choose.setText("选择音频文件");
        choose.setOnClickListener(view -> chooseAudio());
        root.addView(choose, matchWrap());

        fileLabel = new TextView(this);
        fileLabel.setTextSize(14);
        fileLabel.setPadding(0, dp(12), 0, dp(12));
        root.addView(fileLabel, matchWrap());
        updateFileLabel();

        audibleOutput = new CheckBox(this);
        audibleOutput.setText("同时播放给本机（关闭时仅输出到麦克风）");
        root.addView(audibleOutput, matchWrap());

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
        moduleNote.setText("首次使用：在 libxposed 管理器中启用模块并配置作用域；支持 AudioRecord 的应用可加入作用域。");
        moduleNote.setTextSize(13);
        moduleNote.setPadding(0, dp(20), 0, 0);
        root.addView(moduleNote, matchWrap());
        setContentView(root);
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
        if (selectedUri == null) {
            Toast.makeText(this, "请先选择音频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        Intent intent = new Intent(this, ForwardingService.class)
                .setAction(ForwardingService.ACTION_START)
                .setData(selectedUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(ForwardingService.EXTRA_AUDIBLE, audibleOutput.isChecked())
                .putExtra(ForwardingService.EXTRA_GAIN, forwardingGain / 100.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "正在启动音频转发", Toast.LENGTH_SHORT).show();
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
