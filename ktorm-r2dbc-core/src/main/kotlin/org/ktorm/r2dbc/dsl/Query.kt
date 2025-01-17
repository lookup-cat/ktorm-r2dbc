/*
 * Copyright 2018-2021 the original author or authors.
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

package org.ktorm.r2dbc.dsl

import kotlinx.coroutines.flow.*
import org.ktorm.r2dbc.database.Database
import org.ktorm.r2dbc.expression.*
import org.ktorm.r2dbc.schema.BooleanSqlType
import org.ktorm.r2dbc.schema.Column
import org.ktorm.r2dbc.schema.ColumnDeclaring
import org.ktorm.r2dbc.schema.LongSqlType

/**
 * [Query] is an abstraction of query operations and the core class of Ktorm's query DSL.
 *
 * The constructor of this class accepts two parameters: [database] is the database instance that this query
 * is running on; [expression] is the abstract representation of the executing SQL statement. Usually, we don't
 * use the constructor to create [Query] objects but use the `database.from(..).select(..)` syntax instead.
 *
 * [Query] provides a built-in [iterator], so we can iterate the results by a for-each loop:
 *
 * ```kotlin
 * for (row in database.from(Employees).select()) {
 *     println(row[Employees.name])
 * }
 * ```
 *
 * Moreover, there are many extension functions that can help us easily process the query results, such as
 * [Query.map], [Query.flatMap], [Query.associate], [Query.fold], etc. With the help of these functions, we can
 * obtain rows from a query just like it's a common Kotlin collection.
 *
 * Query objects are immutable. Query DSL functions are provided as its extension functions normally. We can
 * chaining call these functions to modify them and create new query objects. Here is a simple example:
 *
 * ```kotlin
 * val query = database
 *     .from(Employees)
 *     .select(Employees.salary)
 *     .where { (Employees.departmentId eq 1) and (Employees.name like "%vince%") }
 * ```
 *
 * Easy to know that the query obtains the salary of an employee named vince in department 1. The generated
 * SQL is obviously:
 *
 * ```sql
 * select t_employee.salary as t_employee_salary
 * from t_employee
 * where (t_employee.department_id = ?) and (t_employee.name like ?)
 * ```
 *
 * More usages can be found in the documentations of those DSL functions.
 *
 * @property database the [Database] instance that this query is running on.
 * @property expression the underlying SQL expression of this query object.
 */
public class Query(public val database: Database, public val expression: QueryExpression) {

    /**
     * The executable SQL string of this query.
     *
     * Useful when we want to ensure if the generated SQL is expected while debugging.
     */
    public val sql: String by lazy(LazyThreadSafetyMode.NONE) {
        database.formatExpression(expression, beautifySql = true).first
    }

    /**
     * The [QueryRow] object flow of this query
     */
    public suspend fun doQuery(expression: QueryExpression = this.expression): Flow<QueryRow> {
        return database.executeQuery(expression).map { QueryRow(this@Query, it) }
    }

    /**
     * The [QueryRow] object flow of this query
     */
    public suspend fun asFlow(): Flow<QueryRow> {
        return this.doQuery()
    }

    /**
     * The total record count of this query ignoring the pagination params.
     *
     * If the query doesn't limits the results via [Query.limit] function, return the size of the result set. Or if
     * it does, return the total record count of the query ignoring the offset and limit parameters. This property
     * is provided to support pagination, we can calculate the page count through dividing it by our page size.
     */
    public suspend fun totalRecords(): Long {
        return if (expression.offset == null && expression.limit == null) {
            doQuery().count().toLong()
        } else {
            val countExpr = expression.toCountExpression()
            val count = doQuery(countExpr)
                .map { LongSqlType.getResult(it,  0) }
                .firstOrNull()
            val (sql, _) = database.formatExpression(countExpr, beautifySql = true)
            count ?: throw IllegalStateException("No result return for sql: $sql")
        }
    }


