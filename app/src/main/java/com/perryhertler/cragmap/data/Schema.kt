package com.perryhertler.cragmap.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Mirrors the `area` table written by tools/openbeta_export.py.
 * depth/is_leaf are crag-agnostic (see blueprint) — not hardcoded to
 * Devils Lake's specific park/bluff/subarea/formation naming.
 */
@Entity(tableName = "area")
data class AreaEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    @ColumnInfo(name = "parent_uuid") val parentUuid: String?,
    val depth: Int,
    @ColumnInfo(name = "is_leaf") val isLeaf: Int,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "total_climbs") val totalClimbs: Int
)

/** Mirrors the `climb` table. lat/lng are inherited from the parent (leaf) area. */
@Entity(tableName = "climb")
data class ClimbEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "area_uuid") val areaUuid: String,
    val name: String,
    @ColumnInfo(name = "yds_grade") val ydsGrade: String?,
    @ColumnInfo(name = "climb_type") val climbType: String?,
    val description: String?,
    // OpenBeta's canonical left-to-right ordering of routes on a wall — how
    // climbers actually read a formation, not alphabetical by name.
    @ColumnInfo(name = "left_right_index") val leftRightIndex: Int?,
    val lat: Double?,
    val lng: Double?
)

/** name/parentName-only projection for area search — not a full AreaEntity row. */
data class AreaSearchResult(
    val uuid: String,
    val name: String,
    val parentName: String?,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "is_leaf") val isLeaf: Int
)

@Dao
interface AreaDao {
    @Query("SELECT * FROM area WHERE depth = :depth")
    suspend fun areasAtDepth(depth: Int): List<AreaEntity>

    // Every non-leaf area from depth 2 down, regardless of how much deeper the
    // tree goes past that (Devils Lake's real tree runs to depth 6 in places).
    // Areas at depth >= 3 used to have no pin at any zoom level at all — this
    // folds them into the same disclosure tier as depth-2 subareas instead of
    // only handling exactly 3 hardcoded levels.
    @Query("SELECT * FROM area WHERE depth >= 2 AND is_leaf = 0")
    suspend fun intermediateAreas(): List<AreaEntity>

    @Query("SELECT * FROM area WHERE is_leaf = 1")
    suspend fun leafAreas(): List<AreaEntity>

    @Query("SELECT * FROM area WHERE uuid = :uuid")
    suspend fun getArea(uuid: String): AreaEntity?

    @Query("SELECT * FROM area WHERE parent_uuid = :parentUuid")
    suspend fun childrenOf(parentUuid: String): List<AreaEntity>

    @Query("SELECT MAX(depth) FROM area")
    suspend fun maxDepth(): Int

    @Query(
        "SELECT a.uuid AS uuid, a.name AS name, p.name AS parentName, a.lat AS lat, a.lng AS lng, a.is_leaf AS is_leaf " +
            "FROM area a LEFT JOIN area p ON a.parent_uuid = p.uuid " +
            "WHERE a.name LIKE '%' || :term || '%' ORDER BY a.name LIMIT 20"
    )
    suspend fun searchByName(term: String): List<AreaSearchResult>
}

@Dao
interface ClimbDao {
    // Left-to-right wall order (how climbers actually read a formation), with
    // any climb missing an index (shouldn't happen, but OpenBeta doesn't
    // guarantee it) pushed to the end and sorted alphabetically among itself.
    @Query(
        "SELECT * FROM climb WHERE area_uuid = :areaUuid " +
            "ORDER BY left_right_index IS NULL, left_right_index, name"
    )
    suspend fun climbsInArea(areaUuid: String): List<ClimbEntity>
}
