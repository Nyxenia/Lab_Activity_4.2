package com.example.labactivity42

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var carMarker: Marker? = null

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            enableMyLocationOnMap()
            showUserCurrentLocation()
        } else {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val btnParked = findViewById<Button>(R.id.btnParked)
        btnParked.setOnClickListener {
            handleParkedButtonClick()
        }

        // Post permission check after layout completes to avoid freezing System UI during map load
        mainView.post {
            if (!hasLocationPermission()) {
                checkAndRequestPermissions()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Step 10: Restore saved location from SharedPreferences
        val savedLatLng = restoreLocation()
        if (savedLatLng != null) {
            updateCarMarker(savedLatLng)
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(savedLatLng, 15f))
        }

        if (hasLocationPermission()) {
            enableMyLocationOnMap()
            showUserCurrentLocation(zoomIfNoCar = savedLatLng == null)
        }
    }

    private fun handleParkedButtonClick() {
        if (!hasLocationPermission()) {
            checkAndRequestPermissions()
            return
        }

        fetchCurrentLocation { latLng ->
            updateCarMarker(latLng)
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            saveLocation(latLng)
            Toast.makeText(this, R.string.parked_location_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        if (hasLocationPermission()) {
            enableMyLocationOnMap()
            showUserCurrentLocation()
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_permission_rationale_title)
                .setMessage(R.string.location_permission_rationale_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions()
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
                }
                .create()
                .show()
        } else {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun enableMyLocationOnMap() {
        if (hasLocationPermission()) {
            try {
                mMap?.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    private fun showUserCurrentLocation(zoomIfNoCar: Boolean = true) {
        fetchCurrentLocation { latLng ->
            if (zoomIfNoCar && carMarker == null) {
                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            }
        }
    }

    private fun fetchCurrentLocation(onLocationReceived: (LatLng) -> Unit) {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        onLocationReceived(LatLng(location.latitude, location.longitude))
                    } else {
                        // If lastLocation is null (common on emulators), request current location update
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener { curLoc ->
                                if (curLoc != null) {
                                    onLocationReceived(LatLng(curLoc.latitude, curLoc.longitude))
                                } else {
                                    Toast.makeText(
                                        this,
                                        "Unable to fetch location. Please check GPS settings.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Location error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun updateCarMarker(location: LatLng) {
        val carIcon = bitmapDescriptorFromVector(this, R.drawable.ic_car)
        val markerOptions = MarkerOptions()
            .position(location)
            .title(getString(R.string.car_parked_title))

        if (carIcon != null) {
            markerOptions.icon(carIcon)
        }

        carMarker?.remove()
        carMarker = mMap?.addMarker(markerOptions)
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // Step 9: Save location to SharedPreferences
    private fun saveLocation(latLng: LatLng) =
        getPreferences(MODE_PRIVATE)?.edit()?.apply {
            putString("latitude", latLng.latitude.toString())
            putString("longitude", latLng.longitude.toString())
            apply()
        }

    // Step 10: Restore location from SharedPreferences
    private fun restoreLocation(): LatLng? {
        val sharedPreferences = getPreferences(MODE_PRIVATE) ?: return null
        val latitude = sharedPreferences.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val longitude = sharedPreferences.getString("longitude", null)?.toDoubleOrNull() ?: return null
        return LatLng(latitude, longitude)
    }
}