    /**
     * Return a copy of this [Query] with the [expression] modified.
     */
    public fun withExpression(expression: QueryExpression): Query {
        return Query(database, expression)
    }

}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource].
 *
 * Note that the specific columns can be empty, that means `select *` in SQL.
 *
 * @since 2.7
 */
public fun QuerySource.select(columns: Collection<ColumnDeclaring<*>>): Query {
    val declarations = columns.map { it.asDeclaringExpression() }
    return Query(database, SelectExpression(columns = declarations, from = expression))
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource].
 *
 * Note that the specific columns can be empty, that means `select *` in SQL.
 *
 * @since 2.7
 */
public fun QuerySource.select(vararg columns: ColumnDeclaring<*>): Query {
    return select(columns.asList())
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource] distinctly.
 *
 * Note that the specific columns can be empty, that means `select distinct *` in SQL.
 *
 * @since 2.7
 */
public fun QuerySource.selectDistinct(columns: Collection<ColumnDeclaring<*>>): Query {
    val declarations = columns.map { it.asDeclaringExpression() }
    return Query(database, SelectExpression(columns = declarations, from = expression, isDistinct = true))
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource] distinctly.
 *
 * Note that the specific columns can be empty, that means `select distinct *` in SQL.
 *
 * @since 2.7
 */
public fun QuerySource.selectDistinct(vararg columns: ColumnDeclaring<*>): Query {
    return selectDistinct(columns.asList())
}

/**
 * Wrap this expression as a [ColumnDeclaringExpression].
 */
internal fun <T : Any> ColumnDeclaring<T>.asDeclaringExpression(): ColumnDeclaringExpression<T> {
    return when (this) {
        is ColumnDeclaringExpression -> this
        is Column -> this.aliased(label)
        else -> this.aliased(null)
    }
}

/**
 * Specify the `where` clause of this query using the given condition expression.
 */
public fun Query.where(condition: ColumnDeclaring<Boolean>): Query {
    return this.withExpression(
        when (expression) {
            is SelectExpression -> expression.copy(where = condition.asExpression())
            is UnionExpression -> throw IllegalStateException("Where clause is not supported in a union expression.")
        }
    )
}

/**
 * Specify the `where` clause of this query using the expression returned by the given callback function.
 */
public inline fun Query.where(condition: () -> ColumnDeclaring<Boolean>): Query {
    return where(condition())
}

/**
 * Create a mutable list, then add filter conditions to the list in the given callback function, finally combine
 * them with the [and] operator and set the combined condition as the `where` clause of this query.
 *
 * Note that if we don't add any conditions to the list, the `where` clause would not be set.
 */
public inline fun Query.whereWithConditions(block: (MutableList<ColumnDeclaring<Boolean>>) -> Unit): Query {
    val conditions = ArrayList<ColumnDeclaring<Boolean>>().apply(block)

    if (conditions.isEmpty()) {
        return this
    } else {
        return this.where { conditions.reduce { a, b -> a and b } }
    }
}

/**
 * Create a mutable list, then add filter conditions to the list in the given callback function, finally combine
 * them with the [or] operator and set the combined condition as the `where` clause of this query.
 *
 * Note that if we don't add any conditions to the list, the `where` clause would not be set.
 */
public inline fun Query.whereWithOrConditions(block: (MutableList<ColumnDeclaring<Boolean>>) -> Unit): Query {
    val conditions = ArrayList<ColumnDeclaring<Boolean>>().apply(block)

    if (conditions.isEmpty()) {
        return this
    } else {
        return this.where { conditions.reduce { a, b -> a or b } }
    }
}

/**
 * Combine this iterable of boolean expressions with the [and] operator.
 *
 * If the iterable is empty, the param [ifEmpty] will be returned.
 */
public fun Iterable<ColumnDeclaring<Boolean>>.combineConditions(ifEmpty: Boolean = true): ColumnDeclaring<Boolean> {
    return this.reduceOrNull { a, b -> a and b } ?: ArgumentExpression(ifEmpty, BooleanSqlType)
}

/**
 * Specify the `group by` clause of this query using the given columns or expressions.
 */
