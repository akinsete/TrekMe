package com.peterlaurence.trekme.ui.mapview

import android.content.Context
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.peterlaurence.mapview.MapView
import com.peterlaurence.mapview.MapViewConfiguration
import com.peterlaurence.mapview.api.addMarker
import com.peterlaurence.mapview.api.moveMarker
import com.peterlaurence.mapview.api.moveToMarker
import com.peterlaurence.mapview.api.setMarkerTapListener
import com.peterlaurence.mapview.markers.*
import com.peterlaurence.trekme.R
import com.peterlaurence.trekme.billing.ign.Billing
import com.peterlaurence.trekme.core.events.OrientationEventManager
import com.peterlaurence.trekme.core.map.Map
import com.peterlaurence.trekme.core.map.gson.MarkerGson
import com.peterlaurence.trekme.core.map.maploader.MapLoader
import com.peterlaurence.trekme.core.projection.Projection
import com.peterlaurence.trekme.core.track.TrackImporter
import com.peterlaurence.trekme.ui.LocationProviderHolder
import com.peterlaurence.trekme.ui.mapview.components.PositionOrientationMarker
import com.peterlaurence.trekme.ui.mapview.events.TrackVisibilityChangedEvent
import com.peterlaurence.trekme.viewmodel.common.Location
import com.peterlaurence.trekme.viewmodel.common.LocationProvider
import com.peterlaurence.trekme.viewmodel.common.LocationViewModel
import com.peterlaurence.trekme.viewmodel.common.tileviewcompat.makeTileStreamProvider
import com.peterlaurence.trekme.viewmodel.mapview.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

/**
 * This fragment displays a [Map], using [MapView].
 *
 * @author peterLaurence on 10/02/2019
 */
class MapViewFragment : Fragment(), MapViewFragmentPresenter.PositionTouchListener {
    private lateinit var presenter: MapViewFragmentContract.Presenter
    private var mapView: MapView? = null
    private var mMap: Map? = null
    private lateinit var positionMarker: PositionOrientationMarker
    private var lockView = false
    private var requestManageTracksListener: RequestManageTracksListener? = null
    private var requestManageMarkerListener: RequestManageMarkerListener? = null
    private var shouldCenterOnFirstLocation = false
    private var magnifyingFactor: Int? = null
    private lateinit var orientationEventManager: OrientationEventManager
    private lateinit var markerLayer: MarkerLayer
    private lateinit var routeLayer: RouteLayer
    private lateinit var distanceLayer: DistanceLayer
    private lateinit var landmarkLayer: LandmarkLayer
    private lateinit var speedListener: SpeedListener
    private lateinit var distanceListener: DistanceLayer.DistanceListener
    private lateinit var locationProvider: LocationProvider
    private var billing: Billing? = null

