package org.jetbrains.squash.dialect

import org.jetbrains.squash.*
import org.jetbrains.squash.definition.*
import org.jetbrains.squash.expressions.*
import org.jetbrains.squash.statements.*

interface SQLDialect {
    fun tableDefinitionSQL(table: Table): String

    fun <T> statementSQL(statement: Statement<T>): StatementSQL

    fun querySQL(query: Query): StatementSQL

    fun <T> expressionSQL(expression: Expression<T>): String
    fun nameSQL(name: Name): String
}

data class StatementSQL(val sql: String, val indexes: Map<Column<*>, Int>)

open class BaseSQLDialect(val name: String) : SQLDialect {

    fun <T> declarationExpressionSQL(expression: Expression<T>): String = when (expression) {
        is AliasExpression<T> -> expressionSQL(expression.expression) + " AS " + nameSQL(expression.name)
        else -> expressionSQL(expression)
    }

    override fun <T> expressionSQL(expression: Expression<T>): String = when (expression) {
        is LiteralExpression -> literalSQL(expression.literal)
        is NamedExpression<*, T> -> nameSQL(expression.name)
        is BinaryExpression<*, *, *> -> "${expressionSQL(expression.left)} ${binaryExpressionSQL(expression)} ${expressionSQL(expression.right)}"
        is NotExpression -> "NOT ${expressionSQL(expression.operand)}"
        is SubQueryExpression<*> -> "(${querySQL(expression.query).sql})"
        else -> error("Expression '$expression' is not supported by $this")
    }

    private fun binaryExpressionSQL(expression: BinaryExpression<*, *, *>): String = when (expression) {
        is EqExpression<*> -> "="
        is NotEqExpression<*> -> "<>"
        is LessExpression<*> -> "<"
        is GreaterExpression<*> -> ">"
        is LessEqExpression<*> -> "<="
        is GreaterEqExpression<*> -> ">="
        is AndExpression -> "AND"
        is OrExpression -> "OR"
        is PlusExpression -> "+"
        is MinusExpression -> "-"
        is MultiplyExpression -> "*"
        is DivideExpression -> "/"
        else -> error("Expression '$expression' is not supported by $this")
    }

    override fun nameSQL(name: Name): String = when (name) {
        is QualifiedIdentifier<*> -> "${nameSQL(name.parent)}.${nameSQL(name.identifier)}"
        is Identifier -> name.id
        else -> error("Name '$name' is not supported by $this")
    }

    override fun querySQL(query: Query): StatementSQL {
        val sql = buildString {
            append("SELECT ")
            if (query.selection.isEmpty())
                append("*")
            else
                query.selection.joinTo(this) { declarationExpressionSQL(it) }

            if (query.schema.isNotEmpty()) {
                val tables = query.schema.filterIsInstance<QuerySchema.From>()
                append(" FROM ")
                tables.joinTo(this) { fieldCollectionSQL(it.target) }

                val innerJoins = query.schema.filterIsInstance<QuerySchema.InnerJoin>()
                if (innerJoins.any()) {
                    innerJoins.forEach { join ->
                        append(" INNER JOIN ")
                        append(fieldCollectionSQL(join.target))
                        append(" ON ")
                        append(expressionSQL(join.condition))
                    }
                }
            }

            if (query.filter.isNotEmpty()) {
                append(" WHERE ")
                query.filter.joinTo(this, separator = " AND ") { expressionSQL(it) }
            }

        }
        return StatementSQL(sql, emptyMap())
    }

    private fun fieldCollectionSQL(target: ColumnOwner): String = when (target) {
        is Table -> nameSQL(target.tableName)
        else -> error("FieldCollection '$target' is not supported by $this")
    }


    override fun <T> statementSQL(statement: Statement<T>): StatementSQL = when (statement) {
        is InsertValuesStatement<*, *> -> insertStatementSQL(statement)
        else -> error("Statement '$statement' is not supported by $this")
    }

