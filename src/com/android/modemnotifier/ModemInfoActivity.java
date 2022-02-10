package com.android.modemnotifier;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ModemInfoActivity extends Activity{
    private TextView mNotifierInfoTextView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mNotifierInfoTextView = new TextView(this.getApplicationContext());

        String notifierInfo = this.getIntent().getStringExtra("notifierInfo");
        mNotifierInfoTextView.setText(notifierInfo);
        this.setContentView(mNotifierInfoTextView);

    }
}
