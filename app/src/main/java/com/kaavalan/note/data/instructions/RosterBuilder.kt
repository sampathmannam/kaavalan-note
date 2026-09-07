package com.kaavalan.note.data.instructions

import com.kaavalan.note.data.person.Person

data class RosterNode(val station: String, val byDesignation: Map<String, List<Person>>) {
    val designations: List<String> get() = byDesignation.keys.sortedBy { RosterBuilder.seniority(it) }
    fun peopleFor(designation: String): List<Person> {
        // Case-insensitive lookup: a user types `@si` in the mention,
        // but the stored designation might be "SI". Map look-up is
        // case-sensitive by default, so the naive lookup misses
        // real matches. The original-case key is preserved for
        // display (see `designations`) — only the query is
        // case-folded.
        val needle = designation.lowercase()
        val key = byDesignation.keys.firstOrNull { it.lowercase() == needle }
        return key?.let { byDesignation[it] } ?: emptyList()
    }
    val totalPeople: Int get() = byDesignation.values.sumOf { it.size }
}
data class RosterPicker(val stations: List<RosterNode>, val allDesignations: List<String>) {
    val totalPeople: Int get() = stations.sumOf { it.totalPeople }
    val allPeople: List<Person> by lazy { stations.flatMap { it.byDesignation.values.flatten() }.distinctBy { it.id } }
    fun peopleByDesignation(designation: String): List<Person> = stations.flatMap { it.peopleFor(designation) }
}
object RosterBuilder {
    fun build(persons: List<Person>): RosterPicker {
        val grouped = persons.groupBy { it.station?.takeIf { s -> s.isNotBlank() } ?: "Unassigned" }
        val nodes = grouped.map { (station, people) -> val byDesig = people.groupBy { it.designation?.takeIf { d -> d.isNotBlank() } ?: "Unassigned" }; RosterNode(station = station, byDesignation = byDesig) }.sortedBy { it.station.lowercase() }
        // distinctBy { lowercase } here, not distinct(): peopleFor()
        // above resolves a designation case-insensitively, so "SI" and
        // "si" already collapse to the same people once picked. A
        // case-sensitive distinct() would still list them as two rows
        // in the Designation picker -- a duplicate-audience trap where
        // both rows silently resolve to the same overlapping set.
        return RosterPicker(stations = nodes, allDesignations = nodes.flatMap { it.designations }.distinctBy { it.lowercase() })
    }
    fun seniority(designation: String): Int { val d = designation.lowercase(); return when (d) { "ig" -> 0; "dig" -> 1; "sp", "superintendent" -> 2; "addl sp", "additional sp" -> 3; "dsp", "asp" -> 4; "inspector" -> 5; "sho" -> 6; "si", "sub-inspector" -> 7; "asi" -> 8; "hc", "head constable" -> 9; "constable" -> 10; else -> 50 } }
}