    private fun insertStatementSQL(statement: InsertValuesStatement<*, *>): StatementSQL {
        val arguments = mutableMapOf<Column<*>, Int>()
        val names = mutableListOf<Name>()
        val values = mutableListOf<Any?>()
        var index = 0
        for ((column, value) in statement.values) {
            names.add(column.name)
            values.add("?")
            arguments[column] = index++
        }
        val sql = "INSERT INTO ${nameSQL(statement.table.tableName)} (${names.map { it.id }.joinToString()}) VALUES (${values.joinToString()})"
        return StatementSQL(sql, arguments)
    }

    override fun tableDefinitionSQL(table: Table): String = buildString {
        append("CREATE TABLE IF NOT EXISTS ${nameSQL(table.tableName)}")
        if (table.tableColumns.any()) {
            append(" (")
            append(table.tableColumns.map { columnDefinitionSQL(it) }.joinToString())
            val primaryKeys = table.tableColumns.filterIsInstance<PrimaryKeyColumn<*>>()

            if (primaryKeys.any()) {
                append(", ")
                primaryKeyDefinitionSQL(primaryKeys, table)
            } else {
                val autoIncrement = table.tableColumns.filterIsInstance<AutoIncrementColumn<*>>()
                if (autoIncrement.any()) {
                    append(", ")
                    primaryKeyDefinitionSQL(autoIncrement, table)
                }
            }
            append(")")
        }
    }

    private fun StringBuilder.primaryKeyDefinitionSQL(primaryKeys: List<Column<*>>, table: Table) {
        append("CONSTRAINT pk_${nameSQL(table.tableName)} PRIMARY KEY (")
        append(primaryKeys.map { it.name.id }.joinToString())
        append(")")
    }

    protected open fun columnDefinitionSQL(column: Column<*>): String = buildString {
        append(column.name.id)
        append(" ")
        append(columnTypeSQL(column, emptySet()))
    }

    enum class ColumnProperty {
        NULLABLE, AUTOINCREMENT, DEFAULT
    }

    protected open fun literalSQL(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'$value'"
        else -> value.toString()
    }

    protected open fun columnTypeSQL(column: Column<*>, properties: Set<ColumnProperty>): String = when (column) {
        is DataColumn -> {
            if (ColumnProperty.NULLABLE in properties)
                "${columnTypeSQL(column.type)} NULL"
            else
                "${columnTypeSQL(column.type)} NOT NULL"
        }

        is NullableColumn -> {
            require(ColumnProperty.AUTOINCREMENT !in properties) { "Column ${column.name} cannot be both AUTOINCREMENT and NULL" }
            columnTypeSQL(column.column, properties + ColumnProperty.NULLABLE)
        }

        is AutoIncrementColumn -> {
            require(ColumnProperty.NULLABLE !in properties) { "Column ${column.name} cannot be both AUTOINCREMENT and NULL" }
            "${columnTypeSQL(column.column, properties + ColumnProperty.AUTOINCREMENT)} AUTO_INCREMENT"
        }

        is PrimaryKeyColumn -> columnTypeSQL(column.column, properties)
        is DefaultValueColumn<*> -> "${columnTypeSQL(column.column, properties + ColumnProperty.DEFAULT)} DEFAULT ${literalSQL(column.value)}"

        else -> error("Column class '${column.javaClass.simpleName}' is not supported by $this")
    }

    protected open fun columnTypeSQL(type: ColumnType): String = when (type) {
        is ReferenceColumnType<*> -> columnTypeSQL(type.column.type)
        is CharColumnType -> "CHAR"
        is LongColumnType -> "BIGINT"
        is IntColumnType -> "INT"
        is DecimalColumnType -> "DECIMAL(${type.scale}, ${type.precision})"
        is EnumColumnType<*> -> "INT"
        is DateColumnType -> "DATE"
        is DateTimeColumnType -> "DATETIME"
        is BinaryColumnType -> "VARBINARY(${type.length})"
        is UUIDColumnType -> "BINARY(16)"
        is StringColumnType -> {
            val sqlType = when (type.length) {
                in 1..255 -> "VARCHAR(${type.length})"
                else -> "TEXT"
            }
            if (type.collate == null)
                sqlType
            else
                sqlType + " COLLATE ${type.collate}"
        }
        else -> error("Column type '$type' is not supported by $this")
    }

    override fun toString(): String = "SQLDialect '$name'"
}
