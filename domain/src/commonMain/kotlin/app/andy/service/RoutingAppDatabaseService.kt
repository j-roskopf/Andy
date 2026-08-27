package app.andy.service

/**
 * Routes [AppDatabaseService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget]. iOS SQLite files sit directly in the simulator's app
 * container, so the iOS backend queries them with the host `sqlite3` — no pull/push/run-as.
 */
class RoutingAppDatabaseService(
    private val android: AppDatabaseService,
    private val ios: AppDatabaseService,
) : AppDatabaseService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override suspend fun listDatabases(serial: String, packageName: String) =
        of(serial).listDatabases(serial, packageName)
    override suspend fun listTables(serial: String, packageName: String, dbName: String) =
        of(serial).listTables(serial, packageName, dbName)
    override suspend fun tableRowCounts(serial: String, packageName: String, dbName: String, tables: List<String>) =
        of(serial).tableRowCounts(serial, packageName, dbName, tables)
    override suspend fun tableInfo(serial: String, packageName: String, dbName: String, tableName: String) =
        of(serial).tableInfo(serial, packageName, dbName, tableName)
    override suspend fun browseTable(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        limit: Int,
        offset: Int,
    ) = of(serial).browseTable(serial, packageName, dbName, tableName, limit, offset)
    override suspend fun query(serial: String, packageName: String, dbName: String, sql: String, limit: Int) =
        of(serial).query(serial, packageName, dbName, sql, limit)
    override suspend fun updateCell(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        column: String,
        newValue: String?,
        rowId: Long?,
        primaryKeyColumn: String?,
        primaryKeyValue: String?,
    ) = of(serial).updateCell(serial, packageName, dbName, tableName, column, newValue, rowId, primaryKeyColumn, primaryKeyValue)
    override suspend fun pullToHost(serial: String, packageName: String, dbName: String, localPath: String) =
        of(serial).pullToHost(serial, packageName, dbName, localPath)
    /**
     * Saved queries are keyed by package name only (not serial), so either backend can serve
     * them — route to Android for a stable, single source of truth.
     */
    override suspend fun listSavedQueries(packageName: String) = android.listSavedQueries(packageName)
    override suspend fun saveQuery(packageName: String, name: String, sql: String) =
        android.saveQuery(packageName, name, sql)
    override suspend fun deleteQuery(packageName: String, id: String) = android.deleteQuery(packageName, id)
}