    private val mapViewViewModel: MapViewViewModel by viewModels()
    private val locationViewModel: LocationViewModel by viewModels()
    private val inMapRecordingViewModel: InMapRecordingViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is RequestManageTracksListener && context is RequestManageMarkerListener &&
                context is LocationProviderHolder) {
            requestManageTracksListener = context
            requestManageMarkerListener = context
            locationProvider = context.locationProvider
        } else {
            throw RuntimeException("$context must implement RequestManageTracksListener, " +
                    "RequestManageMarkerListener and LocationProviderHolder")
        }
        activity?.let {
            billing = Billing(context, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        setHasOptionsMenu(true)
        locationViewModel.setLocationProvider(locationProvider)
        locationViewModel.getLocationLiveData().observe(this, Observer<Location> {
            it?.let {
                onLocationReceived(it)
            }
        })

        /* When the fragment is created for the first time, center on first location */
        shouldCenterOnFirstLocation = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val context = context ?: return null
        /* Create layout from scratch if it does not exist */
        if (!::presenter.isInitialized) {
            presenter = MapViewFragmentPresenter(layoutInflater, container, context)
            presenter.setPositionTouchListener(this)
        }

        /* Get the speed, distance and orientation indicators from the view */
        speedListener = presenter.view.speedIndicator
        distanceListener = presenter.view.distanceIndicator
        positionMarker = presenter.view.positionMarker

        /* Create the instance of the OrientationEventManager */
        orientationEventManager = OrientationEventManager(activity)

        /* Register the position marker as an OrientationListener */
        orientationEventManager.setOrientationListener(positionMarker)
        if (savedInstanceState != null) {
            val shouldDisplayOrientation = savedInstanceState.getBoolean(WAS_DISPLAYING_ORIENTATION)
            if (shouldDisplayOrientation) {
                orientationEventManager.start()
            }
        }

        /* Create the marker layer */
        markerLayer = MarkerLayer(context)
        markerLayer.setRequestManageMarkerListener(requestManageMarkerListener)

        /* Create the route layer */
        routeLayer = RouteLayer(lifecycleScope)

        /* Create the distance layer */
        distanceLayer = DistanceLayer(context, distanceListener)

        /* Create the landmark layer */
        context.let {
            landmarkLayer = LandmarkLayer(it, lifecycleScope)
        }

        /* Create the MapView.
         * Attached views must be known before onCreateView returns, or the save/restore state
         * won't work.
         */
        mapView = MapView(context).also {
            it.apply {
                id = R.id.tileview_id
                isSaveEnabled = true
            }
            presenter.setMapView(it)
        }

        /* Then, asynchronously get the required settings and apply the map */
        lifecycleScope.launch {
            /* First, the settings */
            getMapSettings()

            /* Then, apply the Map */
            getAndApplyMap()
        }

        return presenter.androidView
    }

    private suspend fun getMapSettings() {
        magnifyingFactor = mapViewViewModel.getMagnifyingFactor()
    }

    private suspend fun getAndApplyMap() {
        mapViewViewModel.getMap(billing)?.let {
            try {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    applyMap(it)
                }
            } catch (t: Throwable) {
                // probably the fragment wasn't in the proper state
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        /* Hide the app title */
        val actionBar = (activity as AppCompatActivity).supportActionBar
        actionBar?.setDisplayShowTitleEnabled(false)

        /* Clear the existing action menu */
        menu.clear()

        /* Fill the new one */
        inflater.inflate(R.menu.menu_fragment_map_view, menu)

        /* .. and restore some checkable state */
        val item = menu.findItem(R.id.distancemeter_id)
        item.isChecked = distanceLayer.isVisible

        val itemOrientation = menu.findItem(R.id.orientation_enable_id)
        itemOrientation.isChecked = orientationEventManager.isStarted

        val itemLockOnPosition = menu.findItem(R.id.lock_on_position_id)
        itemLockOnPosition.isChecked = lockView

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.add_marker_id -> {
                markerLayer.addNewMarker()
                return true
            }
            R.id.manage_tracks_id -> {
                requestManageTracksListener?.onRequestManageTracks()
                return true
            }
            R.id.speedometer_id -> {
                speedListener.toggleSpeedVisibility()
                return true
            }
            R.id.distancemeter_id -> {
                distanceListener.toggleDistanceVisibility()
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    distanceLayer.show()
                } else {
                    distanceLayer.hide()
                }
                return true
            }
            R.id.orientation_enable_id -> {
                item.isChecked = orientationEventManager.toggleOrientation()
                return true
            }
            R.id.landmark_id -> {
                landmarkLayer.addNewLandmark()
                return true
            }
            R.id.lock_on_position_id -> {
                item.isChecked = !item.isChecked
                lockView = item.isChecked
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onPositionTouch() {
        mapView?.scale = 1f
        centerOnPosition()
    }

    override fun onResume() {
        super.onResume()

        locationViewModel.startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        /* Save battery */
        locationViewModel.stopLocationUpdates()
    }

    @Subscribe
    fun onTrackVisibilityChangedEvent(event: TrackVisibilityChangedEvent) {
        routeLayer.onTrackVisibilityChanged()
    }

    @Subscribe
    fun onTrackChangedEvent(event: TrackImporter.GpxParseResult) {
        routeLayer.onTrackChanged(event.map, event.routes)
        if (event.newMarkersCount > 0) {
            markerLayer.onMapMarkerUpdate()
        }
    }

    @Subscribe
    fun onOutdatedIgnLicense(event: OutdatedIgnLicenseEvent) {
        val context = context ?: return
        clearMap()
        presenter.showMessage(context.getString(R.string.expired_ign_license))
    }

    @Subscribe
    fun onErrorIgnLicense(event: ErrorIgnLicenseEvent) {
        val context = context ?: return
        clearMap()
        presenter.showMessage(context.getString(R.string.missing_ign_license))
    }

    @Subscribe
    fun onGracePeriodIgnLicense(event: GracePeriodIgnEvent) {
        val view = view ?: return
        val context = context ?: return
        val msg = context.getString(R.string.grace_period_ign_license).format(event.remainingDays)
        val snackBar = Snackbar.make(view, msg, Snackbar.LENGTH_LONG)
        snackBar.show()
    }

    /**
     * Once we get a [Map], a [MapView] instance is created, so layers can be
     * updated.
     */
    private fun applyMap(map: Map) {
        mapView?.also {
            configureMapView(it, map)
        }
        inMapRecordingViewModel.reload()
        initLayers()

        /**
         * Listen to changes on the live route.
         * It's called only now because the [RouteLayer] must be first initialized.
         */
        inMapRecordingViewModel.getLiveRoute().observe(
                viewLifecycleOwner, Observer<InMapRecordingViewModel.LiveRoute> {
            it?.let { liveRoute ->
                routeLayer.updateLiveRoute(liveRoute.route, liveRoute.map)
            }
        })
    }

    private fun initLayers() {
        mMap?.let { map ->
            val mapView = mapView ?: return

            /* Update the marker layer */
            markerLayer.init(map, mapView)

            /* Update the route layer */
            routeLayer.init(map, mapView)

            /* Update the distance layer */
            distanceLayer.init(map, mapView)

            /* Update the landmark layer */
            landmarkLayer.init(map, mapView)
        }
    }

    private fun destroyLayers() {
        distanceLayer.destroy()
        landmarkLayer.destroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        EventBus.getDefault().unregister(this)
        MapLoader.clearMapMarkerUpdateListener()
        destroyLayers()
    }

    override fun onDetach() {
        super.onDetach()

        requestManageTracksListener = null
        requestManageMarkerListener = null
        orientationEventManager.stop()
    }

    private fun onLocationReceived(location: Location) {
        if (isHidden) return

        /* If there is no MapView, no need to go further */
        mapView ?: return

        /* In the case there is no Projection defined, the latitude and longitude are used */
        val map = mMap ?: return
        val projection = map.projection
        if (projection != null) {
            lifecycleScope.launch {
                val projectedValues = withContext(Dispatchers.Default) {
                    projection.doProjection(location.latitude, location.longitude)
                }
                if (projectedValues != null) {
                    updatePosition(projectedValues[0], projectedValues[1])
                }
            }
        } else {
            updatePosition(location.longitude, location.latitude)
        }

        /* If the user wants to see the speed */
        if (::speedListener.isInitialized) {
            speedListener.onSpeed(location.speed, SpeedUnit.KM_H)
        }
    }

    /**
     * Actions taken when the position changes:
     * * Update the position on the [Map]
     * * Update the landmarks
     * * If we locked the view or if the fragment has just been created, we center the TileView on
     *   the current position only if the position is inside the map.
     *
     * @param x the projected X coordinate, or longitude if there is no [Projection]
     * @param y the projected Y coordinate, or latitude if there is no [Projection]
     */
    private fun updatePosition(x: Double, y: Double) {
        mapView?.moveMarker(positionMarker, x, y)
        landmarkLayer.onPositionUpdate(x, y)

        if (lockView || shouldCenterOnFirstLocation) {
            shouldCenterOnFirstLocation = false
            if (mMap?.containsLocation(x, y) == true) {
                centerOnPosition()
            }
        }
    }

    private fun removeCurrentMapView() {
        mapView?.destroy()
        presenter.removeMapView(mapView)
    }

    /**
     * Cleanup internal state and the [MapView].
     */
    private fun clearMap() {
        mMap = null
        removeCurrentMapView()
    }

    /**
     * Sets the map to configure the [MapView].
     *
     * @param map The new [Map] object
     */
    private fun configureMapView(mapView: MapView, map: Map) {
        mMap = map
        val tileSize = map.levelList.firstOrNull()?.tile_size?.x ?: return

        /* The magnifying factor - default to 1 */
        val factor = this.magnifyingFactor ?: 1

        val config = MapViewConfiguration(map.levelList.size, map.widthPx, map.heightPx, tileSize,
                makeTileStreamProvider(map)).setMaxScale(2f).setMagnifyingFactor(factor).setPadding(tileSize * 2).enableRotation()

        /* The MapView only supports one square tile size */
        mapView.configure(config)

        /* Map calibration */
        setMapViewBounds(mapView, map)

        /* The position + orientation reticule */
        try {
            val parent = positionMarker.parent as ViewGroup
            parent.removeView(positionMarker)
        } catch (e: Exception) {
            // don't care
        }

        mapView.addMarker(positionMarker, 0.0, 0.0, -0.5f, -0.5f)

        /* The MapView can have only one MarkerTapListener.
         * It dispatches the tap event to child layers.
         */
        mapView.setMarkerTapListener(object : MarkerTapListener {
            override fun onMarkerTap(view: View, x: Int, y: Int) {
                markerLayer.onMarkerTap(view, x, y)
                landmarkLayer.onMarkerTap(view, x, y)
            }
        })
    }

    private fun centerOnPosition() {
        mapView?.moveToMarker(positionMarker, true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(WAS_DISPLAYING_ORIENTATION, orientationEventManager.isStarted)
    }

    private fun setMapViewBounds(mapView: MapView, map: Map) {
        val mapBounds = map.mapBounds
        if (mapBounds != null) {
            mapView.defineBounds(mapBounds.X0,
                    mapBounds.Y0,
                    mapBounds.X1,
                    mapBounds.Y1)
        } else {
            mapView.defineBounds(0.0, 0.0, 1.0, 1.0)
        }
    }

    enum class SpeedUnit {
        KM_H, MPH
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     */
    interface RequestManageTracksListener {
        fun onRequestManageTracks()
    }

    /**
     * Same as [RequestManageTracksListener].
     */
    interface RequestManageMarkerListener {
        fun onRequestManageMarker(mapId: Int, marker: MarkerGson.Marker)
    }

    /**
     * As the `MapViewFragment` receives speed data, it can dispatch speed
     * information to other sub-components.
     */
    interface SpeedListener {
        /**
         * @param speed speed in meters per second
         * @param unit the desired unit to use for display
         */
        fun onSpeed(speed: Float, unit: SpeedUnit)

        fun toggleSpeedVisibility()

        fun hideSpeed()
    }

    companion object {
        const val TAG = "MapViewFragment"
        private const val WAS_DISPLAYING_ORIENTATION = "wasDisplayingOrientation"
    }
}

interface MapViewFragmentContract {
    interface Presenter {
        val androidView: android.view.View
        val view: View
        fun showMessage(msg: String)
        fun setPositionTouchListener(listener: MapViewFragmentPresenter.PositionTouchListener)
        fun setMapView(mapView: MapView)
        fun removeMapView(mapView: MapView?)
    }

    interface View {
        val speedIndicator: MapViewFragment.SpeedListener
        val distanceIndicator: DistanceLayer.DistanceListener
        val positionMarker: PositionOrientationMarker
    }
}