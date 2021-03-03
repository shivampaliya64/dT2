package com.example.dt2.data;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.dt2.AppConst;
import com.example.dt2.database.DbExecutor;
import com.example.dt2.database.IgnoreItem;
import com.example.dt2.util.AppUtil;
import com.example.dt2.util.PreferenceManager;



public class DataManager {

    private static Map<String, Map<String, Object>> mCacheData = new HashMap<>();

    public void requestPermission(Context context) {
        context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    public boolean hasPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps != null) {
            int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        }
        return false;
    }

    public List<AppItem> getTargetAppTimeline(Context context, String target) {
        List<AppItem> items = new ArrayList<>();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager != null) {

            long endTime = System.currentTimeMillis();
            long startTime = AppUtil.startOfDay(endTime);
            UsageEvents events = manager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();

            AppItem item = new AppItem();
            item.mPackageName = target;
            item.mName = AppUtil.parsePackageName(context.getPackageManager(), target);

            long start = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String currentPackage = event.getPackageName();
                int eventType = event.getEventType();
                long eventTime = event.getTimeStamp();
                if (currentPackage.equals(target)) {
                    if (eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) { //
                        if (start == 0) {
                            start = eventTime;
                            item.mEventTime = eventTime;
                            item.mEventType = eventType;
                            item.mUsageTime = 0;
                            items.add(item.copy());
                        }
                    }
                } else { //
                    if (start > 0) {
                        item.mUsageTime = eventTime - start;
                        if (item.mUsageTime > AppConst.USAGE_TIME_MIX) { //
                            item.mCount++;
                        }
                        item.mEventTime = eventTime;
                        item.mEventType = UsageEvents.Event.MOVE_TO_BACKGROUND;
                        items.add(item.copy());
                        start = 0;
                    }
                }
            }
        }
        return items;
    }

    public List<AppItem> getApps(Context context, int sort) {

        List<AppItem> items = new ArrayList<>();
        List<AppItem> newList = new ArrayList<>();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager != null) {

            String prevPackage = "";
            Map<String, Long> startPoints = new HashMap<>();

            long endTime = System.currentTimeMillis();
            long startTime = AppUtil.startOfDay(endTime);
            UsageEvents events = manager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {

                events.getNextEvent(event);
                int eventType = event.getEventType();
                long eventTime = event.getTimeStamp();
                String eventPackage = event.getPackageName();

                if (eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {

                    AppItem item = containItem(items, eventPackage);
                    if (item == null) {
                        item = new AppItem();
                        item.mPackageName = eventPackage;
                        items.add(item);
                    }
                    if (!startPoints.containsKey(eventPackage) || startPoints.get(eventPackage) == 0) {
                        startPoints.put(eventPackage, eventTime);
                    }
                }

                if (TextUtils.isEmpty(prevPackage)) prevPackage = eventPackage;
                if (!prevPackage.equals(eventPackage)) {
                    if (startPoints.containsKey(prevPackage) && startPoints.get(prevPackage) > 0) {

                        long start = startPoints.get(prevPackage);
                        AppItem prevItem = containItem(items, prevPackage);
                        if (prevItem != null) {
                            prevItem.mEventTime = eventTime;
                            prevItem.mUsageTime += eventTime - start;
                            if (prevItem.mUsageTime > AppConst.USAGE_TIME_MIX) {
                                prevItem.mCount++;
                            }
                        }

                        startPoints.put(prevPackage, 0L);
                    }
                    prevPackage = eventPackage;

                    if (eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {

                        AppItem item2 = containItem(items, eventPackage);
                        if (item2 == null) {
                            item2 = new AppItem();
                            item2.mPackageName = eventPackage;
                            items.add(item2);
                        }
                        if (!startPoints.containsKey(eventPackage) || startPoints.get(eventPackage) == 0) {
                            startPoints.put(eventPackage, eventTime);
                        }
                    }
                }
            }
        }

        if (items.size() > 0) {
            if (sort == 0) {
                Collections.sort(items, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return (int) (right.mEventTime - left.mEventTime);
                    }
                });
            } else if (sort == 1) {
                Collections.sort(items, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return (int) (right.mUsageTime - left.mUsageTime);
                    }
                });
            } else {
                Collections.sort(items, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return right.mCount - left.mCount;
                    }
                });
            }

            boolean hideSystem = PreferenceManager.getInstance().getBoolean(PreferenceManager.PREF_SETTINGS_HIDE_SYSTEM_APPS);
            boolean hideUninstall = PreferenceManager.getInstance().getBoolean(PreferenceManager.PREF_SETTINGS_HIDE_UNINSTALL_APPS);
            List<IgnoreItem> ignoreItems = DbExecutor.getInstance().getAllItems();
            PackageManager packageManager = context.getPackageManager();
            for (AppItem item : items) {
                if (hideSystem && AppUtil.isSystemApp(packageManager, item.mPackageName)) {
                    continue;
                }
                if (hideUninstall && !AppUtil.isInstalled(packageManager, item.mPackageName)) {
                    continue;
                }
                if (inIgnoreList(ignoreItems, item.mPackageName)) {
                    continue;
                }
                item.mName = AppUtil.parsePackageName(packageManager, item.mPackageName);
                newList.add(item);
            }
        }
        return newList;
    }

    private AppItem containItem(List<AppItem> items, String packageName) {
        for (AppItem item : items) {
            if (item.mPackageName.equals(packageName)) return item;
        }
        return null;
    }

    private boolean inIgnoreList(List<IgnoreItem> items, String packageName) {
        for (IgnoreItem item : items) {
            if (item.mPackageName.equals(packageName)) return true;
        }
        return false;
    }
}
