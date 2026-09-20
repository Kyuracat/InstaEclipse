package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide Specific Chats: let the user hide chosen DM threads from the Direct inbox.
 *
 * Two parts:
 *  1. Inbox filter — hooks the DirectThreadStore's inbox thread-summary builder (anchored by the
 *     stable string "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries") and removes rows whose
 *     thread id is in the persistent hidden set (HiddenThreads). Each row exposes a
 *     com.instagram.model.direct.DirectThreadKey; its first String field is the thread id (same
 *     resolution KeepUnsentMessagesHook/UnsentThreadButtonHook already use).
 *  2. Hide toggle — injects an eye-off button into the OPEN thread's header (same global-layout
 *     pattern as UnsentThreadButtonHook); tapping it hides/unhides the current thread.
 *
 * Gated on FeatureFlags.hideSpecificChats.
 */
public class HideChatsHook {

    private static final String TAG = "ie_hidechat_btn";
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static int threadHeaderId, backButtonId;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installInboxFilter(bridge, classLoader);
        installHeaderButton(classLoader);
        installThreadTracker(bridge, classLoader);
    }

    // ── 1. Inbox thread-list filter ────────────────────────────────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats || HiddenThreads.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof java.util.List) || ((java.util.List<?>) r).isEmpty()) return;
                try {
                    java.util.Iterator<?> it = ((java.util.List<?>) r).iterator();
                    while (it.hasNext()) {
                        String id = threadIdOfRow(it.next());
                        if (id != null && HiddenThreads.isHidden(id)) it.remove();
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter); n++; }
                catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    /** Finds the DirectThreadKey on an inbox row object and returns its thread id (first String). */
    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DirectThreadKey")) continue;
                    f.setAccessible(true);
                    Object key = f.get(row);
                    String id = firstStringField(key);
                    if (id != null) return id;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        // fallback: the key may sit one or two objects deeper inside the row
        try {
            Object dtk = scan(row, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()), 3);
            if (dtk != null) return firstStringField(dtk);
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats) return;
                final Activity a = (Activity) param.thisObject;
                a.runOnUiThread(new Runnable() { @Override public void run() { registerListener(a); } });
            }
        };
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try { XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume); }
            catch (Throwable t) { ModuleLog.line("(IE|HideChats) ⚠️ hook " + act + ": " + t.getMessage()); }
        }
        ModuleLog.line("(IE|HideChats) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        backButtonId   = r.getIdentifier("action_bar_button_back", "id", pkg);
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            ViewGroup target;
            int insertAt;
            View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;
            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            if (target.findViewWithTag(TAG) != null) return true;

            android.widget.ImageView btn = new android.widget.ImageView(activity);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(activity, 9);
            btn.setPadding(pad, pad, pad, pad);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("Hide chat");
            int sz = dp(activity, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);

            final Activity act2 = activity;
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String threadId = resolveThreadId(act2);
                    if (threadId == null) {
                        Toast.makeText(act2, I18n(act2, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(act2));
                    Toast.makeText(act2,
                            I18n(act2, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                            Toast.LENGTH_SHORT).show();
                    ModuleLog.line("(IE|HideChats) toggled thread=" + threadId + " hidden=" + nowHidden);
                }
            });
            try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
            catch (Throwable t) { target.addView(btn); }
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }

    private static String I18n(Activity a, int res) {
        return ps.reso.instaeclipse.utils.i18n.I18n.t(a, res);
    }

    /**
     * Best-effort title of the open thread (username, for the unhide-manager label). The
     * thread_title id resolves but findViewById returns null here, so scan the header subtree for
     * its TextViews and pick the @username (a handle-like token), falling back to the first name.
     */
    private String threadTitle(Activity a) {
        try {
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (!(header instanceof ViewGroup)) return "";
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t; // handle-like
            return texts.isEmpty() ? "" : texts.get(0);
        } catch (Throwable t) { return ""; }
    }

    private void collectTexts(ViewGroup vg, java.util.List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) c).getText();
                if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }

    // ── thread-id resolution (mirrors UnsentThreadButtonHook.resolveFromActivity — proven) ──
    private String resolveThreadId(Activity activity) {
        try {
            // 1. Intent extras (thread key usually passed here); depth 4.
            android.os.Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object dtk = scan(ex.get(k), 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 4);
                    if (dtk != null) { String id = firstStringField(dtk); if (id != null) return id; }
                }
            }
            // 2. Activity object graph (hosted thread fragment holds the key); depth 6.
            Object dtk = scan(activity, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 6);
            String id = dtk != null ? firstStringField(dtk) : null;
            if (id != null) { ModuleLog.line("(IE|HideChats) id via object-graph scan"); return id; }
        } catch (Throwable t) { /* fall through to the other strategies */ }

        // 3. Open-thread tracker (hooked "igThreadIgid" (DirectThreadKey, boolean) -> void).
        String t = trackedVisibleId;
        if (t != null) { ModuleLog.line("(IE|HideChats) id via thread tracker"); return t; }
        t = KeepUnsentMessagesHook.currentThreadId;
        if (t != null) { ModuleLog.line("(IE|HideChats) id via KeepUnsent tracker"); return t; }

        // 4. Wider object-graph scan (also walks androidx/fragment internals, collections, arrays).
        try {
            int[] budget = new int[]{6000};
            Object dtk = scanWide(activity, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()), 8, budget);
            String id = dtk != null ? firstStringField(dtk) : null;
            if (id != null) { ModuleLog.line("(IE|HideChats) id via wide scan"); return id; }
        } catch (Throwable ignored) {}

        // 5. Last resort: the most recent thread event, whatever its flag.
        t = trackedAnyId;
        if (t != null) { ModuleLog.line("(IE|HideChats) id via last thread event"); return t; }
        ModuleLog.line("(IE|HideChats) ⚠️ could not identify thread (tracker events seen: " + trackerEvents + ")");
        return null;
    }

    // ── open-thread tracker ────────────────────────────────────────────────────
    private static volatile String trackedVisibleId, trackedAnyId;
    private static volatile int trackerEvents;

    private void installThreadTracker(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        String id = firstStringField(param.args[0]);
                        if (id == null) return;
                        trackerEvents++;
                        trackedAnyId = id;
                        if (param.args.length > 1 && Boolean.TRUE.equals(param.args[1])) trackedVisibleId = id;
                    } catch (Throwable ignored) {}
                }
            };
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("igThreadIgid")
                            .paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), hook); n++; }
                catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|HideChats) thread tracker hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ thread tracker: " + t.getMessage());
        }
    }

    /** Wider scan: descends androidx/X./app classes, collections, maps and arrays (bounded). */
    private static Object scanWide(Object obj, int depth, java.util.Set<Object> seen, int maxDepth, int[] budget) {
        if (obj == null || depth > maxDepth || budget[0] <= 0 || !seen.add(obj)) return null;
        budget[0]--;
        Class<?> oc = obj.getClass();
        String cn = oc.getName();
        if (cn.contains("DirectThreadKey")) return obj;
        if (cn.startsWith("android.") && !(obj instanceof android.os.Bundle)) return null;
        if (cn.startsWith("java.") && !(obj instanceof java.util.Collection) && !(obj instanceof java.util.Map)
                && !oc.isArray()) return null;
        if (cn.startsWith("kotlin.") || cn.startsWith("dalvik.") || cn.startsWith("libcore.")
                || cn.startsWith("com.android.") || cn.startsWith("javax.")) return null;
        try {
            if (obj instanceof android.os.Bundle) {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scanWide(b.get(k), depth + 1, seen, maxDepth, budget);
                    if (r != null) return r;
                }
                return null;
            }
            if (obj instanceof java.util.Collection) {
                int i = 0;
                for (Object e : (java.util.Collection<?>) obj) {
                    if (i++ > 60) break;
                    Object r = scanWide(e, depth + 1, seen, maxDepth, budget);
                    if (r != null) return r;
                }
                return null;
            }
            if (obj instanceof java.util.Map) {
                int i = 0;
                for (Object e : ((java.util.Map<?, ?>) obj).values()) {
                    if (i++ > 60) break;
                    Object r = scanWide(e, depth + 1, seen, maxDepth, budget);
                    if (r != null) return r;
                }
                return null;
            }
            if (oc.isArray()) {
                if (oc.getComponentType().isPrimitive()) return null;
                Object[] arr = (Object[]) obj;
                for (int i = 0; i < arr.length && i < 60; i++) {
                    Object r = scanWide(arr[i], depth + 1, seen, maxDepth, budget);
                    if (r != null) return r;
                }
                return null;
            }
            for (Class<?> c = oc; c != null && c != Object.class; c = c.getSuperclass()) {
                String ccn = c.getName();
                if (ccn.startsWith("android.") || ccn.startsWith("java.")) break; // framework base classes
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scanWide(v, depth + 1, seen, maxDepth, budget);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Find a DirectThreadKey in obj's field graph (depth-limited; descends IG/obfuscated + Bundles). */
    private static Object scan(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof android.os.Bundle;
        if (!descendable) return null;
        if (obj instanceof android.os.Bundle) {
            try {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scan(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scan(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstStringField(Object o) {
        if (o == null) return null;
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
