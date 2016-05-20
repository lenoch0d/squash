package org.jetbrains.squash.drivers

import org.jetbrains.squash.dialect.*
import org.jetbrains.squash.schema.*
import java.sql.*

open class JDBCDatabaseSchema(dialect: SQLDialect, val jdbcConnection: Connection) : DatabaseSchemaBase(dialect) {
    protected val catalogue: String = jdbcConnection.catalog
    protected val metadata: DatabaseMetaData = jdbcConnection.metaData

    override fun tables(): Sequence<DatabaseSchema.SchemaTable> {
        val resultSet = metadata.getTables(catalogue, currentSchema(), null, arrayOf("TABLE"))
        return JDBCResponse(resultSet).rows.map { SchemaTable(it.get<String>("TABLE_NAME"), this) }
    }

    protected open fun currentSchema(): String = jdbcConnection.schema

    class SchemaColumn(override val name: String,
                       override val nullable: Boolean,
                       override val autoIncrement: Boolean,
                       override val size: Int) : DatabaseSchema.SchemaColumn {
        override fun toString(): String = "[JDBC] Column: $name"
    }

    class SchemaTable(override val name: String, private val schema: JDBCDatabaseSchema) : DatabaseSchema.SchemaTable {
        override fun columns(): Sequence<DatabaseSchema.SchemaColumn> {
            val resultSet = schema.metadata.getColumns(schema.catalogue, schema.currentSchema(), name, null)
            val response = JDBCResponse(resultSet)
            return response.rows.map {
                val columnSize = it.get<Int>("COLUMN_SIZE")
                val autoIncrement = it.get<String>("IS_AUTOINCREMENT") == "YES"
                val columnName = it.get<String>("COLUMN_NAME")
                val nullable = it.get<Int>("NULLABLE") == DatabaseMetaData.columnNullable
                SchemaColumn(columnName, nullable, autoIncrement, columnSize)
            }
        }

        override fun toString(): String = "[JDBC] Table: $name"
    }
}