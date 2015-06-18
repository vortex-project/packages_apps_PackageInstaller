/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.packageinstaller.permission.model;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.graphics.LightingColorFilter;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.packageinstaller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PermissionApps {
    private static final String LOG_TAG = "PermissionApps";

    private final Context mContext;
    private final String mGroupName;
    private final PackageManager mPm;
    private final Callback mCallback;

    private final PmCache mCache;

    private CharSequence mLabel;
    private Drawable mIcon;
    private List<PermissionApp> mPermApps;
    // Map (pkg|uid) -> AppPermission
    private ArrayMap<String, PermissionApp> mAppLookup;

    private boolean mSkipUi;

    public PermissionApps(Context context, String groupName, Callback callback) {
        this(context, groupName, callback, null);
    }

    public PermissionApps(Context context, String groupName, Callback callback, PmCache cache) {
        mCache = cache;
        mContext = context;
        mPm = mContext.getPackageManager();
        mGroupName = groupName;
        mCallback = callback;
        loadGroupInfo();
    }

    public void loadNowWithoutUi() {
        mSkipUi = true;
        createMap(loadPermissionApps());
    }

    public void refresh(boolean getUiInfo) {
        mSkipUi = !getUiInfo;
        new PermissionAppsLoader().execute();
    }

    public int getGrantedCount() {
        int count = 0;
        for (PermissionApp app : mPermApps) {
            if (!Utils.shouldShowPermission(app)) {
                continue;
            }
            if (app.isSystem()) {
                // We default to not showing system apps, so hide them from count.
                continue;
            }
            if (app.areRuntimePermissionsGranted()) {
                count++;
            }
        }
        return count;
    }

    public int getTotalCount() {
        int count = 0;
        for (PermissionApp app : mPermApps) {
            if (!Utils.shouldShowPermission(app)) {
                continue;
            }
            if (app.isSystem()) {
                // We default to not showing system apps, so hide them from count.
                continue;
            }
            count++;
        }
        return count;
    }

    public Collection<PermissionApp> getApps() {
        return mPermApps;
    }

    public PermissionApp getApp(String key) {
        return mAppLookup.get(key);
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    private List<PermissionApp> loadPermissionApps() {
        PackageItemInfo groupInfo = getGroupInfo(mGroupName);
        if (groupInfo == null) {
            return Collections.emptyList();
        }

        List<PermissionInfo> groupPermInfos = getGroupPermissionInfos(mGroupName);
        if (groupPermInfos == null) {
            return Collections.emptyList();
        }

        ArrayList<PermissionApp> permApps = new ArrayList<>();

        for (UserHandle user : UserManager.get(mContext).getUserProfiles()) {
            List<PackageInfo> apps = mCache != null ? mCache.getPackages(user.getIdentifier())
                    : mPm.getInstalledPackages(PackageManager.GET_PERMISSIONS,
                            user.getIdentifier());

            final int N = apps.size();
            for (int i = 0; i < N; i++) {
                PackageInfo app = apps.get(i);
                if (app.requestedPermissions == null) {
                    continue;
                }

                for (int j = 0; j < app.requestedPermissions.length; j++) {
                    String requestedPerm = app.requestedPermissions[j];

                    boolean requestsPermissionInGroup = false;

                    for (PermissionInfo groupPermInfo : groupPermInfos) {
                        if (groupPermInfo.name.equals(requestedPerm)) {
                            requestsPermissionInGroup = true;
                            break;
                        }
                    }

                    if (!requestsPermissionInGroup) {
                        continue;
                    }

                    AppPermissionGroup group = AppPermissionGroup.create(mContext,
                            app, groupInfo, groupPermInfos, user);

                    String label = mSkipUi ? app.packageName
                            : app.applicationInfo.loadLabel(mPm).toString();
                    PermissionApp permApp = new PermissionApp(app.packageName,
                            group, label, getBadgedIcon(app.applicationInfo),
                            app.applicationInfo.isSystemApp());

                    permApps.add(permApp);
                }
            }
        }

        Collections.sort(permApps);

        return permApps;
    }

    private void createMap(List<PermissionApp> result) {
        mAppLookup = new ArrayMap<>();
        for (PermissionApp app : result) {
            mAppLookup.put(app.getKey(), app);
        }
        mPermApps = result;
    }

    private PackageItemInfo getGroupInfo(String groupName) {
        try {
            return mContext.getPackageManager().getPermissionGroupInfo(groupName, 0);
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        try {
            return mContext.getPackageManager().getPermissionInfo(groupName, 0);
        } catch (NameNotFoundException e2) {
            /* ignore */
        }
        return null;
    }

    private List<PermissionInfo> getGroupPermissionInfos(String groupName) {
        try {
            return mContext.getPackageManager().queryPermissionsByGroup(groupName, 0);
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        try {
            PermissionInfo permissionInfo = mContext.getPackageManager()
                    .getPermissionInfo(groupName, 0);
            List<PermissionInfo> permissions = new ArrayList<>();
            permissions.add(permissionInfo);
            return permissions;
        } catch (NameNotFoundException e2) {
            /* ignore */
        }
        return null;
    }

    private Drawable getBadgedIcon(ApplicationInfo appInfo) {
        if (mSkipUi) {
            return null;
        }
        Drawable unbadged = appInfo.loadUnbadgedIcon(mPm);
        return mPm.getUserBadgedIcon(unbadged,
                new UserHandle(UserHandle.getUserId(appInfo.uid)));
    }

    private void loadGroupInfo() {
        PackageItemInfo info;
        try {
            info = mPm.getPermissionGroupInfo(mGroupName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                PermissionInfo permInfo = mPm.getPermissionInfo(mGroupName, 0);
                if (permInfo.protectionLevel != PermissionInfo.PROTECTION_DANGEROUS) {
                    Log.w(LOG_TAG, mGroupName + " is not a runtime permission");
                    return;
                }
                info = permInfo;
            } catch (NameNotFoundException reallyNotFound) {
                Log.w(LOG_TAG, "Can't find permission: " + mGroupName, reallyNotFound);
                return;
            }
        }
        mLabel = info.loadLabel(mPm);
        if (info.icon != 0) {
            mIcon = info.loadUnbadgedIcon(mPm);
        } else {
            mIcon = mContext.getDrawable(com.android.internal.R.drawable.ic_perm_device_info);
        }
        LightingColorFilter filter = new LightingColorFilter(0, 0xffffffff);
        mIcon.setColorFilter(filter);
    }

    public static class PermissionApp implements Comparable<PermissionApp> {
        private final String mPackageName;
        private final AppPermissionGroup mAppPermissionGroup;
        private final String mLabel;
        private final Drawable mIcon;
        private final boolean mSystem;

        public PermissionApp(String packageName, AppPermissionGroup appPermissionGroup,
                String label, Drawable icon, boolean isSystem) {
            mPackageName = packageName;
            mAppPermissionGroup = appPermissionGroup;
            mLabel = label;
            mIcon = icon;
            mSystem = isSystem;
        }

        public boolean isSystem() {
            return mSystem;
        }

        public String getKey() {
            return Integer.toString(getUid());
        }

        public String getLabel() {
            return mLabel;
        }

        public Drawable getIcon() {
            return mIcon;
        }

        public boolean areRuntimePermissionsGranted() {
            return mAppPermissionGroup.areRuntimePermissionsGranted();
        }

        public void grantRuntimePermissions() {
            mAppPermissionGroup.grantRuntimePermissions(false);
        }

        public void revokeRuntimePermissions() {
            mAppPermissionGroup.revokeRuntimePermissions(false);
        }

        public boolean isPolicyFixed() {
            return mAppPermissionGroup.isPolicyFixed();
        }

        public boolean isSystemFixed() {
            return mAppPermissionGroup.isSystemFixed();
        }

        public boolean hasRuntimePermissions() {
            return mAppPermissionGroup.hasRuntimePermission();
        }

        public boolean hasAppOpPermissions() {
            return mAppPermissionGroup.hasAppOpPermission();
        }

        public String getPackageName() {
            return mPackageName;
        }

        public AppPermissionGroup getPermissionGroup() {
            return mAppPermissionGroup;
        }

        @Override
        public int compareTo(PermissionApp another) {
            final int result = mLabel.compareTo(another.mLabel);
            if (result == 0) {
                // Unbadged before badged.
                return getUid() - another.getUid();
            }
            return result;
        }

        public int getUid() {
            return mAppPermissionGroup.getApp().applicationInfo.uid;
        }
    }

    private class PermissionAppsLoader extends AsyncTask<Void, Void, List<PermissionApp>> {

        @Override
        protected List<PermissionApp> doInBackground(Void... args) {
            return loadPermissionApps();
        }

        @Override
        protected void onPostExecute(List<PermissionApp> result) {
            createMap(result);
            if (mCallback != null) {
                mCallback.onPermissionsLoaded(PermissionApps.this);
            }
        }
    }

    /**
     * Class used to reduce the number of calls to the package manager.
     * This caches app information so it should only be used across parallel PermissionApps
     * instances, and should not be retained across UI refresh.
     */
    public static class PmCache {
        private final SparseArray<List<PackageInfo>> mPackageInfoCache = new SparseArray<>();
        private final PackageManager mPm;

        public PmCache(PackageManager pm) {
            mPm = pm;
        }

        public synchronized List<PackageInfo> getPackages(int userId) {
            List<PackageInfo> ret = mPackageInfoCache.get(userId);
            if (ret == null) {
                ret = mPm.getInstalledPackages(PackageManager.GET_PERMISSIONS, userId);
                mPackageInfoCache.put(userId, ret);
            }
            return ret;
        }
    }

    public interface Callback {
        void onPermissionsLoaded(PermissionApps permissionApps);
    }
}