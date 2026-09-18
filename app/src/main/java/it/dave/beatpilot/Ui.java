package it.dave.beatpilot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    static int dp(android.content.Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static void insets(View root) {
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left + dp(v.getContext(), 16), i.top + dp(v.getContext(), 12),
                    i.right + dp(v.getContext(), 16), i.bottom + dp(v.getContext(), 12));
            return in;
        });
        root.requestApplyInsets();
    }
    static LinearLayout column(Activity a) {
        LinearLayout layout = new LinearLayout(a); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(244, 246, 245)); return layout;
    }
    static TextView text(LinearLayout parent, String label, int size) {
        TextView t = new TextView(parent.getContext()); t.setText(label); t.setTextSize(size);
        t.setTextColor(Color.rgb(23, 39, 35)); t.setPadding(0, dp(parent.getContext(), 8), 0, dp(parent.getContext(), 8));
        parent.addView(t); return t;
    }
    static Button button(LinearLayout parent, String label, Runnable click) {
        Button b = new Button(parent.getContext()); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> click.run()); parent.addView(b); return b;
    }
    static void toast(android.content.Context c, String text) { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
}
