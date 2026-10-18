package com.floatwindow.morebubblebutton;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class SettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.floatwindow.morebubblebutton.settings";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (getContext() == null) return null;
        SharedPreferences prefs = getContext().getSharedPreferences(ModuleSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        add(cursor, ModuleSettings.KEY_MENU_ENABLED, prefs.getBoolean(ModuleSettings.KEY_MENU_ENABLED, true));
        add(cursor, ModuleSettings.KEY_ACTION_BAR_ENABLED, prefs.getBoolean(ModuleSettings.KEY_ACTION_BAR_ENABLED, true));
        add(cursor, ModuleSettings.KEY_POSITION_MODE, prefs.getInt(ModuleSettings.KEY_POSITION_MODE, 0));
        add(cursor, ModuleSettings.KEY_POS_X, prefs.getInt(ModuleSettings.KEY_POS_X, 50));
        add(cursor, ModuleSettings.KEY_POS_Y, prefs.getInt(ModuleSettings.KEY_POS_Y, 50));
        add(cursor, ModuleSettings.KEY_SYSTEMUI_BUBBLE_ENABLED, prefs.getBoolean(ModuleSettings.KEY_SYSTEMUI_BUBBLE_ENABLED, true));
        return cursor;
    }

    private static void add(MatrixCursor cursor, String key, boolean value) {
        cursor.addRow(new Object[]{key, value ? "1" : "0"});
    }

    private static void add(MatrixCursor cursor, String key, int value) {
        cursor.addRow(new Object[]{key, Integer.toString(value)});
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.floatwindow.morebubblebutton.settings";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
