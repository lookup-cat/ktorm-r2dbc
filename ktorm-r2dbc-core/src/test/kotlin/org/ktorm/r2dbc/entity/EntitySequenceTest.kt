package org.ktorm.r2dbc.entity

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.ktorm.r2dbc.BaseTest
import org.ktorm.r2dbc.dsl.*

/**
 * Created by vince on Mar 22, 2019.
 */
class EntitySequenceTest : BaseTest() {

    @Test
    fun testSequenceOf() = runBlocking {
        val employee = database
            .sequenceOf(Employees, withReferences = false)
            .filter { it.name eq "vince" }
            .single()

        println(employee)
        assert(employee.name == "vince")
        assert(employee.department.name.isEmpty())
    }

    @Test
    fun testToList() = runBlocking {
        val employees = database.employees.toList()
        assert(employees.size == 4)
        assert(employees[0].name == "vince")
        assert(employees[0].department.name == "tech")
    }

    @Test
    fun testFilter() = runBlocking {
        val names = database.employees
            .filter { it.departmentId eq 1 }
            .filterNot { it.managerId.isNull() }
            .toList()
            .map { it.name }

        assert(names.size == 1)
        assert(names[0] == "marry")
    }

    @Test
    fun testFilterTo() = runBlocking {
        val names = database.employees
            .filter { it.departmentId eq 1 }
            .filterTo(ArrayList()) { it.managerId.isNull() }
            .map { it.name }

        assert(names.size == 1)
        assert(names[0] == "vince")
    }

    @Test
    fun testCount() = runBlocking {
        assert(database.employees.filter { it.departmentId eq 1 }.count() == 2)
        assert(database.employees.count { it.departmentId eq 1 } == 2)
    }

    @Test
    fun testAll() = runBlocking {
        assert(database.employees.filter { it.departmentId eq 1 }.all { it.salary greater 49L })
    }

    @Test
    fun testAssociate() = runBlocking {
        val employees = database.employees.filter { it.departmentId eq 1 }.associateBy { it.id }
        assert(employees.size == 2)
        assert(employees[1]!!.name == "vince")
    }

    @Test
    fun testDrop() = runBlocking {
        try {
            val employees = database.employees.drop(3).toList()
            assert(employees.size == 1)
            assert(employees[0].name == "penny")
        } catch (e: UnsupportedOperationException) {
            // Expected, pagination should be provided by dialects...
        }
    }

    @Test
    fun testTake() = runBlocking {
        try {
            val employees = database.employees.take(1).toList()
            assert(employees.size == 1)
            assert(employees[0].name == "vince")
        } catch (e: UnsupportedOperationException) {
            // Expected, pagination should be provided by dialects...
        }
    }

    @Test
    fun testFindLast() = runBlocking {
        val employee = database.employees.elementAt(3)
        assert(employee.name == "penny")
        assert(database.employees.elementAtOrNull(4) == null)
    }

    @Test
    fun testFold() = runBlocking {
        val totalSalary = database.employees.fold(0L) { acc, employee -> acc + employee.salary }
        assert(totalSalary == 450L)
    }

    @Test
    fun testSorted() = runBlocking {
        val employee = database.employees.sortedByDescending { it.salary }.first()
        assert(employee.name == "tom")
    }

    @Test
    fun testFilterColumns() = runBlocking {
        val employee = database.employees
            .filterColumns { it.columns + it.department.columns - it.department.location }
            .filter { it.department.id eq 1 }
            .first()

        assert(employee.department.location.underlying.isEmpty())
    }

    @Test
    fun testGroupBy() = runBlocking {
        val employees = database.employees.groupBy { it.department.id }
        println(employees)
        assert(employees.size == 2)
        assert(employees[1]!!.sumOf { it.salary.toInt() } == 150)
        assert(employees[2]!!.sumOf { it.salary.toInt() } == 300)
    }

    @Test
    fun testGroupingBy() = runBlocking {
        val salaries = database.employees
            .groupingBy { it.departmentId * 2 }
            .fold(0L) { acc, employee ->
                acc + employee.salary
            }

        println(salaries)
        assert(salaries.size == 2)
        assert(salaries[2] == 150L)
        assert(salaries[4] == 300L)
    }

    @Test
    fun testEachCount() = runBlocking {
        val counts = database.employees
            .filter { it.salary less 100000L }
            .groupingBy { it.departmentId }
            .eachCount()

        println(counts)
        assert(counts.size == 2)
        assert(counts[1] == 2L)
        assert(counts[2] == 2L)
    }

    @Test
    fun testEachSum() = runBlocking {
        val sums = database.employees
            .filter { it.salary lessEq 100000L }
            .groupingBy { it.departmentId }
            .eachSumBy { it.salary }

        println(sums)
        assert(sums.size == 2)
        assert(sums[1] == 150L)
        assert(sums[2] == 300L)
    }

    @Test
    fun testJoinToString() = runBlocking {
        val salaries = database.employees.joinToString { it.id.toString() }
        assert(salaries == "1, 2, 3, 4")
    }

    @Test
    fun testReduce() = runBlocking {
        val emp = database.employees.reduce { acc, employee -> acc.apply { salary += employee.salary } }
        assert(emp.salary == 450L)
    }

    @Test
    fun testSingle() = runBlocking {
        val employee = database.employees.singleOrNull { it.departmentId eq -1 }
        assert(employee == null)
    }

    @Test
    fun testMapColumns() = runBlocking {
        val names = database.employees.sortedBy { it.id }.mapColumns { it.name }

        println(names)
        assert(names.size == 4)
        assert(names[0] == "vince")
    }

    @Test
    fun testFlatMap() = runBlocking {
        val names = database.employees
            .sortedBy { it.id.asc() }
            .flatMapIndexed { index, employee -> listOf("$index:${employee.name}") }

        println(names)
        assert(names.size == 4)
        assert(names[0] == "0:vince")
    }
}
