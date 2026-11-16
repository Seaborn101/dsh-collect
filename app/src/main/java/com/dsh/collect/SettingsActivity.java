package com.dsh.collect;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：模型接入 + 提示词模板。 */
public class SettingsActivity extends Activity {

    private EditText endpoint;
    private EditText apiKey;
    private EditText model;
    private EditText temp;
    private EditText prompt;
    private EditText promptL2;
    private Switch autoSwitch;
    private Switch remindSwitch;
    private TextView remindTime;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        endpoint = findViewById(R.id.in_endpoint);
        apiKey = findViewById(R.id.in_key);
        model = findViewById(R.id.in_model);
        temp = findViewById(R.id.in_temp);
        prompt = findViewById(R.id.in_prompt);
        promptL2 = findViewById(R.id.in_prompt_l2);
        autoSwitch = findViewById(R.id.sw_auto);
        remindSwitch = findViewById(R.id.sw_remind);
        remindTime = findViewById(R.id.btn_remind_time);
        status = findViewById(R.id.status_text);

        Settings s = Settings.load(this);
        endpoint.setText(s.endpoint);
        apiKey.setText(s.apiKey);
        model.setText(s.model);
        temp.setText(String.valueOf(s.temperature));
        prompt.setText(s.prompt);
        autoSwitch.setChecked(s.autoProcess);
        promptL2.setText(s.promptL2);
        remindSwitch.setChecked(s.autoRemind);
        remindTime.setText(Reminder.timeText(s.remindHour, s.remindMinute));
        status.setText(s.lastMessage.isEmpty() ? getString(R.string.no_run_yet) : s.lastMessage);

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_reset_prompt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prompt.setText(Settings.DEFAULT_PROMPT);
            }
        });
        findViewById(R.id.btn_reset_prompt_l2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptL2.setText(Settings.DEFAULT_PROMPT_L2);
            }
        });
        remindTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime();
            }
        });
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                toast("已保存");
                finish();
            }
        });
        findViewById(R.id.btn_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.io.File f = Backup.exportBackup(SettingsActivity.this);
                if (f == null) {
                    toast(getString(R.string.backup_fail));
                } else {
                    toast(getString(R.string.backup_done, f.getAbsolutePath()));
                }
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                testCall();
            }
        });
    }

    private void save() {
        Settings s = Settings.load(this);
        s.endpoint = endpoint.getText().toString().trim();
        s.apiKey = apiKey.getText().toString().trim();
        s.model = model.getText().toString().trim();
        try {
            s.temperature = Float.parseFloat(temp.getText().toString().trim());
        } catch (Exception ignored) {
            s.temperature = 0.4f;
        }
        s.prompt = prompt.getText().toString();
        s.promptL2 = promptL2.getText().toString();
        s.autoProcess = autoSwitch.isChecked();
        s.autoRemind = remindSwitch.isChecked();
        s.save(this);
        Reminder.schedule(this);
    }

    /** 用一个极短的请求测试连通性 */
    private void testCall() {
        Settings s = Settings.load(this);
        if (!s.isConfigured()) {
            status.setText(getString(R.string.not_configured));
            return;
        }
        status.setText(getString(R.string.testing));
        final Settings cfg = s;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LlmClient.Result r = LlmClient.complete(
                        cfg.endpoint, cfg.apiKey, cfg.model,
                        "你是测试助手",
                        "只回复两个字：通了",
                        0.1f, 60);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.ok) {
                            status.setText(getString(R.string.test_ok));
                            toast("接口正常");
                        } else {
                            status.setText(getString(R.string.test_fail) + " " + r.error);
                            toast("调用失败：" + r.error);
                        }
                    }
                });
            }
        }).start();
    }

    /** 选提醒时间 */
    private void pickTime() {
        final Settings s = Settings.load(this);
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(this,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker view, int hour, int minute) {
                        s.remindHour = hour;
                        s.remindMinute = minute;
                        s.save(SettingsActivity.this);
                        remindTime.setText(Reminder.timeText(hour, minute));
                    }
                }, s.remindHour, s.remindMinute, true);
        dlg.show();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
