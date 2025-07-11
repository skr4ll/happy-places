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
import android.view.MotionEvent
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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

    private var selectedImage: Bitmap? = null
    var currentLocation: GeoPoint? = null
    
    private val _entries = mutableStateListOf<LocationEntry>()
    val entries: List<LocationEntry> = _entries

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
                    data?.data != null -> {
                        val imageUri = data.data
                        selectedImage = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)                        
                    }
                    data?.extras?.get("data") != null -> {
                        selectedImage = data.extras?.get("data") as Bitmap                       
                    }
                }

                if (selectedImage != null) {
                    saveImageToInternalStorage(selectedImage!!)
                    handleCapturedImage(selectedImage!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            pendingNote = ""
        }
    }

    fun updateEntryNote(newNote: String, entry: LocationEntry) {
        val index = _entries.indexOfFirst { it.id == entry.id }
        if (index != -1) {
            _entries[index] = entry.copy(note = newNote)
        }
    }
    
    fun deleteEntry(entry: LocationEntry) {
        _entries.removeAll { it.id == entry.id }
    }
}

@Composable
fun OsmMapScreen() {
    val context = LocalContext.current
    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showList by remember { mutableStateOf(false) }
    val mainActivity = context as MainActivity
    var selectedLocation by remember { mutableStateOf<GeoPoint?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
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

                        overlays.add(object : org.osmdroid.views.overlay.Overlay() {

                            override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
                                val proj = mapView.projection
                                val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt())
                                selectedLocation = geoPoint as GeoPoint
  
                                mapView.overlays.removeAll { it is Marker && it.title == "Selected Location" }

                                val marker = Marker(mapView).apply {
                                    position = geoPoint
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = "Selected Location"
                                    icon = context.getDrawable(android.R.drawable.ic_menu_add)
                                }
                                mapView.overlays.add(marker)
                                mapView.invalidate()
                                return true
                            }
                        })
                    }
                },
                update = { view ->
                    view.overlays.clear()
                    currentLocation?.let { location ->
                        val marker = Marker(view).apply {
                            position = location
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Du bist hier!"
                            setIcon(context.getDrawable(android.R.drawable.ic_menu_mylocation))
                        }
                        view.overlays.add(marker)
                    }

                    selectedLocation?.let { location ->
                        val marker = Marker(view).apply {
                            position = location
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Selected Location"
                            icon = context.getDrawable(android.R.drawable.ic_menu_add)
                        }
                        view.overlays.add(marker)
                    }

                    mainActivity.entries.forEach { entry ->
                        val marker = Marker(view).apply {
                            position = entry.location
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = entry.note.takeIf { it.isNotEmpty() } ?: "Saved location"
                            snippet = java.text.SimpleDateFormat(
                                "dd.MM.yyyy HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(entry.timestamp))
                            icon = context.getDrawable(android.R.drawable.ic_menu_save)
                        }
                        view.overlays.add(marker)
                    }

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
                    Text(if (showList) "List ausbl." else "Liste zeigen")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { saveLocation(currentLocation, context, "current") }
                    ) {
                        Text("Aktuellen speichern")
                    }

                    Button(
                        onClick = {
                            if (selectedLocation != null) {
                                saveLocation(selectedLocation, context, "selected")
                            }
                        },
                        enabled = selectedLocation != null
                    ) {
                        Text("Markierten speichern")
                    }
                }
            }
        }

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
                    },
                    onEditEntry = { newNote, entry ->
                        mainActivity.updateEntryNote(newNote, entry)
                    },
                    onDeleteEntry = { entry ->
                        mainActivity.deleteEntry(entry)
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
                    mapView?.controller?.setCenter(currentLocation)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
private fun EntriesList(
    entries: List<LocationEntry>,
    onEntryClick: (LocationEntry) -> Unit,
    onEditEntry: (String, LocationEntry) -> Unit,
    onDeleteEntry: (LocationEntry) -> Unit
) {
    LazyColumn {
        items(entries) { entry ->
            EntryItem(
                entry = entry,
                onClick = { onEntryClick(entry) },
                onEdit = { newNote -> onEditEntry(newNote, entry) },
                onDelete = { onDeleteEntry(entry) }
            )
        }
    }
}

@Composable
private fun EntryItem(
    entry: LocationEntry,
    onClick: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editedNote by remember { mutableStateOf(entry.note) }
    
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

            if (entry.note.isNotEmpty()) {
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Bearbeiten")
                }
                
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Löschen")
                }
            }
        }
    }
    
    if (showEditDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Note") },
            text = {
                TextField(
                    value = editedNote,
                    onValueChange = { editedNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEdit(editedNote)
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun saveLocation(cl: GeoPoint?, activity: Context?, type: String) {
    if (activity is MainActivity) {
        val input = EditText(activity).apply {
            hint = "Notiz hinzufügen"
        }
        
        AlertDialog.Builder(activity)
            .setTitle("Ort speichern")
            .setView(input)
            .setPositiveButton("Bild hinzufügen und speichern") { _, _ ->
                activity.currentLocation = cl
                activity.pendingNote = input.text.toString()
                activity.openImagePicker()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
