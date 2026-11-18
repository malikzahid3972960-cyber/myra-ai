package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MyraCyanPrimary
import com.example.ui.theme.MyraVioletSecondary
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.WebsiteCreatorViewModel
import com.example.ui.viewmodel.WebsiteGenState
import com.example.util.ClipboardHelper
import kotlinx.coroutines.launch

@Composable
fun WebsiteCreatorScreen(
    viewModel: WebsiteCreatorViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val businessName by viewModel.businessName.collectAsStateWithLifecycle()
    val industry by viewModel.industry.collectAsStateWithLifecycle()
    val requirements by viewModel.requirements.collectAsStateWithLifecycle()
    val colorPalette by viewModel.colorPalette.collectAsStateWithLifecycle()
    val htmlCode by viewModel.htmlCode.collectAsStateWithLifecycle()
    val cssCode by viewModel.cssCode.collectAsStateWithLifecycle()
    val jsCode by viewModel.jsCode.collectAsStateWithLifecycle()
    val genState by viewModel.genState.collectAsStateWithLifecycle()
    val savedWebsites by viewModel.savedWebsites.collectAsStateWithLifecycle()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Builder, 1 = Live Preview, 2 = Code Editor, 3 = Saved
    var codeFileTab by remember { mutableIntStateOf(0) } // 0 = HTML, 1 = CSS, 2 = JS
    var mobilePreviewFrame by remember { mutableStateOf(true) }

    val exportHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(viewModel.buildCombinedHtmlDocument())
                }
                scope.launch { snackbarHostState.showSnackbar("Website HTML bundle exported successfully!") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.localizedMessage}") }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header + Mode Switcher
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Jarvis Web Studio",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "رسپانسیو ویب سائٹ بنائیں، دیکھیں اور محفوظ کریں",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (htmlCode.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val slug = businessName.lowercase().replace(Regex("[^a-z0-9]+"), "-").ifBlank { "jarvis-website" }
                                exportHtmlLauncher.launch("$slug.html")
                            },
                            modifier = Modifier.testTag("export_website_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val tabs = listOf("Brief", "Preview", "Code", "Saved")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size)
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Status banner
        when (val state = genState) {
            is WebsiteGenState.Generating -> {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Jarvis HTML, CSS اور JavaScript تیار کر رہا ہے...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is WebsiteGenState.Error -> {
                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            is WebsiteGenState.Ready -> {
                Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.summary,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            is WebsiteGenState.Idle -> Unit
        }

        when (activeTab) {
            0 -> {
                // Requirements Form
                val palettes = listOf(
                    "Midnight Cyan & Violet",
                    "Minimalist Ivory & Slate",
                    "Emerald & Obsidian",
                    "Warm Terracotta & Cream",
                    "Corporate Navy & Gold"
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = businessName,
                        onValueChange = viewModel::updateBusinessName,
                        label = { Text("Business / Brand Name") },
                        placeholder = { Text("e.g. Horizon Coffee Roasters") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val pasted = ClipboardHelper.readText(context)
                                    if (!pasted.isNullOrEmpty()) {
                                        viewModel.updateBusinessName(pasted)
                                        scope.launch { snackbarHostState.showSnackbar("پیسٹ ہو گیا") }
                                    }
                                },
                                modifier = Modifier.testTag("website_business_name_paste_button")
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("website_business_name_input")
                    )

                    OutlinedTextField(
                        value = industry,
                        onValueChange = viewModel::updateIndustry,
                        label = { Text("Industry / Niche") },
                        placeholder = { Text("e.g. Specialty Cafe, AI SaaS, Architecture") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Visual Palette", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        palettes.forEach { palette ->
                            FilterChip(
                                selected = colorPalette == palette,
                                onClick = { viewModel.updateColorPalette(palette) },
                                label = { Text(palette) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = requirements,
                        onValueChange = viewModel::updateRequirements,
                        label = { Text("Website Requirements & Sections") },
                        placeholder = { Text("Hero banner, services grid, pricing cards, testimonials, contact form...") },
                        minLines = 4,
                        maxLines = 8,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val pasted = ClipboardHelper.readText(context)
                                    if (!pasted.isNullOrEmpty()) {
                                        viewModel.updateRequirements(if (requirements.isBlank()) pasted else requirements + "\n" + pasted)
                                        scope.launch { snackbarHostState.showSnackbar("پیسٹ ہو گیا") }
                                    }
                                },
                                modifier = Modifier.testTag("website_requirements_paste_button")
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("website_requirements_input")
                    )

                    Button(
                        onClick = {
                            viewModel.generateWebsite()
                            activeTab = 1
                        },
                        enabled = genState !is WebsiteGenState.Generating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MyraCyanPrimary,
                            contentColor = Color(0xFF050814)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("generate_website_button")
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Responsive Website", fontWeight = FontWeight.Bold)
                    }
                }
            }

            1 -> {
                // Live WebView Preview
                if (htmlCode.isBlank()) {
                    EmptyStudioBox(
                        icon = Icons.Rounded.Preview,
                        title = "No Website Generated Yet",
                        subtitle = "Fill out the Brief tab and tap Generate Responsive Website to preview it live here."
                    )
                } else {
                    val combinedHtml = remember(htmlCode, cssCode, jsCode) {
                        viewModel.buildCombinedHtmlDocument()
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = mobilePreviewFrame,
                                onClick = { mobilePreviewFrame = true },
                                label = { Text("Mobile Frame") },
                                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                            FilterChip(
                                selected = !mobilePreviewFrame,
                                onClick = { mobilePreviewFrame = false },
                                label = { Text("Full Width") },
                                leadingIcon = { Icon(Icons.Rounded.Laptop, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.5f)),
                                modifier = if (mobilePreviewFrame) {
                                    Modifier.widthIn(max = 400.dp).fillMaxSize()
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            ) {
                                HtmlWebViewPreview(htmlDocument = combinedHtml)
                            }
                        }
                    }
                }
            }

            2 -> {
                // Code Editor (HTML / CSS / JS)
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = codeFileTab) {
                        listOf("index.html", "styles.css", "app.js").forEachIndexed { idx, name ->
                            Tab(
                                selected = codeFileTab == idx,
                                onClick = { codeFileTab = idx },
                                text = { Text(name, style = MaterialTheme.typography.labelLarge) }
                            )
                        }
                    }

                    val currentCode = when (codeFileTab) {
                        0 -> htmlCode
                        1 -> cssCode
                        else -> jsCode
                    }
                    var codeEditorField by remember(codeFileTab) {
                        mutableStateOf(
                            TextFieldValue(
                                text = currentCode,
                                selection = TextRange(currentCode.length)
                            )
                        )
                    }
                    LaunchedEffect(currentCode, codeFileTab) {
                        if (currentCode != codeEditorField.text) {
                            codeEditorField = TextFieldValue(
                                text = currentCode,
                                selection = TextRange(currentCode.length)
                            )
                        }
                    }

                    fun applyEditorChange(updatedField: TextFieldValue) {
                        codeEditorField = updatedField
                        when (codeFileTab) {
                            0 -> viewModel.updateHtmlCode(updatedField.text)
                            1 -> viewModel.updateCssCode(updatedField.text)
                            else -> viewModel.updateJsCode(updatedField.text)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = { codeEditorField = ClipboardHelper.selectAll(codeEditorField) },
                            modifier = Modifier.testTag("code_select_all_button")
                        ) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select All")
                        }
                        TextButton(
                            onClick = {
                                val textToCopy = ClipboardHelper.selectedTextOrAll(codeEditorField)
                                if (textToCopy.isNotEmpty() && ClipboardHelper.writeText(context, "Jarvis Code", textToCopy)) {
                                    scope.launch { snackbarHostState.showSnackbar("کوڈ کاپی ہو گیا") }
                                }
                            },
                            modifier = Modifier.testTag("code_copy_button")
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                        TextButton(
                            onClick = {
                                val (cutText, updated) = ClipboardHelper.cutSelectionOrAll(codeEditorField)
                                if (cutText.isNotEmpty()) {
                                    ClipboardHelper.writeText(context, "Jarvis Code", cutText)
                                    applyEditorChange(updated)
                                    scope.launch { snackbarHostState.showSnackbar("کوڈ کٹ ہو گیا") }
                                }
                            },
                            modifier = Modifier.testTag("code_cut_button")
                        ) {
                            Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cut")
                        }
                        TextButton(
                            onClick = {
                                val pasted = ClipboardHelper.readText(context)
                                if (!pasted.isNullOrEmpty()) {
                                    applyEditorChange(ClipboardHelper.insertAtSelection(codeEditorField, pasted))
                                    scope.launch { snackbarHostState.showSnackbar("کلپ بورڈ سے پیسٹ ہو گیا") }
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("کلپ بورڈ خالی ہے") }
                                }
                            },
                            modifier = Modifier.testTag("code_paste_button")
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste")
                        }
                    }

                    OutlinedTextField(
                        value = codeEditorField,
                        onValueChange = { updated -> applyEditorChange(updated) },
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("website_code_editor")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveEditsLocally()
                                activeTab = 1
                            },
                            enabled = htmlCode.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("فون میں محفوظ کریں اور دیکھیں")
                        }
                    }
                }
            }

            else -> {
                // Saved Projects from Firestore
                when (val sites = savedWebsites) {
                    is DataUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is DataUiState.Error -> {
                        Text(sites.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                    is DataUiState.Success -> {
                        if (sites.data.isEmpty()) {
                            EmptyStudioBox(
                                icon = Icons.Rounded.Code,
                                title = "کوئی محفوظ ویب سائٹ نہیں",
                                subtitle = "تیار کی گئی ویب سائٹس خودکار طور پر آپ کے فون میں محفوظ ہو جاتی ہیں۔"
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(sites.data, key = { it.id }) { site ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.loadProject(site)
                                                activeTab = 1
                                            },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                        border = BorderStroke(1.dp, MyraVioletSecondary.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(site.businessName, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    text = "${site.industry.ifBlank { "Web Project" }} • ${site.colorPalette}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(onClick = { viewModel.deleteWebsite(site.id) }) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete website")
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlWebViewPreview(htmlDocument: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                isLongClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://myra.local/",
                htmlDocument,
                "text/html",
                "UTF-8",
                null
            )
        },
        onRelease = { webView ->
            webView.destroy()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
internal fun EmptyStudioBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MyraCyanPrimary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
