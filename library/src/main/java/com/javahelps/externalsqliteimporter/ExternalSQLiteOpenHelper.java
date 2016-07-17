package com.javahelps.externalsqliteimporter;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.support.v4.content.PermissionChecker;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Scanner;

/**
 * Import database from external location into the Android application.
 */
public abstract class ExternalSQLiteOpenHelper {
    private final SQLiteOpenHelper sqLiteOpenHelper;
    public static final String READ_EXTERNAL_STORAGE_PERMISSION = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String TAG = ExternalSQLiteOpenHelper.class.getSimpleName();
    private static final String DATABASES = "databases";
    private static final String VERSION_INFO = "version.info";


    private final String sourceDirectory;
    private final Context context;
    private final String databaseName;
    private final String updateScript;

    public ExternalSQLiteOpenHelper(Context context, String name, String sourceDirectory, SQLiteDatabase.CursorFactory factory, int version) {
        this.sqLiteOpenHelper = new InternalOpenHelper(context, name, factory, version);
        this.sourceDirectory = sourceDirectory;
        this.context = context;
        this.databaseName = name;
        this.updateScript = name + "_update_%d.sql";
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public ExternalSQLiteOpenHelper(Context context, String name, String sourceDirectory, SQLiteDatabase.CursorFactory factory, int version, DatabaseErrorHandler errorHandler) {
        this.sqLiteOpenHelper = new InternalOpenHelper(context, name, factory, version, errorHandler);
        this.sourceDirectory = sourceDirectory;
        this.context = context;
        this.databaseName = name;
        this.updateScript = name + "_update_%d.sql";
    }

    public final String getDatabaseName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            return this.sqLiteOpenHelper.getDatabaseName();
        } else {
            return this.databaseName;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public final synchronized void setWriteAheadLoggingEnabled(boolean enabled) {
        this.sqLiteOpenHelper.setWriteAheadLoggingEnabled(enabled);
    }

    public final synchronized SQLiteDatabase getWritableDatabase() {
        deployExternalDatabase();
        return this.sqLiteOpenHelper.getWritableDatabase();
    }

    public final synchronized SQLiteDatabase getReadableDatabase() {
        deployExternalDatabase();
        return this.sqLiteOpenHelper.getReadableDatabase();
    }

    public final synchronized void close() {
        this.sqLiteOpenHelper.close();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public final void onConfigure(SQLiteDatabase db) {
        this.sqLiteOpenHelper.onConfigure(db);
    }

    public abstract void onUpgradeInternally(SQLiteDatabase database, int oldVersion, int newVersion);

    public void onDowngradeInternally(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Do nothing
    }

    public abstract void onUpgradeExternally(SQLiteDatabase currentDatabase, SQLiteDatabase readOnlyExternalDatabase, String externalDBPath, int currentVersion, int newVersion);

    public void onDowngradeExternally(SQLiteDatabase currentDatabase, SQLiteDatabase readOnlyExternalDatabase, String externalDBPath, int currentVersion, int newVersion) {
        // Do nothing
    }

    private synchronized void deployExternalDatabase() throws ExternalSQLiteOpenHelperException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && PermissionChecker.checkSelfPermission(context, READ_EXTERNAL_STORAGE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new ExternalSQLiteOpenHelperException(READ_EXTERNAL_STORAGE_PERMISSION + " permission is not granted");
        }
        File destination = new File(context.getFilesDir().getAbsolutePath().replace("files", "databases") + File.separator + this.databaseName);
        File source = new File(this.sourceDirectory, this.databaseName);
        if (!destination.exists()) {
            this.sqLiteOpenHelper.getReadableDatabase().close();
            copyFile(source, destination);
        } else {
            // Update the database

            // Read the version
            SQLiteDatabase sqLiteDatabase = this.sqLiteOpenHelper.getReadableDatabase();
            int currentVersion = sqLiteDatabase.getVersion();
            sqLiteDatabase.close();

            int externalVersion = getExternallyDefinedVersion(currentVersion);
            if (currentVersion != externalVersion) {
                // Upgrade due to external change
                File databaseSource = new File(sourceDirectory, databaseName);
                String updateScript = getUpdateScript(externalVersion);

                if (updateScript != null && !databaseSource.exists()) {
                    throw new ExternalSQLiteOpenHelperException("External database nor update script is not available");
                }

                // Execute sql script
                if (updateScript != null) {
                    sqLiteDatabase = this.sqLiteOpenHelper.getWritableDatabase();
                    sqLiteDatabase.beginTransaction();
                    try {
                        sqLiteDatabase.execSQL(updateScript);
                        sqLiteDatabase.setTransactionSuccessful();
                    } finally {
                        sqLiteDatabase.endTransaction();
                        sqLiteDatabase.close();
                    }
                }

                // Call onUpgradeExternally or onDowngradeExternally
                if (databaseSource.exists()) {
                    // Close the writable database and open readable database
                    sqLiteDatabase = this.sqLiteOpenHelper.getReadableDatabase();
                    SQLiteDatabase externalDatabase = SQLiteDatabase.openDatabase(databaseSource.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                    if (externalDatabase == null) {
                        throw new ExternalSQLiteOpenHelperException("Source database is not valid or corrupted");
                    }
                    try {
                        if (currentVersion < externalVersion) {
                            onUpgradeExternally(sqLiteDatabase, externalDatabase, databaseSource.getAbsolutePath(), currentVersion, externalVersion);
                        } else {
                            onDowngradeExternally(sqLiteDatabase, externalDatabase, databaseSource.getAbsolutePath(), currentVersion, externalVersion);
                        }
                    } finally {
                        externalDatabase.close();
                    }
                    sqLiteDatabase.close();
                }

                // Update the version
                sqLiteDatabase = this.sqLiteOpenHelper.getWritableDatabase();
                sqLiteDatabase.beginTransaction();
                sqLiteDatabase.setVersion(externalVersion);
                sqLiteDatabase.setTransactionSuccessful();
                sqLiteDatabase.endTransaction();
                sqLiteDatabase.close();
            }
        }
    }

    private synchronized String getUpdateScript(int forVersion) {
        String script = null;
        File scriptFile = new File(this.sourceDirectory, String.format(updateScript, forVersion));
        Reader fileReader = null;
        BufferedReader bufferedReader = null;
        try {
            if (scriptFile.exists()) {
                fileReader = new FileReader(scriptFile);
                bufferedReader = new BufferedReader(fileReader);
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    builder.append(line);
                }
                if (builder.length() > 0) {
                    script = builder.toString();
                }
            }
        } catch (FileNotFoundException e) {
            Log.i(TAG, "Failed to open script file: " + updateScript);
        } catch (IOException e) {
            Log.i(TAG, "Failed to read script file: " + updateScript);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close script file: " + updateScript);
                }
            } else if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close script file: " + updateScript);
                }
            }
        }

        return script;
    }

    private synchronized int getExternallyDefinedVersion(int defaultValue) {
        int externalVersion = defaultValue;
        Scanner scanner = null;
        try {
            File versionFile = new File(this.sourceDirectory + File.separator + VERSION_INFO);
            System.out.println(versionFile.getAbsolutePath());
            scanner = new Scanner(versionFile);
            if (scanner.hasNextInt()) {
                externalVersion = scanner.nextInt();
                if (externalVersion < 1) {
                    throw new IllegalArgumentException("Version must be >= 1, was " + externalVersion);
                }
            } else {
                throw new ExternalSQLiteOpenHelperException(VERSION_INFO + " does not contain a valid integer version number");
            }
        } catch (FileNotFoundException e) {
            // throw new ExternalSQLiteOpenHelperException(VERSION_INFO + " file does not exist in source directory");
            Log.i(TAG, VERSION_INFO + " file does not exist in source directory. Continue with internal version " + defaultValue);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }

        return externalVersion;
    }

    private synchronized void copyFile(File from, File to) {
        InputStream inputStream = null;
        if (from.exists()) {
            try {
                inputStream = new FileInputStream(from);
            } catch (FileNotFoundException e) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e1) {
                        Log.e(TAG, "Error in closing external database source");
                    }
                }
                throw new ExternalSQLiteOpenHelperException("Failed to open database from external source");
            }
        } else {
            try {
                inputStream = context.getAssets().open(DATABASES + File.separator + this.databaseName);
            } catch (IOException e) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e1) {
                        Log.e(TAG, "Error in closing external database from assets source");
                    }
                }
                throw new ExternalSQLiteOpenHelperException("External database does not exist. Failed to open database from assets/databases folder");
            }
        }
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(to);
        } catch (FileNotFoundException e) {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e1) {
                    Log.e(TAG, "Error in closing database destination");
                }
            }
            throw new ExternalSQLiteOpenHelperException("Failed to open database destination");
        }

        try {
            // Copy
            byte[] buffer = new byte[1024];
            int length;

            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        } catch (IOException e) {
            throw new ExternalSQLiteOpenHelperException("Failed to copy external database to the destination");
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close the external database");
            }
            try {
                inputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close the destination");
            }
        }
    }


    /**
     * Internal helper class to deal with underlying database.
     */
    private class InternalOpenHelper extends SQLiteOpenHelper {

        public InternalOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
            super(context, name, factory, version);
        }

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        public InternalOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version, DatabaseErrorHandler errorHandler) {
            super(context, name, factory, version, errorHandler);
        }

        @Override
        public void onCreate(SQLiteDatabase sqLiteDatabase) {
            // Do nothing
        }

        @Override
        public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
            ExternalSQLiteOpenHelper.this.onUpgradeInternally(sqLiteDatabase, oldVersion, newVersion);
        }

        @Override
        public void onDowngrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
            ExternalSQLiteOpenHelper.this.onDowngradeInternally(sqLiteDatabase, oldVersion, newVersion);
        }
    }
}
