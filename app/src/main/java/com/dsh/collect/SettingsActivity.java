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
    private Switch autoSwitch;
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
        autoSwitch = findViewById(R.id.sw_auto);
        status = findViewById(R.id.status_text);

        Settings s = Settings.load(this);
        endpoint.setText(s.endpoint);
        apiKey.setText(s.apiKey);
        model.setText(s.model);
        temp.setText(String.valueOf(s.temperature));
        prompt.setText(s.prompt);
        autoSwitch.setChecked(s.autoProcess);
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
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                toast("已保存");
                finish();
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
        s.autoProcess = autoSwitch.isChecked();
        s.save(this);
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

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
