package de.westnordost.streetcomplete.screens.settings.debug

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.edits.AddElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.ElementEditAction
import de.westnordost.streetcomplete.data.osm.edits.ElementEditType
import de.westnordost.streetcomplete.data.osm.edits.delete.DeletePoiNodeAction
import de.westnordost.streetcomplete.data.osm.edits.update_tags.UpdateElementTagsAction
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.osm.osmquests.HideOsmQuestController
import de.westnordost.streetcomplete.data.osm.osmquests.OsmElementQuestType
import de.westnordost.streetcomplete.data.osm.osmquests.OsmQuest
import de.westnordost.streetcomplete.data.quest.OsmQuestKey
import de.westnordost.streetcomplete.data.quest.QuestKey
import de.westnordost.streetcomplete.data.quest.QuestType
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.databinding.FragmentShowQuestFormsBinding
import de.westnordost.streetcomplete.databinding.RowQuestDisplayBinding
import de.westnordost.streetcomplete.quests.AbstractOsmQuestForm
import de.westnordost.streetcomplete.quests.AbstractQuestForm
import de.westnordost.streetcomplete.screens.BaseActivity
import de.westnordost.streetcomplete.screens.settings.genericQuestTitle
import de.westnordost.streetcomplete.util.math.translate
import de.westnordost.streetcomplete.util.viewBinding
import de.westnordost.streetcomplete.view.ListAdapter
import org.koin.android.ext.android.inject

/** activity only used in debug, to show all the different forms for the different quests. */
class ShowQuestFormsActivity : BaseActivity(), AbstractOsmQuestForm.Listener {

    private val questTypeRegistry: QuestTypeRegistry by inject()
    private val prefs: SharedPreferences by inject()

    private val binding by viewBinding(FragmentShowQuestFormsBinding::inflate)

    private val showQuestFormAdapter: ShowQuestFormAdapter = ShowQuestFormAdapter()

    private var currentQuestType: QuestType? = null

    private var pos: LatLon = LatLon(0.0, 0.0)

    init {
        showQuestFormAdapter.list = questTypeRegistry.toMutableList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_show_quest_forms)
        binding.toolbarLayout.toolbar.navigationIcon = getDrawable(R.drawable.ic_close_24dp)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { onBackPressed() }
        binding.toolbarLayout.toolbar.title = "Show Quest Forms"

        binding.questFormContainer.setOnClickListener { onBackPressed() }

        binding.showQuestFormsList.apply {
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            layoutManager = LinearLayoutManager(context)
            adapter = showQuestFormAdapter
        }
    }

    override fun onStart() {
        super.onStart()
        pos = LatLon(
            Double.fromBits(prefs.getLong(Prefs.MAP_LATITUDE, 0.0.toBits())),
            Double.fromBits(prefs.getLong(Prefs.MAP_LONGITUDE, 0.0.toBits()))
        )
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            popQuestForm()
        } else {
            super.onBackPressed()
        }
    }

    private fun popQuestForm() {
        binding.questFormContainer.visibility = View.GONE
        supportFragmentManager.popBackStack()
        currentQuestType = null
    }

    inner class ShowQuestFormAdapter : ListAdapter<QuestType>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListAdapter.ViewHolder<QuestType> =
            ViewHolder(RowQuestDisplayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        private inner class ViewHolder(val binding: RowQuestDisplayBinding) : ListAdapter.ViewHolder<QuestType>(binding) {
            override fun onBind(with: QuestType) {
                binding.questIcon.setImageResource(with.icon)
                binding.questTitle.text = genericQuestTitle(itemView.resources, with)
                binding.root.setOnClickListener { onClickQuestType(with) }
            }
        }
    }

    private fun onClickQuestType(questType: QuestType) {
        if (questType !is OsmElementQuestType<*>) return

        val firstPos = pos.translate(20.0, 45.0)
        val secondPos = pos.translate(20.0, 135.0)
        /* tags are values that results in more that quests working on showing/solving debug quest
           form, i.e. some quests expect specific tags to be set and crash without them - what is
           OK, but here some tag combination needs to be setup to reduce number of crashes when
           using test forms */
        val tags = mapOf(
            "highway" to "cycleway",
            "building" to "residential",
            "name" to "<object name>",
            "opening_hours" to "Mo-Fr 08:00-12:00,13:00-17:30; Sa 08:00-12:00",
            "addr:housenumber" to "176"
        )
        // way geometry is needed by quests using clickable way display (steps direction, sidewalk quest, lane quest, cycleway quest...)
        val element = Way(1, listOf(1, 2), tags, 1)
        val geometry = ElementPolylinesGeometry(listOf(listOf(firstPos, secondPos)), pos)
        // for testing quests requiring nodes code above can be commented out and this uncommented
        // val element = Node(1, centerPos, tags, 1)
        // val geometry = ElementPointGeometry(centerPos)

        val quest = OsmQuest(questType, element.type, element.id, geometry)

        val f = questType.createForm()
        if (f.arguments == null) f.arguments = bundleOf()
        f.requireArguments().putAll(
            AbstractQuestForm.createArguments(quest.key, quest.type, geometry, 30.0f, 0.0f)
        )
        f.requireArguments().putAll(AbstractOsmQuestForm.createArguments(element))
        f.hideOsmQuestController = object : HideOsmQuestController {
            override fun hide(key: OsmQuestKey) {}
            override fun tempHide(key: OsmQuestKey) {}
        }
        f.addElementEditsController = object : AddElementEditsController {
            override fun add(
                type: ElementEditType,
                element: Element,
                geometry: ElementGeometry,
                source: String,
                action: ElementEditAction,
                key: QuestKey?
            ) {
                when (action) {
                    is DeletePoiNodeAction -> {
                        message("Deleted node")
                    }
                    is UpdateElementTagsAction -> {
                        val tagging = action.changes.changes.joinToString("\n")
                        message("Tagging\n$tagging")
                    }
                }
            }
        }

        currentQuestType = questType

        binding.questFormContainer.visibility = View.VISIBLE
        supportFragmentManager.commit {
            replace(R.id.questForm, f)
            addToBackStack(null)
        }
    }

    override val displayedMapLocation: Location
        get() = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = pos.latitude
            longitude = pos.longitude
        }

    override fun onEdited(editType: ElementEditType, element: Element, geometry: ElementGeometry) {
        popQuestForm()
    }

    override fun onComposeNote(
        editType: ElementEditType,
        element: Element,
        geometry: ElementGeometry,
        leaveNoteContext: String,
    ) {
        message("Composing note")
        popQuestForm()
    }

    override fun onSplitWay(editType: ElementEditType, way: Way, geometry: ElementPolylinesGeometry) {
        message("Splitting way")
        popQuestForm()
    }

    override fun onQuestHidden(questKey: QuestKey) {
        popQuestForm()
    }

    override fun onEditTags(element: Element, geometry: ElementGeometry, questKey: QuestKey?) {
        message("Showing Tag Editor")
        popQuestForm()
    }

    private fun message(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this).setMessage(msg).show()
        }
    }
}
