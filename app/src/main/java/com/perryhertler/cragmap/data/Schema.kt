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

@Dao
interface AreaDao {
    @Query("SELECT * FROM area WHERE depth = :depth")
    suspend fun areasAtDepth(depth: Int): List<AreaEntity>

    @Query("SELECT * FROM area WHERE is_leaf = 1")
    suspend fun leafAreas(): List<AreaEntity>

    @Query("SELECT * FROM area")
    suspend fun allAreas(): List<AreaEntity>

    @Query("SELECT MAX(depth) FROM area")
    suspend fun maxDepth(): Int
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


/** Cliff LineString derived offline from sibling area coordinates (tools/build_cliff_corridors.py). */
@Entity(tableName = "cliff_corridor")
data class CliffCorridorEntity(
    @PrimaryKey @ColumnInfo(name = "parent_uuid") val parentUuid: String,
    val name: String,
    val geojson: String,
    @ColumnInfo(name = "child_uuids_json") val childUuidsJson: String
)

@Dao
interface CliffCorridorDao {
    @Query("SELECT * FROM cliff_corridor")
    suspend fun getAll(): List<CliffCorridorEntity>

    @Query("SELECT * FROM cliff_corridor WHERE parent_uuid = :parentUuid")
    suspend fun get(parentUuid: String): CliffCorridorEntity?
}
