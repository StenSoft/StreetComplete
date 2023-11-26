package de.westnordost.streetcomplete.data.osm.mapdata

import de.westnordost.streetcomplete.data.download.DownloadController
import de.westnordost.streetcomplete.data.osm.created_elements.CreatedElementsController
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryCreator
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryDao
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryEntry
import de.westnordost.streetcomplete.util.Listeners
import de.westnordost.streetcomplete.util.ktx.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox

/** Controller to access element data and its geometry and handle updates to it (from OSM API) */
class MapDataController internal constructor(
    private val nodeDB: NodeDao,
    private val wayDB: WayDao,
    private val relationDB: RelationDao,
    private val elementDB: ElementDao,
    private val geometryDB: ElementGeometryDao,
    private val elementGeometryCreator: ElementGeometryCreator,
    private val createdElementsController: CreatedElementsController,
    private val downloadController: DownloadController,
) : MapDataRepository {

    /* Must be a singleton because there is a listener that should respond to a change in the
     * database table */

    /** Interface to be notified of new or updated OSM elements */
    interface Listener {
        /** Called when a number of elements have been updated or deleted */
        fun onUpdated(updated: MutableMapDataWithGeometry, deleted: Collection<ElementKey>)

        /** Called when all elements in the given bounding box should be replaced with the elements
         *  in the mapDataWithGeometry */
        fun onReplacedForBBox(bbox: BoundingBox, mapDataWithGeometry: MutableMapDataWithGeometry)

        /** Called when all elements have been cleared */
        fun onCleared()
    }
    private val listeners = Listeners<Listener>()

    private val cache = MapDataCache(
        SPATIAL_CACHE_TILE_ZOOM,
        SPATIAL_CACHE_TILES,
        SPATIAL_CACHE_INITIAL_CAPACITY,
        { bbox ->
            val elements = elementDB.getAll(bbox)
            val elementGeometries = geometryDB.getAllEntries(
                elements.mapNotNull { if (it !is Node) it.key else null }
            )
            elements to elementGeometries
        },
        { nodeDB.getAll(it) },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dbJob: Job? = null // we rely on this job never being canceled (which should not be possible unless explicitly calling cancel)

    /** update element data with [mapData] in the given [bbox] (fresh data from the OSM API has been
     *  downloaded) */
    fun putAllForBBox(bbox: BoundingBox, mapData: MutableMapData) {
        val time = nowAsEpochMilliseconds()

        val oldElementKeys: Set<ElementKey>
        val geometryEntries: Collection<ElementGeometryEntry>
        synchronized(this) {
            // for incompletely downloaded relations, complete the map data (as far as possible) with
            // local data, i.e. with local nodes and ways (still) in local storage
            completeMapData(mapData)

            geometryEntries = createGeometries(mapData, mapData)

            // don't use cache here, because if not everything is already cached, db call will be faster
            oldElementKeys = elementDB.getAllKeys(mapData.boundingBox!!).toHashSet()
            for (element in mapData) {
                oldElementKeys.remove(element.key)
            }

            // for the cache, use bbox and not mapData.boundingBox because the latter is padded,
            // see comment for QUEST_FILTER_PADDING
            cache.update(oldElementKeys, mapData, geometryEntries, bbox)
        }

        val mapDataWithGeometry = MutableMapDataWithGeometry(mapData, geometryEntries)
        mapDataWithGeometry.boundingBox = mapData.boundingBox

        // first call onReplaced, then persist the data
        // this allows quests to be created and displayed before starting to persist data (which slows down quest creation considerably)
        // overall the persist takes a little longer, but it's still perceived as a clear performance improvement
        cache.noTrimPlus(mapData.boundingBox!!) // quest creation can trigger trim, so we need to set noTrim here
        onReplacedForBBox(bbox, mapDataWithGeometry)

        val oldDbJob = dbJob
        dbJob = scope.launch {
            downloadController.setPersisting(true)
            oldDbJob?.join()
            synchronized(this@MapDataController) {
                elementDB.deleteAll(oldElementKeys)
                geometryDB.deleteAll(oldElementKeys)
                geometryDB.putAll(geometryEntries)
                elementDB.putAll(mapData)
            }
            cache.noTrimMinus(mapData.boundingBox!!)
            Log.i(TAG,
                "Persisted ${geometryEntries.size} and deleted ${oldElementKeys.size} elements and geometries" +
                " in ${((nowAsEpochMilliseconds() - time) / 1000.0).format(1)}s"
            )
            downloadController.setPersisting(false)
        }
    }

    /** incorporate the [mapDataUpdates] (data has been updated after upload) */
    fun updateAll(mapDataUpdates: MapDataUpdates) {
        val elements = mapDataUpdates.updated
        // need mapData in order to create (updated) geometry
        val mapData = MutableMapData(elements)

        val deletedKeys: List<ElementKey>
        val geometryEntries: Collection<ElementGeometryEntry>
        synchronized(this) {
            completeMapData(mapData)

            geometryEntries = createGeometries(elements, mapData)

            val newElementKeys = mapDataUpdates.idUpdates.map { ElementKey(it.elementType, it.newElementId) }
            val oldElementKeys = mapDataUpdates.idUpdates.map { ElementKey(it.elementType, it.oldElementId) }
            deletedKeys = mapDataUpdates.deleted + oldElementKeys

            cache.update(deletedKeys, elements, geometryEntries)

            if (newElementKeys.isNotEmpty())
                createdElementsController.putAll(newElementKeys)
        }

        val mapDataWithGeom = MutableMapDataWithGeometry(elements, geometryEntries)
        mapDataWithGeom.boundingBox = mapData.boundingBox

        val bbox = geometryEntries.flatMap { listOf(it.geometry.getBounds().min, it.geometry.getBounds().max) }.enclosingBoundingBox()
        cache.noTrimPlus(bbox) // quest creation can trigger trim, so we need to set noTrim here
        onUpdated(updated = mapDataWithGeom, deleted = deletedKeys)

        val oldDbJob = dbJob
        dbJob = scope.launch {
            // the background job here is mostly so that a running dbJob (slow persist) doesn't block updateAll
            oldDbJob?.join()
            // no need to set persisting, as this is only few elements at a time
            synchronized(this@MapDataController) {
                elementDB.deleteAll(deletedKeys)
                geometryDB.deleteAll(deletedKeys)
                geometryDB.putAll(geometryEntries)
                elementDB.putAll(elements)
            }
            cache.noTrimMinus(bbox)
        }
    }

    /** Create ElementGeometryEntries for [elements] using [mapData] to supply the necessary geometry */
    private fun createGeometries(elements: Iterable<Element>, mapData: MapData): Collection<ElementGeometryEntry> =
        elements.mapNotNull { element ->
            val geometry = elementGeometryCreator.create(element, mapData, true)
            geometry?.let { ElementGeometryEntry(element.type, element.id, geometry) }
        }

    private fun completeMapData(mapData: MutableMapData) {
        val missingNodeIds = mutableListOf<Long>()
        val missingWayIds = mutableListOf<Long>()
        for (relation in mapData.relations) {
            for (member in relation.members) {
                if (member.type == ElementType.NODE && mapData.getNode(member.ref) == null) {
                    missingNodeIds.add(member.ref)
                }
                if (member.type == ElementType.WAY && mapData.getWay(member.ref) == null) {
                    missingWayIds.add(member.ref)
                }
                /* deliberately not recursively looking for relations of relations
                   because that is also not how the OSM API works */
            }
        }

        val ways = getWays(missingWayIds)
        for (way in mapData.ways + ways) {
            for (nodeId in way.nodeIds) {
                if (mapData.getNode(nodeId) == null) {
                    missingNodeIds.add(nodeId)
                }
            }
        }
        val nodes = getNodes(missingNodeIds)

        mapData.addAll(nodes)
        mapData.addAll(ways)
    }

    fun getGeometry(type: ElementType, id: Long): ElementGeometry? = cache.getGeometry(type, id, geometryDB::get, nodeDB::get)

    fun getGeometries(keys: Collection<ElementKey>): List<ElementGeometryEntry> = cache.getGeometries(keys, geometryDB::getAllEntries, nodeDB::getAll)

    fun getMapDataWithGeometry(bbox: BoundingBox): MutableMapDataWithGeometry {
        val time = nowAsEpochMilliseconds()
        val result = cache.getMapDataWithGeometry(bbox)
        Log.i(TAG, "Fetched ${result.size} elements and geometries in ${nowAsEpochMilliseconds() - time}ms")

        return result
    }

    data class ElementCounts(val nodes: Int, val ways: Int, val relations: Int)
    // this is used after downloading one tile with auto-download, so we should always have it cached
    fun getElementCounts(bbox: BoundingBox): ElementCounts {
        val data = getMapDataWithGeometry(bbox)
        return ElementCounts(
            data.count { it is Node },
            data.count { it is Way },
            data.count { it is Relation }
        )
    }

    override fun getNode(id: Long): Node? = cache.getElement(ElementType.NODE, id, elementDB::get) as? Node
    override fun getWay(id: Long): Way? = cache.getElement(ElementType.WAY, id, elementDB::get) as? Way
    override fun getRelation(id: Long): Relation? = cache.getElement(ElementType.RELATION, id, elementDB::get) as? Relation

    fun getAll(elementKeys: Collection<ElementKey>): List<Element> =
        cache.getElements(elementKeys, elementDB::getAll)

    fun getNodes(ids: Collection<Long>): List<Node> = cache.getNodes(ids, nodeDB::getAll)
    fun getWays(ids: Collection<Long>): List<Way> = cache.getWays(ids, wayDB::getAll)
    fun getRelations(ids: Collection<Long>): List<Relation> = cache.getRelations(ids, relationDB::getAll)

    override fun getWaysForNode(id: Long): List<Way> = cache.getWaysForNode(id, wayDB::getAllForNode)
    override fun getRelationsForNode(id: Long): List<Relation> = cache.getRelationsForNode(id, relationDB::getAllForNode)
    override fun getRelationsForWay(id: Long): List<Relation> = cache.getRelationsForWay(id, relationDB::getAllForWay)
    override fun getRelationsForRelation(id: Long): List<Relation> = cache.getRelationsForRelation(id, relationDB::getAllForRelation)

    override fun getWayComplete(id: Long): MapData? {
        val way = getWay(id) ?: return null
        val nodeIds = way.nodeIds.toHashSet()
        val nodes = getNodes(nodeIds)
        if (nodes.size < nodeIds.size) return null
        return MutableMapData(nodes + way)
    }

    override fun getRelationComplete(id: Long): MapData? {
        val relation = getRelation(id) ?: return null
        val elementKeys = relation.members.mapTo(HashSet(relation.members.size)) { it.key }
        val elements = getAll(elementKeys)
        if (elements.size < elementKeys.size) return null
        return MutableMapData(elements + relation)
    }

    fun deleteOlderThan(timestamp: Long, limit: Int? = null): Int {
        val elements: List<ElementKey>
        val elementCount: Int
        val geometryCount: Int
        synchronized(this) {
            val relations = relationDB.getIdsOlderThan(timestamp, limit).map { ElementKey(ElementType.RELATION, it) }
            val ways = wayDB.getIdsOlderThan(timestamp, limit?.minus(relations.size)).map { ElementKey(ElementType.WAY, it) }

            // delete now, so filterNodeIdsWithoutWays works as intended
            cache.update(deletedKeys = ways + relations)
            val wayAndRelationCount = elementDB.deleteAll(ways + relations)
            val nodes = nodeDB.getIdsOlderThan(timestamp, limit?.minus(relations.size + ways.size))
            // filter nodes to only delete nodes that are not part of a ways in the database
            val filteredNodes = wayDB.filterNodeIdsWithoutWays(nodes).map { ElementKey(ElementType.NODE, it) }

            elements = relations + ways + filteredNodes
            if (elements.isEmpty()) return 0

            cache.update(deletedKeys = filteredNodes)
            elementCount = wayAndRelationCount + elementDB.deleteAll(filteredNodes)
            geometryCount = geometryDB.deleteAll(elements)
            createdElementsController.deleteAll(elements)
        }
        Log.i(TAG, "Deleted $elementCount old elements and $geometryCount geometries")

        onUpdated(deleted = elements)

        return elementCount
    }

    fun clear() {
        synchronized(this) {
            clearCache()
            elementDB.clear()
            geometryDB.clear()
            createdElementsController.clear()
        }
        onCleared()
    }

    fun clearCache() = synchronized(this) { cache.clear() }

    fun trimCache() = synchronized(this) { cache.trim(SPATIAL_CACHE_TILES / 4) }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun onUpdated(
        updated: MutableMapDataWithGeometry = MutableMapDataWithGeometry(),
        deleted: Collection<ElementKey> = emptyList()
    ) {
        if (updated.nodes.isEmpty() && updated.ways.isEmpty() && updated.relations.isEmpty() && deleted.isEmpty()) return

        listeners.forEach { it.onUpdated(updated, deleted) }
    }

    private fun onReplacedForBBox(bbox: BoundingBox, mapDataWithGeometry: MutableMapDataWithGeometry) {
        listeners.forEach { it.onReplacedForBBox(bbox, mapDataWithGeometry) }
    }

    private fun onCleared() {
        listeners.forEach { it.onCleared() }
    }

    companion object {
        private const val TAG = "MapDataController"
    }
}

// StyleableOverlayManager loads z16 tiles, but we want smaller tiles. Small tiles make db fetches for
// typical getMapDataWithGeometry calls noticeably faster than z16, as they usually only require a small area.
private const val SPATIAL_CACHE_TILE_ZOOM = 18

// Three times the maximum number of tiles that can be loaded at once in StyleableOverlayManager (translated from z16 tiles).
// We don't want to drop tiles from cache already when scrolling the map just a bit, especially
// considering automatic trim may temporarily reduce cache size to 2/3 of maximum.
private const val SPATIAL_CACHE_TILES = 64 * 4 * 4 // 48 z16 tiles, but 2 zoom levels higher

// In a city this is roughly the number of nodes in ~20-40 z16 tiles
private const val SPATIAL_CACHE_INITIAL_CAPACITY = 100000
