package app.relayview;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int BG=0xff101616, PANEL=0xff192222, BORDER=0xff32413d, TEXT=0xfff0f5f1,
            MUTED=0xffa2b1ac, MINT=0xffa8eac9, INK=0xff14291e, ERROR=0xffffb8a7;
    static int dp(Context c, float value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }

    static GradientDrawable shape(Context c, int fill, int edge, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(c, radius));
        if (edge != 0) d.setStroke(dp(c, 1), edge);
        return d;
    }

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    static void pad(View v, int x, int y) { int h=dp(v.getContext(),x), t=dp(v.getContext(),y); v.setPadding(h,t,h,t); }
    static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static void space(LinearLayout l, int n) { l.addView(new Space(l.getContext()),1,dp(l.getContext(),n)); }
    static Button button(Context c, String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? INK : TEXT); b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33669e83),
                shape(c,primary ? MINT : PANEL, primary ? 0 : BORDER,14),null));
        pad(b,16,8); b.setOnClickListener(action);
        return b;
    }

    static EditText field(LinearLayout owner, String label, String value, int type) {
        Context c=owner.getContext();
        TextView title=text(c,label,13,MUTED,true);
        owner.addView(title); space(owner,6);
        EditText field = new EditText(c);
        field.setId(View.generateViewId()); title.setLabelFor(field.getId());
        field.setSingleLine(); field.setInputType(type); field.setTextColor(TEXT);
        field.setTextSize(16); field.setText(value); field.setSaveEnabled(false);
        field.setBackground(shape(c,PANEL,BORDER,12)); pad(field,14,10);
        owner.addView(field,-1,dp(c,50)); space(owner,15);
        return field;
    }

    static void insets(View v) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            view.setPadding(ins.getSystemWindowInsetLeft(),ins.getSystemWindowInsetTop(),
                    ins.getSystemWindowInsetRight(),ins.getSystemWindowInsetBottom());
            return ins;
        });
        v.requestApplyInsets();
    }
}
