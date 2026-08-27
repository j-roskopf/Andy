package app.andy.model

/**
 * Builds SQLite UPDATE statements for inline cell edits.
 * Prefers rowid when available; otherwise a single-column primary key.
 */
object DbCellUpdate {
    fun buildUpdateSql(
        tableName: String,
        column: String,
        newValue: String?,
        rowId: Long?,
        primaryKeyColumn: String?,
        primaryKeyValue: String?,
    ): String? {
        val quotedTable = quoteIdent(tableName)
        val quotedColumn = quoteIdent(column)
        val setClause = "$quotedColumn = ${sqlLiteral(newValue)}"
        return when {
            rowId != null -> "UPDATE $quotedTable SET $setClause WHERE rowid = $rowId;"
            !primaryKeyColumn.isNullOrBlank() && primaryKeyValue != null ->
                "UPDATE $quotedTable SET $setClause WHERE ${quoteIdent(primaryKeyColumn)} = ${sqlLiteral(primaryKeyValue)};"
            else -> null
        }
    }

    fun quoteIdent(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    fun sqlLiteral(value: String?): String =
        if (value == null) "NULL" else "'${value.replace("'", "''")}'"
}
