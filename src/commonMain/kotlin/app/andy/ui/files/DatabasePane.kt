package app.andy.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AppDatabaseInfo
import app.andy.model.DbQueryResult
import app.andy.model.DbTableInfo
import app.andy.model.SavedSqlQuery
import app.andy.pickSavePath
import app.andy.service.AppDatabaseService
import app.andy.service.AppService
import app.andy.ui.components.Button
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PackageSelector
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun DatabasePane(
    service: AppDatabaseService,
    apps: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var databases by remember { mutableStateOf<List<AppDatabaseInfo>>(emptyList()) }
    var selectedDb by remember { mutableStateOf<String?>(null) }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var tableRowCounts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var tableInfo by remember { mutableStateOf<DbTableInfo?>(null) }
    var browseResult by remember { mutableStateOf<DbQueryResult?>(null) }
    var sqlText by remember { mutableStateOf("SELECT * FROM ") }
    var resultsAreFromSql by remember { mutableStateOf(false) }
    var savedQueries by remember { mutableStateOf<List<SavedSqlQuery>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tableLoading by remember { mutableStateOf(false) }
    var leftPaneWidth by remember { mutableStateOf(280f) }
    var sqlPaneHeight by remember { mutableStateOf(160f) }
    var editingCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var browseGeneration by remember { mutableStateOf(0) }

    fun refreshDatabases() {
        if (serial == null || selectedPackage.isNullOrBlank()) {
            databases = emptyList()
            selectedDb = null
            return
        }
        scope.launch {
            busy = true
            service.listDatabases(serial, selectedPackage)
                .onSuccess {
                    databases = it
                    if (selectedDb !in it.map { db -> db.path }) selectedDb = it.firstOrNull()?.path
                    message = when {
                        it.isEmpty() -> "No databases — launch the app on the device if you expect data"
                        it.any { db -> db.hasWal } -> "${it.size} databases · WAL present (best-effort edits)"
                        else -> "${it.size} databases"
                    }
                }
                .onFailure {
                    databases = emptyList()
                    selectedDb = null
                    message = it.message ?: "Failed to list databases"
                }
            busy = false
        }
    }

    fun refreshTables(dbName: String?) {
        if (serial == null || selectedPackage.isNullOrBlank() || dbName.isNullOrBlank()) {
            tables = emptyList()
            tableRowCounts = emptyMap()
            selectedTable = null
            return
        }
        scope.launch {
            busy = true
            service.tableRowCounts(serial, selectedPackage, dbName, emptyList())
                .onSuccess { counts ->
                    val names = counts.keys.sorted()
                    tables = names
                    tableRowCounts = counts
                    if (selectedTable !in names) {
                        selectedTable = counts.entries.firstOrNull { it.value > 0 }?.key
                            ?: names.firstOrNull { it != "android_metadata" }
                            ?: names.firstOrNull()
                    }
                }
                .onFailure {
                    tables = emptyList()
                    tableRowCounts = emptyMap()
                    selectedTable = null
                    message = it.message ?: "Failed to list tables"
                }
            busy = false
        }
    }

    fun refreshBrowse(dbName: String?, tableName: String?) {
        if (serial == null || selectedPackage.isNullOrBlank() || dbName.isNullOrBlank() || tableName.isNullOrBlank()) {
            browseGeneration += 1
            browseResult = null
            tableInfo = null
            tableLoading = false
            return
        }
        val generation = browseGeneration + 1
        browseGeneration = generation
        scope.launch {
            tableLoading = true
            browseResult = null
            tableInfo = null
            editingCell = null
            resultsAreFromSql = false
            service.tableInfo(serial, selectedPackage, dbName, tableName)
                .onSuccess { if (browseGeneration == generation) tableInfo = it }
                .onFailure {
                    if (browseGeneration == generation) {
                        message = it.message ?: "Failed to load table info"
                    }
                }
            service.browseTable(serial, selectedPackage, dbName, tableName)
                .onSuccess { if (browseGeneration == generation) browseResult = it }
                .onFailure {
                    if (browseGeneration == generation) {
                        browseResult = null
                        message = it.message ?: "Failed to browse table"
                    }
                }
            if (browseGeneration == generation) tableLoading = false
        }
    }

    fun refreshSaved() {
        if (selectedPackage.isNullOrBlank()) {
            savedQueries = emptyList()
            return
        }
        scope.launch { savedQueries = service.listSavedQueries(selectedPackage) }
    }

    LaunchedEffect(serial, selectedPackage) {
        refreshDatabases()
        refreshSaved()
    }
    LaunchedEffect(selectedDb, serial, selectedPackage) {
        refreshTables(selectedDb)
    }
    LaunchedEffect(selectedTable, selectedDb, serial, selectedPackage) {
        refreshBrowse(selectedDb, selectedTable)
        if (!selectedTable.isNullOrBlank()) {
            sqlText = "SELECT * FROM \"$selectedTable\" LIMIT 200"
        }
    }

    if (serial == null) {
        InspectorEmptyState("Select an online device to inspect databases.")
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PackageSelector(
                appsService = apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
                allowAll = false,
                placeholder = "Select app…",
                buttonPrefix = "App: ",
                modifier = Modifier.width(320.dp),
            )
            OutlinedButton(onClick = {
                refreshDatabases()
                refreshTables(selectedDb)
                refreshBrowse(selectedDb, selectedTable)
                refreshSaved()
            }, enabled = !busy && !selectedPackage.isNullOrBlank()) { Text("Refresh") }
            Button(
                onClick = {
                    val db = selectedDb ?: return@Button
                    scope.launch {
                        val path = pickSavePath(db.substringAfterLast('/')) ?: return@launch
                        busy = true
                        val result = service.pullToHost(serial, selectedPackage!!, db, path)
                        message = if (result.isSuccess) result.stdout.ifBlank { "Saved $path" } else result.stderr.ifBlank { "Pull failed" }
                        busy = false
                    }
                },
                enabled = !busy && selectedDb != null && !selectedPackage.isNullOrBlank(),
            ) { Text("Pull DB…") }
            if (message.isNotBlank()) {
                Text(message, color = if (message.contains("fail", ignoreCase = true)) Rust else TextSecondary, fontSize = 12.sp)
            }
        }
        if (selectedPackage.isNullOrBlank()) {
            InspectorEmptyState(
                "Search and select a debuggable app to inspect its databases.",
            )
        } else {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(leftPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Databases", color = TextSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.height(160.dp)) {
                    items(databases, key = { it.path }) { db ->
                        Text(
                            buildString {
                                append(db.name)
                                if (db.hasWal) append(" · wal")
                            },
                            color = if (db.path == selectedDb) Cyan else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (db.path == selectedDb) PanelSoft else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedDb = db.path }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
                Text("Tables", color = TextSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f)) {
                    items(tables, key = { it }) { table ->
                        val count = tableRowCounts[table]
                        Text(
                            buildString {
                                append(table)
                                if (count != null) append(" · $count")
                            },
                            color = if (table == selectedTable) Cyan else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (table == selectedTable) PanelSoft else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedTable = table }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
                Text("Saved queries", color = TextSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.height(120.dp)) {
                    items(savedQueries) { query ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                query.name,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f).clickable { sqlText = query.sql },
                            )
                            OutlinedButton(onClick = {
                                scope.launch {
                                    service.deleteQuery(selectedPackage, query.id)
                                    refreshSaved()
                                }
                            }) { Text("Del") }
                        }
                    }
                }
            }
            PaneDivider(onDrag = { dragX -> leftPaneWidth = (leftPaneWidth + dragX).coerceIn(200f, 520f) })
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (resultsAreFromSql) "Query results" else "Table data",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    QueryResultGrid(
                        result = browseResult,
                        tableInfo = tableInfo,
                        loading = tableLoading,
                        editingCell = editingCell,
                        editingValue = editingValue,
                        onEditingValueChange = { editingValue = it },
                        onStartEdit = { rowIndex, column, value ->
                            editingCell = rowIndex to column
                            editingValue = value.orEmpty()
                        },
                        onCancelEdit = { editingCell = null },
                        onCommitEdit = { rowIndex, column ->
                            val db = selectedDb ?: return@QueryResultGrid
                            val table = selectedTable ?: return@QueryResultGrid
                            val info = tableInfo ?: return@QueryResultGrid
                            val row = browseResult?.rows?.getOrNull(rowIndex) ?: return@QueryResultGrid
                            val columns = browseResult?.columns.orEmpty()
                            val rowIdIndex = columns.indexOf("__rowid__")
                            val rowId = rowIdIndex.takeIf { it >= 0 }?.let { row.getOrNull(it)?.toLongOrNull() }
                            val pk = info.columns.singleOrNull { it.primaryKey }
                            val pkIndex = pk?.let { columns.indexOf(it.name) }?.takeIf { it >= 0 }
                            scope.launch {
                                busy = true
                                val result = service.updateCell(
                                    serial = serial,
                                    packageName = selectedPackage,
                                    dbName = db,
                                    tableName = table,
                                    column = column,
                                    newValue = editingValue,
                                    rowId = rowId,
                                    primaryKeyColumn = pk?.name,
                                    primaryKeyValue = pkIndex?.let { row.getOrNull(it) },
                                )
                                message = if (result.isSuccess) "Updated $column" else result.stderr.ifBlank { "Update failed" }
                                editingCell = null
                                if (result.isSuccess) refreshBrowse(db, table)
                                busy = false
                            }
                        },
                        editable = !resultsAreFromSql,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                HorizontalPaneDivider(
                    onDrag = { dragY -> sqlPaneHeight = (sqlPaneHeight - dragY).coerceIn(120f, 360f) },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .height(sqlPaneHeight.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("SQL", color = TextSecondary, fontSize = 12.sp)
                    TextField(
                        sqlText,
                        { sqlText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).weight(1f),
                        colors = fieldColors(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val db = selectedDb ?: return@Button
                                scope.launch {
                                    busy = true
                                    tableLoading = true
                                    editingCell = null
                                    service.query(serial, selectedPackage, db, sqlText)
                                        .onSuccess {
                                            message = it.message ?: "${it.rows.size} rows"
                                            if (it.rowsAffected != null) {
                                                resultsAreFromSql = false
                                                tableLoading = false
                                                refreshBrowse(db, selectedTable)
                                                refreshTables(db)
                                            } else {
                                                browseResult = it
                                                tableInfo = null
                                                resultsAreFromSql = true
                                                tableLoading = false
                                            }
                                        }
                                        .onFailure {
                                            message = it.message ?: "Query failed"
                                            tableLoading = false
                                        }
                                    busy = false
                                }
                            },
                            enabled = !busy && selectedDb != null,
                        ) { Text("Run") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val name = sqlText.lineSequence().firstOrNull()?.take(40) ?: "Query"
                                    service.saveQuery(selectedPackage, name, sqlText)
                                    refreshSaved()
                                    message = "Saved query"
                                }
                            },
                            enabled = sqlText.isNotBlank(),
                        ) { Text("Save query") }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun QueryResultGrid(
    result: DbQueryResult?,
    tableInfo: DbTableInfo?,
    loading: Boolean,
    editingCell: Pair<Int, String>?,
    editingValue: String,
    onEditingValueChange: (String) -> Unit,
    onStartEdit: (rowIndex: Int, column: String, value: String?) -> Unit,
    onCancelEdit: () -> Unit,
    onCommitEdit: (rowIndex: Int, column: String) -> Unit,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    if (loading) {
        Row(
            modifier = modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Rust)
            Text("Loading table…", color = TextSecondary, fontSize = 13.sp)
        }
        return
    }
    if (result == null) {
        Text("No results", color = TextSecondary, modifier = modifier)
        return
    }
    val columns = result.columns
    val pkColumns = tableInfo?.columns?.filter { it.primaryKey }?.map { it.name }.orEmpty()
    val canEditRows = editable && tableInfo != null && (tableInfo.hasRowId || pkColumns.size == 1)
    Column(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(PanelSoft).padding(vertical = 4.dp)) {
            columns.filter { it != "__rowid__" }.forEach { column ->
                MonoCell(column, 140.dp, TextSecondary)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(result.rows) { rowIndex, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    columns.forEachIndexed { colIndex, column ->
                        if (column == "__rowid__") return@forEachIndexed
                        val value = row.getOrNull(colIndex)
                        val isEditing = editingCell?.first == rowIndex && editingCell.second == column
                        if (isEditing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TextField(
                                    editingValue,
                                    onEditingValueChange,
                                    singleLine = true,
                                    modifier = Modifier
                                        .width(180.dp)
                                        .height(44.dp)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (event.key) {
                                                Key.Enter, Key.NumPadEnter -> {
                                                    onCommitEdit(rowIndex, column)
                                                    true
                                                }
                                                Key.Escape -> {
                                                    onCancelEdit()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    colors = fieldColors(),
                                )
                                if (canEditRows) {
                                    OutlinedButton(onClick = { onCommitEdit(rowIndex, column) }) { Text("Save") }
                                    OutlinedButton(onClick = onCancelEdit) { Text("Cancel") }
                                }
                            }
                        } else {
                            val cellModifier = Modifier.width(140.dp).then(
                                if (canEditRows) Modifier.clickable { onStartEdit(rowIndex, column, value) } else Modifier,
                            )
                            Text(
                                value ?: "NULL",
                                color = if (value == null) TextSecondary else TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = cellModifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
