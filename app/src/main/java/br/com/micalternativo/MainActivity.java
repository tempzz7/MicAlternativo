package br.com.micalternativo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("MicAlternativo");
        title.setTextSize(24f);
        root.addView(title);
        TextView version = new TextView(this);
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        version.setText("v" + v);
        root.addView(version);
        setContentView(root);
    }
}