public fun Query.groupBy(columns: Collection<ColumnDeclaring<*>>): Query {
    return this.withExpression(
        when (expression) {
            is SelectExpression -> expression.copy(groupBy = columns.map { it.asExpression() })
            is UnionExpression -> throw IllegalStateException("Group by clause is not supported in a union expression.")
        }
    )
}

/**
 * Specify the `group by` clause of this query using the given columns or expressions.
 */
public fun Query.groupBy(vararg columns: ColumnDeclaring<*>): Query {
    return groupBy(columns.asList())
}

/**
 * Specify the `having` clause of this query using the given condition expression.
 */
public fun Query.having(condition: ColumnDeclaring<Boolean>): Query {
    return this.withExpression(
        when (expression) {
            is SelectExpression -> expression.copy(having = condition.asExpression())
            is UnionExpression -> throw IllegalStateException("Having clause is not supported in a union expression.")
        }
    )
}

/**
 * Specify the `having` clause of this query using the expression returned by the given callback function.
 */
public inline fun Query.having(condition: () -> ColumnDeclaring<Boolean>): Query {
    return having(condition())
}

/**
 * Specify the `order by` clause of this query using the given order-by expressions.
 */
public fun Query.orderBy(orders: Collection<OrderByExpression>): Query {
    return this.withExpression(
        when (expression) {
            is SelectExpression -> expression.copy(orderBy = orders.toList())
            is UnionExpression -> {
                val replacer = OrderByReplacer(expression)
                expression.copy(orderBy = orders.map { replacer.visit(it) as OrderByExpression })
            }
        }
    )
}

/**
 * Specify the `order by` clause of this query using the given order-by expressions.
 */
public fun Query.orderBy(vararg orders: OrderByExpression): Query {
    return orderBy(orders.asList())
}

private class OrderByReplacer(query: UnionExpression) : SqlExpressionVisitor() {
    val declaringColumns = query.findDeclaringColumns()

    override fun visitOrderBy(expr: OrderByExpression): OrderByExpression {
        val declaring = declaringColumns.find { it.declaredName != null && it.expression == expr.expression }

        if (declaring == null) {
            throw IllegalArgumentException("Could not find the ordering column in the union expression, column: $expr")
        } else {
            return OrderByExpression(
                expression = ColumnExpression(
                    table = null,
                    name = declaring.declaredName!!,
                    sqlType = declaring.expression.sqlType
                ),
                orderType = expr.orderType
            )
        }
    }
}

internal tailrec fun QueryExpression.findDeclaringColumns(): List<ColumnDeclaringExpression<*>> {
    return when (this) {
        is SelectExpression -> columns
        is UnionExpression -> left.findDeclaringColumns()
    }
}

/**
 * Order this column or expression in ascending order.
 */
public fun ColumnDeclaring<*>.asc(): OrderByExpression {
    return OrderByExpression(asExpression(), OrderType.ASCENDING)
}

/**
 * Order this column or expression in descending order, corresponding to the `desc` keyword in SQL.
 */
public fun ColumnDeclaring<*>.desc(): OrderByExpression {
    return OrderByExpression(asExpression(), OrderType.DESCENDING)
}

/**
 * Specify the pagination offset parameter of this query.
 *
 * This function requires a dialect enabled, different SQLs will be generated with different dialects.
 *
 * Note that if the number isn't positive then it will be ignored.
 */
public fun Query.offset(n: Int): Query {
    return limit(offset = n, limit = null)
}

/**
 * Specify the pagination limit parameter of this query.
 *
 * This function requires a dialect enabled, different SQLs will be generated with different dialects.
 *
 * Note that if the number isn't positive then it will be ignored.
 */
public fun Query.limit(n: Int): Query {
    return limit(offset = null, limit = n)
}

/**
 * Specify the pagination parameters of this query.
 *
 * This function requires a dialect enabled, different SQLs will be generated with different dialects. For example,
 * `limit ?, ?` by MySQL, `limit m offset n` by PostgreSQL.
 *
 * Note that if the numbers aren't positive, they will be ignored.
 */
