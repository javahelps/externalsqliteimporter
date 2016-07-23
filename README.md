# External SQLite Importer

External SQLite Importer provides a helper class for Android devcelopers to import SQLite database from SD card or internal storage. It also provides an easy way to upgrade the database using a new solid database or sql script.

Currently this library is under development and does not support encryption for external files like database and sql script so that it is not recommended to use this library with sensitive applications where security is a concern. However, in future releases, encryption will be supported by this library so that you can import databases from SD card securely.

Currently implemented features:
  - Import database from external storage or asstes directory
  - External versioning support
  - SQL script can be used to upgrade the database
  - A new database with any kind of changes can be imported as an upgrade with no pain

### Setup

Add the following dependency in your build.gradle app module:
```gradle
dependencies {
    compile 'com.javahelps:externalsqliteimporter:+'
}
```

For sample application and tutorial, visit to [Java Helps](http://www.javahelps.com/2016/07/deploy-and-upgrade-android-database.html#more)

### Relase Note v0.3
Current version 0.3 allows you to import external SQLite database from either asstes directory or SD card.
Database deployed from assets directory can be upgraded using SQL script
Provides three different upgrade mechanism to deploy database from SD card.
 - Upgrade using SQL script
 - Manually upgrade the current database using records from an external database
 - Manually upgrade the current database by attaching an external database
Bug fixes