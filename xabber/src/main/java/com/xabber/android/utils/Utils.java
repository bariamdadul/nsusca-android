package com.xabber.android.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.TypedValue;

import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.push.SyncManager;
import com.xabber.android.service.XabberService;

import java.util.Calendar;
import java.util.Date;

public class Utils {

    public static int dipToPx(float dip, Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                dip, context.getResources().getDisplayMetrics());
    }

    public static boolean isSameDay(Long date1, Long date2) {
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();
        cal1.setTime(new Date(date1));
        cal2.setTime(new Date(date2));
        return cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) &&
                cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR);
    }

    public static void startXabberServiceCompat(Context context) {
        startXabberServiceCompat(context, XabberService.createIntent(context));
    }

    public static void startXabberServiceCompatWithSyncMode(Context context, String pushNode) {
        startXabberServiceCompat(context,
                SyncManager.createXabberServiceIntentWithSyncMode(context, pushNode));
    }

    public static void startXabberServiceCompatWithSyncMode(Context context, AccountJid accountJid) {
        startXabberServiceCompat(context,
                SyncManager.createXabberServiceIntentWithSyncMode(context, accountJid));
    }

    private static void startXabberServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static String xmlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        char c;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;"); //$NON-NLS-1$
                    break;
                case '>':
                    sb.append("&gt;"); //$NON-NLS-1$
                    break;
                case '&':
                    sb.append("&amp;"); //$NON-NLS-1$
                    break;
                case '\'':
                    // In this implementation we use &apos; instead of &#39; because we encode XML, not HTML.
                    sb.append("&apos;"); //$NON-NLS-1$
                    break;
                case '"':
                    sb.append("&quot;"); //$NON-NLS-1$
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