public fun Query.limit(offset: Int?, limit: Int?): Query {
    return this.withExpression(
        when (expression) {
            is SelectExpression -> expression.copy(
                offset = offset?.takeIf { it > 0 } ?: expression.offset,
                limit = limit?.takeIf { it > 0 } ?: expression.limit
            )
            is UnionExpression -> expression.copy(
                offset = offset?.takeIf { it > 0 } ?: expression.offset,
                limit = limit?.takeIf { it > 0 } ?: expression.limit
            )
        }
    )
}

/**
 * Union this query with the given one, corresponding to the `union` keyword in SQL.
 */
public fun Query.union(right: Query): Query {
    return this.withExpression(UnionExpression(left = expression, right = right.expression, isUnionAll = false))
}

/**
 * Union this query with the given one, corresponding to the `union all` keyword in SQL.
 */
public fun Query.unionAll(right: Query): Query {
    return this.withExpression(UnionExpression(left = expression, right = right.expression, isUnionAll = true))
}

/**
 * Wrap this query as [Iterable].
 *
 * @since 3.0.0
 */
public suspend fun Query.asIterable(): Iterable<QueryRow> {
    return doQuery().toList()
}

/**
 * Perform the given [action] on each row of the query.
 *
 * @since 3.0.0
 */
public suspend inline fun Query.forEach(crossinline action: (row: QueryRow) -> Unit) {
    doQuery().collect { action(it) }
}

/**
 * Perform the given [action] on each row of the query, providing sequential index with the row.
 *
 * The [action] function takes the index of a row and the row itself and performs the desired action on the row.
 *
 * @since 3.0.0
 */
public suspend inline fun Query.forEachIndexed(crossinline action: (index: Int, row: QueryRow) -> Unit) {
    doQuery().collectIndexed { index, it ->  action(index, it) }
}

/**
 * Return a lazy [Iterable] that wraps each row of the query into an [IndexedValue] containing the index of
 * that row and the row itself.
 *
 * @since 3.0.0
 */

public suspend fun Query.withIndex(): Iterable<IndexedValue<QueryRow>> {
    return doQuery().toList().withIndex()
}

/**
 * Iterator transforming original [iterator] into iterator of [IndexedValue], counting index from zero.
 */

@Suppress("IteratorNotThrowingNoSuchElementException")
internal class IndexingIterator<out T>(private val iterator: Iterator<T>) : Iterator<IndexedValue<T>> {
    private var index = 0

    override fun hasNext(): Boolean {
        return iterator.hasNext()
    }

    override fun next(): IndexedValue<T> {
        return IndexedValue(index++, iterator.next())
    }
}

/**
 * Return a list containing the results of applying the given [transform] function to each row of the query.
 *
 * @since 3.0.0
 */

public suspend inline fun <R> Query.map(crossinline transform: (row: QueryRow) -> R): List<R> {
    return mapTo(ArrayList(), transform)
}

/**
 * Apply the given [transform] function to each row of the query and append the results to the given [destination].
 *
 * @since 3.0.0
 */

public suspend inline fun <R, C : MutableCollection<in R>> Query.mapTo(
    destination: C,
    crossinline transform: (row: QueryRow) -> R
): C {

    doQuery().collect { destination += transform(it) }
    return destination
}

/**
 * Return a list containing only the non-null results of applying the given [transform] function to each row of
 * the query.
 *
 * @since 3.0.0
 */

public suspend inline fun <R : Any> Query.mapNotNull(crossinline transform: (row: QueryRow) -> R?): List<R> {
    return mapNotNullTo(ArrayList(), transform)
}

/**
 * Apply the given [transform] function to each row of the query and append only the non-null results to
 * the given [destination].
 *
 * @since 3.0.0
 */

public suspend inline fun <R : Any, C : MutableCollection<in R>> Query.mapNotNullTo(
    destination: C,
    crossinline transform: (row: QueryRow) -> R?
): C {
    doQuery().collect { row ->
        transform(row)?.let { destination += it }
    }
    return destination
}

