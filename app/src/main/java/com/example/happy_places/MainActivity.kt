package com.example.happy_places

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume

data class LocationEntry(
    val id: String = UUID.randomUUID().toString(),
    val location: GeoPoint,
    val imagePath: String,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val CAMERA_PERMISSION_REQUEST_CODE = 2
    private val IMAGE_PICKER_REQUEST_CODE = 3

    // Add this property to store the selected image
    private var selectedImage: Bitmap? = null
    var currentLocation: GeoPoint? = null
    
    // Add this to store all entries
    private val _entries = mutableStateListOf<LocationEntry>()
    val entries: List<LocationEntry> = _entries

    // Add this property to store the pending note
    var pendingNote: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkLocationPermission()

        setContent {
            MaterialTheme {
                OsmMapScreen()
            }
        }
    }

    private fun checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            startImagePicker()
        }
    }

    private fun startImagePicker() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val chooser = Intent.createChooser(galleryIntent, "Select or take a picture")
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
        startActivityForResult(chooser, IMAGE_PICKER_REQUEST_CODE)
    }

    fun openImagePicker() {
        checkCameraPermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    recreate()
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startImagePicker()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICKER_REQUEST_CODE && resultCode == RESULT_OK) {
            try {
                when {
                    // Handle gallery image
                    data?.data != null -> {
                        val imageUri = data.data
                        selectedImage = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
                        // Now you can use selectedImage as needed
                        // For example, save it or display it
                    }
                    // Handle camera image
                    data?.extras?.get("data") != null -> {
                        selectedImage = data.extras?.get("data") as Bitmap
                        // Now you can use selectedImage as needed
                        // For example, save it or display it
                    }
                }

                // Example of what you could do with the image:
                if (selectedImage != null) {
                    // Save to internal storage
                    saveImageToInternalStorage(selectedImage!!)
                    // Or pass it to a function to upload/process it
                    handleCapturedImage(selectedImage!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Show error message to user
                AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Failed to process image")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun saveImageToInternalStorage(bitmap: Bitmap): String {
        val wrapper = ContextWrapper(applicationContext)
        var file = wrapper.getDir("images", Context.MODE_PRIVATE)
        file = File(file, "${UUID.randomUUID()}.jpg")

        try {
            val stream: OutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stream.flush()
            stream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return file.absolutePath
    }

    private fun handleCapturedImage(bitmap: Bitmap) {
        currentLocation?.let { location ->
            val imagePath = saveImageToInternalStorage(bitmap)
            val entry = LocationEntry(
                location = location,
                imagePath = imagePath,
                note = pendingNote
            )
            _entries.add(entry)
            pendingNote = "" // Reset pending note
        }
    }
}

@Composable
fun OsmMapScreen() {
    val context = LocalContext.current
    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showList by remember { mutableStateOf(false) }
    val mainActivity = context as MainActivity
    
    Row(modifier = Modifier.fillMaxSize()) {
        // Map section (left side, now can be full width)
        Box(
            modifier = Modifier
                .weight(if (showList) 0.7f else 1f)
                .fillMaxHeight()
        ) {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
                    Configuration.getInstance().userAgentValue = ctx.packageName

                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(16.0)
                        mapView = this
                    }
                },
                update = { view ->
                    view.overlays.clear()
                    
                    // Add current location marker
                    currentLocation?.let { location ->
                        val marker = Marker(view).apply {
                            position = location
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "You are here!"
                            setIcon(context.getDrawable(android.R.drawable.ic_menu_mylocation))
                        }
                        view.overlays.add(marker)
                    }
                    
                    // Add markers for all entries
                    mainActivity.entries.forEach { entry ->
                        val marker = Marker(view).apply {
                            position = entry.location
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = entry.note.takeIf { it.isNotEmpty() } ?: "Saved location"
                            snippet = java.text.SimpleDateFormat(
                                "dd.MM.yyyy HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(entry.timestamp))
                        }
                        view.overlays.add(marker)
                    }
                    
                    currentLocation?.let { view.controller.setCenter(it) }
                    view.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { showList = !showList },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(if (showList) "Hide List" else "Show List")
                }
                
                Button(
                    onClick = { saveLocation(currentLocation, context) }
                ) {
                    Text("Save Location")
                }
            }
        }

        // List section (right side, only shown when showList is true)
        if (showList) {
            Box(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
                    .padding(8.dp)
            ) {
                EntriesList(
                    entries = mainActivity.entries,
                    onEntryClick = { entry ->
                        mapView?.controller?.animateTo(entry.location)
                    }
                )
            }
        }
    }

    // Get current location
    LaunchedEffect(Unit) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val location = fused.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).await()

                location?.let {
                    currentLocation = GeoPoint(it.latitude, it.longitude)
                }
            } catch (e: Exception) {
                // Handle location error
                e.printStackTrace()
            }
        }
    }
}

@Composable
private fun EntriesList(
    entries: List<LocationEntry>,
    onEntryClick: (LocationEntry) -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn {
        items(entries) { entry ->
            EntryItem(
                entry = entry,
                onClick = { onEntryClick(entry) }
            )
        }
    }
}

@Composable
private fun EntryItem(
    entry: LocationEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Show thumbnail
            Image(
                bitmap = android.graphics.BitmapFactory.decodeFile(entry.imagePath)
                    .asImageBitmap(),
                contentDescription = "Location image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentScale = ContentScale.Crop
            )
            
            // Show note if present
            if (entry.note.isNotEmpty()) {
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            
            // Show location details
            Text(
                text = "Lat: ${entry.location.latitude.format(2)}\n" +
                       "Lon: ${entry.location.longitude.format(2)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Text(
                text = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(entry.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun saveLocation(cl: GeoPoint?, activity: Context?) {
    if (activity is MainActivity) {
        val input = EditText(activity).apply {
            hint = "Enter a note (optional)"
        }
        
        AlertDialog.Builder(activity)
            .setTitle("Save Location")
            .setView(input)
            .setPositiveButton("Add picture and save") { _, _ ->
                activity.currentLocation = cl
                activity.pendingNote = input.text.toString()
                activity.openImagePicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
