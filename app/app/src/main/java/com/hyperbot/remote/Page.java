package com.hyperbot.remote;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** One tab: a scrolling column of panels, plus hooks the shell calls. */
abstract class Page {
    final MainActivity host;
    final ScrollView root;
    final LinearLayout body;

    Page(MainActivity host) {
        this.host = host;
        root = new ScrollView(host);
        root.setBackgroundColor(Brutal.PAPER);
        root.setFillViewport(true);
        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dp(18), host.dp(16), host.dp(18), host.dp(28));
        // Keep text fields from grabbing focus (and the scroll position) when a page opens.
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        body.setClipToPadding(false);
        root.addView(body, new ScrollView.LayoutParams(-1, -2));
    }

    Hero hero;
    private boolean entered;

    /** Full-bleed page opener, flush with the header ribbon. */
    void setHero(Hero h) {
        hero = h;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = -host.dp(18);
        lp.rightMargin = -host.dp(18);
        lp.topMargin = -host.dp(16);
        body.addView(h, lp);
    }

    /** Called by the shell when this tab comes on screen: hero plays, sections rise (first time). */
    final void enter() {
        if (hero != null) hero.play();
        if (!entered) {
            entered = true;
            Motion.stagger(body, hero != null ? 1 : 0, 300, 70);
        }
    }

    /** Called when another tab takes over: stop idle loops. */
    final void leave() {
        if (hero != null) hero.stop();
    }

    void add(View v, int topDp) { body.addView(v, Brutal.spaced(host, -1, -2, topDp)); }

    void onShow() { }
    void onLinkChanged(BotLink.Link state, String detail) { onBusyChanged(); }
    void onBusyChanged() { }
    void onProgress(BotLink.Kind kind, int part, int parts, String label, long partDone, long partSize, long allDone, long allSize) { }
    void onJobDone(BotLink.Kind kind, int count) { }
    void onJobFailed(BotLink.Kind kind, String reason) { }

    boolean canSend() { return host.link.ready(); }
}