/**
 * Return a list containing the results of applying the given [transform] function to each row and its index.
 *
 * The [transform] function takes the index of a row and the row itself and returns the result of the transform
 * applied to the row.
 *
 * @since 3.0.0
 */

public suspend inline fun <R> Query.mapIndexed(crossinline transform: (index: Int, row: QueryRow) -> R): List<R> {
    return mapIndexedTo(ArrayList(), transform)
}

/**
 * Apply the given [transform] function the each row and its index and append the results to the given [destination].
 *
 * The [transform] function takes the index of a row and the row itself and returns the result of the transform
 * applied to the row.
 *
 * @since 3.0.0
 */

public suspend inline fun <R, C : MutableCollection<in R>> Query.mapIndexedTo(
    destination: C,
    crossinline transform: (index: Int, row: QueryRow) -> R
): C {
    var index = 0
    return mapTo(destination) { row -> transform(index++, row) }
}

/**
 * Return a list containing only the non-null results of applying the given [transform] function to each row
 * and its index.
 *
 * The [transform] function takes the index of a row and the row itself and returns the result of the transform
 * applied to the row.
 *
 * @since 3.0.0
 */

public suspend inline fun <R : Any> Query.mapIndexedNotNull(crossinline transform: (index: Int, row: QueryRow) -> R?): List<R> {
    return mapIndexedNotNullTo(ArrayList(), transform)
}

/**
 * Apply the given [transform] function the each row and its index and append only the non-null results to
 * the given [destination].
 *
 * The [transform] function takes the index of a row and the row itself and returns the result of the transform
 * applied to the row.
 *
 * @since 3.0.0
 */

public suspend inline fun <R : Any, C : MutableCollection<in R>> Query.mapIndexedNotNullTo(
    destination: C,
    crossinline transform: (index: Int, row: QueryRow) -> R?
): C {
    forEachIndexed { index, row -> transform(index, row)?.let { destination += it } }
    return destination
}

/**
 * Return a single list of all elements yielded from results of [transform] function being invoked on each row
 * of the query.
 *
 * @since 3.0.0
 */

public suspend inline fun <R> Query.flatMap(crossinline transform: (row: QueryRow) -> Iterable<R>): List<R> {
    return flatMapTo(ArrayList(), transform)
}

/**
 * Append all elements yielded from results of [transform] function being invoked on each row of the query,
 * to the given [destination].
 *
 * @since 3.0.0
 */

public suspend inline fun <R, C : MutableCollection<in R>> Query.flatMapTo(
    destination: C,
    crossinline transform: (row: QueryRow) -> Iterable<R>
): C {
    doQuery().collect { destination += transform(it) }
    return destination
}

/**
 * Return a single list of all elements yielded from results of [transform] function being invoked on each row
 * and its index in the query.
 *
 * @since 3.1.0
 */

public suspend inline fun <R> Query.flatMapIndexed(crossinline transform: (index: Int, row: QueryRow) -> Iterable<R>): List<R> {
    return flatMapIndexedTo(ArrayList(), transform)
}

/**
 * Append all elements yielded from results of [transform] function being invoked on each row and its index
 * in the query, to the given [destination].
 *
 * @since 3.1.0
 */

public suspend inline fun <R, C : MutableCollection<in R>> Query.flatMapIndexedTo(
    destination: C,
    crossinline transform: (index: Int, row: QueryRow) -> Iterable<R>
): C {
    var index = 0
    return flatMapTo(destination) { transform(index++, it) }
}

/**
 * Return a [Map] containing key-value pairs provided by [transform] function applied to rows of the query.
 *
 * If any of two pairs would have the same key the last one gets added to the map.
 *
 * The returned map preserves the entry iteration order of the original query results.
 *
 * @since 3.0.0
 */

public suspend inline fun <K, V> Query.associate(crossinline transform: (row: QueryRow) -> Pair<K, V>): Map<K, V> {
    return associateTo(LinkedHashMap(), transform)
}

