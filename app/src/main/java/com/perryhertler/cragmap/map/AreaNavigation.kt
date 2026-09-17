package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.AppDatabase
import com.perryhertler.cragmap.data.AreaEntity

/** Root-first ancestor chain for breadcrumbs, e.g. [Devils Lake, East Bluff, East Rampart]. */
suspend fun breadcrumbFor(db: AppDatabase, area: AreaEntity): List<AreaEntity> {
    val path = mutableListOf<AreaEntity>()
    var parentUuid = area.parentUuid
    while (parentUuid != null) {
        val parent = db.areaDao().getArea(parentUuid) ?: break
        path.add(0, parent)
        parentUuid = parent.parentUuid
    }
    return path
}

/**
 * Orders areas along whichever axis (lng for an east-west wall, lat for
 * north-south) they actually span more of — the same "cliff order" idea as
 * the closed corridor-survey branch, computed on the fly from real
 * coordinates instead of a separately-maintained LineString table.
 */
fun sortByDominantAxis(areas: List<AreaEntity>): List<AreaEntity> {
    val withCoords = areas.filter { it.lat != null && it.lng != null }
    if (withCoords.size < 2) return areas
    val lats = withCoords.map { it.lat!! }
    val lngs = withCoords.map { it.lng!! }
    val latSpan = lats.max() - lats.min()
    val lngSpan = lngs.max() - lngs.min()
    return if (lngSpan >= latSpan) {
        areas.sortedBy { it.lng ?: Double.MAX_VALUE }
    } else {
        areas.sortedBy { it.lat ?: Double.MAX_VALUE }
    }
}

/**
 * Builds whatever the sheet should show for one area: its children in cliff
 * order if it's a disclosure node, or its climbs if it's a formation —
 * always with a breadcrumb and the sorted sibling list prev/next uses.
 */
suspend fun buildSheetContent(
    db: AppDatabase,
    area: AreaEntity,
    highlightedClimbUuid: String? = null
): SheetContent {
    val breadcrumb = breadcrumbFor(db, area)
    val siblings = area.parentUuid?.let { parentUuid ->
        sortByDominantAxis(db.areaDao().childrenOf(parentUuid))
    } ?: listOf(area)

    return if (area.isLeaf == 1) {
        val climbs = db.climbDao().climbsInArea(area.uuid)
        SheetContent.Climbs(area, breadcrumb, siblings, climbs, highlightedClimbUuid)
    } else {
        val children = sortByDominantAxis(db.areaDao().childrenOf(area.uuid))
        SheetContent.Children(area, breadcrumb, siblings, children)
    }
}
