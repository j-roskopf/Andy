package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbCellUpdateTest {
    @Test
    fun prefersRowId() {
        val sql = DbCellUpdate.buildUpdateSql(
            tableName = "users",
            column = "name",
            newValue = "Ada",
            rowId = 7,
            primaryKeyColumn = "id",
            primaryKeyValue = "1",
        )
        assertEquals("""UPDATE "users" SET "name" = 'Ada' WHERE rowid = 7;""", sql)
    }

    @Test
    fun fallsBackToPrimaryKey() {
        val sql = DbCellUpdate.buildUpdateSql(
            tableName = "users",
            column = "name",
            newValue = "Ada",
            rowId = null,
            primaryKeyColumn = "id",
            primaryKeyValue = "1",
        )
        assertEquals("""UPDATE "users" SET "name" = 'Ada' WHERE "id" = '1';""", sql)
    }

    @Test
    fun refusesWithoutIdentity() {
        assertNull(
            DbCellUpdate.buildUpdateSql(
                tableName = "users",
                column = "name",
                newValue = "Ada",
                rowId = null,
                primaryKeyColumn = null,
                primaryKeyValue = null,
            ),
        )
    }

    @Test
    fun escapesQuotes() {
        val sql = DbCellUpdate.buildUpdateSql(
            tableName = "t",
            column = "c",
            newValue = "O'Brien",
            rowId = 1,
            primaryKeyColumn = null,
            primaryKeyValue = null,
        )
        assertEquals("""UPDATE "t" SET "c" = 'O''Brien' WHERE rowid = 1;""", sql)
    }

    @Test
    fun nullLiteralWritesSqlNull() {
        val sql = DbCellUpdate.buildUpdateSql(
            tableName = "t",
            column = "c",
            newValue = null,
            rowId = 1,
            primaryKeyColumn = null,
            primaryKeyValue = null,
        )
        assertEquals("""UPDATE "t" SET "c" = NULL WHERE rowid = 1;""", sql)
    }
}
