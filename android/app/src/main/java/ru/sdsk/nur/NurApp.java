package ru.sdsk.nur;

import android.app.Application;

public class NurApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Push.init(this);
    }
}
