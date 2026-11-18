package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyraCyanPrimary
import com.example.ui.theme.MyraVioletGlow
import com.example.ui.theme.MyraVioletSecondary
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.DesignStudioState
import com.example.ui.viewmodel.DesignStudioViewModel
import com.example.util.ClipboardHelper
import kotlinx.coroutines.launch

@Composable
fun DesignStudioScreen(
    viewModel: DesignStudioViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val brandName by viewModel.brandName.collectAsStateWithLifecycle()
    val selectedStyle by viewModel.selectedStyle.collectAsStateWithLifecycle()
    val selectedColorScheme by viewModel.selectedColorScheme.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val studioState by viewModel.studioState.collectAsStateWithLifecycle()
    val savedDesigns by viewModel.savedDesigns.collectAsStateWithLifecycle()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Studio, 1 = Saved Concepts

    val exportPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = (studioState as? DesignStudioState.Ready)?.bitmap
        if (uri != null && bmp != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                scope.launch { snackbarHostState.showSnackbar("Logo PNG exported!") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.localizedMessage}") }
            }
        }
    }

    val exportSvgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri ->
        val svg = (studioState as? DesignStudioState.Ready)?.svgPreview
        if (uri != null && !svg.isNullOrBlank()) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(svg)
                }
                scope.launch { snackbarHostState.showSnackbar("Vector SVG exported!") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("SVG export failed: ${e.localizedMessage}") }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "Jarvis Design Studio",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "لوگو ڈیزائن، ویکٹر کانسپٹس اور برانڈ رنگ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                val tabs = listOf("Create Logo", "Saved Designs")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        if (activeTab == 0) {
            val styles = listOf(
                "Futuristic Tech",
                "Minimalist Luxury",
                "Geometric Neon",
                "Organic Emblem",
                "3D Glassmorphism"
            )
            val colorSchemes = listOf(
                "Electric Cyan & Cosmic Purple",
                "Obsidian & Champagne Gold",
                "Emerald & Slate",
                "Crimson & Charcoal",
                "Arctic Blue & Silver"
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = brandName,
                    onValueChange = viewModel::updateBrandName,
                    label = { Text("Brand / Company Name") },
                    placeholder = { Text("e.g. Nexus Dynamics") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("design_brand_name_input")
                )

                Text("Design Style", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styles.forEach { style ->
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = { viewModel.updateStyle(style) },
                            label = { Text(style) }
                        )
                    }
                }

                Text("Color Scheme", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colorSchemes.forEach { scheme ->
                        FilterChip(
                            selected = selectedColorScheme == scheme,
                            onClick = { viewModel.updateColorScheme(scheme) },
                            label = { Text(scheme) }
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = viewModel::updateNotes,
                    label = { Text("Concept Notes & Symbolism") },
                    placeholder = { Text("e.g. Abstract owl + neural circuit, sleek icon mark") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                val isBusy = studioState is DesignStudioState.GeneratingConcept ||
                    studioState is DesignStudioState.GeneratingImage

                Button(
                    onClick = { viewModel.generateLogoAndConcept(renderAiImage = true) },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MyraVioletSecondary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("generate_design_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate Concept & AI Logo", fontWeight = FontWeight.Bold)
                }

                // Studio Output Preview
                when (val state = studioState) {
                    is DesignStudioState.Idle -> Unit
                    is DesignStudioState.GeneratingConcept -> {
                        LoadingDesignCard("Crafting brand concept, typography & color kit...")
                    }
                    is DesignStudioState.GeneratingImage -> {
                        LoadingDesignCard("Rendering high-resolution AI logo with Nano Banana (may take 30-60s)...")
                    }
                    is DesignStudioState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    is DesignStudioState.Ready -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.bitmap != null) {
                                    Image(
                                        bitmap = state.bitmap.asImageBitmap(),
                                        contentDescription = "AI Generated Logo for $brandName",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(MaterialTheme.shapes.medium),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (!state.imageError.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "AI Image Render Notice: ${state.imageError}",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            OutlinedButton(onClick = { viewModel.renderBitmapForCurrentPrompt() }) {
                                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Retry AI Logo Render")
                                            }
                                        }
                                    }
                                }

                                if (state.tagline.isNotBlank()) {
                                    Text(
                                        text = "\"${state.tagline}\"",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MyraCyanPrimary
                                    )
                                }

                                SelectionContainer {
                                    Text(
                                        text = state.conceptDescription,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                Text(
                                    text = "Palette: ${state.paletteSummary}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MyraVioletGlow
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (state.bitmap != null) {
                                        Button(
                                            onClick = {
                                                val slug = brandName.lowercase().replace(Regex("[^a-z0-9]+"), "-").ifBlank { "jarvis-logo" }
                                                exportPngLauncher.launch("$slug-logo.png")
                                            },
                                            modifier = Modifier.weight(1f).testTag("export_logo_png_button")
                                        ) {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Export PNG")
                                        }
                                    }
                                    if (state.svgPreview.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                val slug = brandName.lowercase().replace(Regex("[^a-z0-9]+"), "-").ifBlank { "jarvis-logo" }
                                                exportSvgLauncher.launch("$slug.svg")
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Export SVG")
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        ClipboardHelper.writeText(context, "Jarvis Design Concept", state.conceptDescription)
                                        scope.launch { snackbarHostState.showSnackbar("ڈیزائن کانسپٹ کاپی ہو گیا!") }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Brand Concept")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            when (val designs = savedDesigns) {
                is DataUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is DataUiState.Error -> {
                    Text(designs.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                is DataUiState.Success -> {
                    if (designs.data.isEmpty()) {
                        EmptyStudioBox(
                            icon = Icons.Rounded.Brush,
                            title = "No Saved Designs Yet",
                            subtitle = "Create your first brand logo concept in the Create Logo tab."
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(designs.data, key = { it.id }) { concept ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.loadSavedConcept(concept)
                                            activeTab = 0
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(concept.brandName, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = "${concept.style} • ${concept.colorScheme}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteDesign(concept.id) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete design")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingDesignCard(label: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = MyraCyanPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
