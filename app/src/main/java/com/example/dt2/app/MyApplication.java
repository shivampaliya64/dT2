package com.example.dt2.app;

import android.app.Application;
import android.content.Intent;

import com.example.dt2.BuildConfig;
import com.example.dt2.data.AppItem;
import com.example.dt2.database.DbExecutor;
import com.example.dt2.service.AppService;
import com.example.dt2.util.PreferenceManager;


public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.init(this);
        getApplicationContext().startService(new Intent(getApplicationContext(), AppService.class));
        DbExecutor.init(getApplicationContext());
        AppItem item = new AppItem();
        item.mPackageName = BuildConfig.APPLICATION_ID;
        item.mEventTime = System.currentTimeMillis();
        DbExecutor.getInstance().insertItem(item);
    }
}