/**
 * Return a [Map] containing the values provided by [valueTransform] and indexed by [keySelector] functions applied to
 * rows of the query.
 *
 * If any two rows would have the same key returned by [keySelector] the last one gets added to the map.
 *
 * The returned map preserves the entry iteration order of the original query results.
 *
 * @since 3.0.0
 */

public suspend inline fun <K, V> Query.associateBy(
    crossinline keySelector: (row: QueryRow) -> K,
    crossinline valueTransform: (row: QueryRow) -> V
): Map<K, V> {
    return associateByTo(LinkedHashMap(), keySelector, valueTransform)
}

/**
 * Populate and return the [destination] mutable map with key-value pairs provided by [transform] function applied to
 * each row of the query.
 *
 * If any of two pairs would have the same key the last one gets added to the map.
 *
 * @since 3.0.0
 */

public suspend inline fun <K, V, M : MutableMap<in K, in V>> Query.associateTo(
    destination: M,
    crossinline transform: (row: QueryRow) -> Pair<K, V>
): M {
    doQuery().collect { destination += transform(it) }
    return destination
}

/**
 * Populate and return the [destination] mutable map with key-value pairs, where key is provided by the [keySelector]
 * function and value is provided by the [valueTransform] function applied to rows of the query.
 *
 * If any two rows would have the same key returned by [keySelector] the last one gets added to the map.
 *
 * @since 3.0.0
 */

public suspend inline fun <K, V, M : MutableMap<in K, in V>> Query.associateByTo(
    destination: M,
    crossinline keySelector: (row: QueryRow) -> K,
    crossinline valueTransform: (row: QueryRow) -> V
): M {
    doQuery().collect { destination.put(keySelector(it), valueTransform(it)) }
    return destination
}

/**
 * Accumulate value starting with [initial] value and applying [operation] to current accumulator value and each row.
 *
 * @since 3.0.0
 */

public suspend inline fun <R> Query.fold(initial: R, crossinline operation: (acc: R, row: QueryRow) -> R): R {
    var accumulator = initial
    doQuery().collect { accumulator = operation(accumulator, it) }
    return accumulator
}

/**
 * Accumulate value starting with [initial] value and applying [operation] to current accumulator value and each row
 * with its index in the original query results.
 *
 * The [operation] function takes the index of a row, current accumulator value and the row itself,
 * and calculates the next accumulator value.
 *
 * @since 3.0.0
 */

public suspend inline fun <R> Query.foldIndexed(initial: R, crossinline operation: (index: Int, acc: R, row: QueryRow) -> R): R {
    var index = 0
    var accumulator = initial
    doQuery().collect { accumulator = operation(index++, accumulator, it) }
    return accumulator
}

/**
 * Append the string from all rows separated using [separator] and using the given [prefix] and [postfix] if supplied.
 *
 * If the query result could be huge, you can specify a non-negative value of [limit], in which case only the first
 * [limit] rows will be appended, followed by the [truncated] string (which defaults to "...").
 *
 * @since 3.0.0
 */

public suspend inline fun <A : Appendable> Query.joinTo(
    buffer: A,
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    crossinline transform: (row: QueryRow) -> CharSequence
): A {
    buffer.append(prefix)
    var count = 0
    doQuery().collect {
        if (++count > 1) buffer.append(separator)
        if (limit < 0 || count <= limit) {
            buffer.append(transform(it))
        } else {
            buffer.append(truncated)
        }
    }
    buffer.append(postfix)
    return buffer
}

/**
 * Create a string from all rows separated using [separator] and using the given [prefix] and [postfix] if supplied.
 *
 * If the query result could be huge, you can specify a non-negative value of [limit], in which case only the first
 * [limit] rows will be appended, followed by the [truncated] string (which defaults to "...").
 *
 * @since 3.0.0
 */

public suspend inline fun Query.joinToString(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    crossinline transform: (row: QueryRow) -> CharSequence
): String {
    return joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString()
}

