package com.example.freebook.ui.reader

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import com.example.freebook.model.Book
import com.example.freebook.model.BookReadingDirection
import com.example.freebook.model.ReaderNavigationMode
import com.github.barteksc.pdfviewer.PDFView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private data class LoadedPdfPublication(
    val publication: Publication,
    val positions: List<Locator>
)

@OptIn(ExperimentalReadiumApi::class)
private fun buildPdfiumPreferences(
    navigationMode: ReaderNavigationMode,
    readingDirection: BookReadingDirection
): PdfiumPreferences {
    val isScroll = navigationMode == ReaderNavigationMode.VERTICAL_SCROLL
    val progression = when (readingDirection) {
        BookReadingDirection.RTL -> ReadingProgression.RTL
        BookReadingDirection.LTR -> ReadingProgression.LTR
    }
    return PdfiumPreferences(
        fit = if (isScroll) Fit.WIDTH else Fit.CONTAIN,
        pageSpacing = if (isScroll) 12.0 else 0.0,
        readingProgression = progression,
        scrollAxis = if (isScroll) Axis.VERTICAL else Axis.HORIZONTAL
    )
}

/**
 * Host component for fixed-layout PDF publications rendering via Readium's [PdfNavigatorFragment]
 * and the official `readium-adapter-pdfium` engine, with native [Locator] persistence and
 * live Paged Swipe / Continuous Scroll switching.
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun ReadiumPdfReaderHost(
    book: Book,
    navigationMode: ReaderNavigationMode,
    onDocumentReady: (totalPages: Int) -> Unit,
    onLocatorChanged: (locatorJson: String, pageNumber: Int, totalPages: Int, progressPercent: Float) -> Unit,
    onSingleTap: () -> Unit,
    onBindNavigator: ((jumpToPage: (Int) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val containerId = remember(book.id) { View.generateViewId() }

    val latestOnDocumentReady by rememberUpdatedState(onDocumentReady)
    val latestOnLocatorChanged by rememberUpdatedState(onLocatorChanged)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)
    val latestOnBindNavigator by rememberUpdatedState(onBindNavigator)

    val isVerticalScrollRef = remember(book.id) {
        AtomicBoolean(navigationMode == ReaderNavigationMode.VERTICAL_SCROLL)
    }

    val loadedPdfState = produceState<LoadedPdfPublication?>(initialValue = null, key1 = book.id) {
        value = withContext(Dispatchers.IO) {
            val file = File(book.bookFilePath)
            if (!file.exists()) return@withContext null

            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(
                contentResolver = context.contentResolver,
                httpClient = httpClient
            )
            val pdfFactory = PdfiumDocumentFactory(context)
            val opener = PublicationOpener(
                publicationParser = DefaultPublicationParser(
                    context = context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = pdfFactory
                )
            )

            val asset = assetRetriever.retrieve(file.toUrl()).getOrNull() ?: return@withContext null
            val publication = opener.open(asset, allowUserInteraction = false).getOrNull()
                ?: return@withContext null
            val positions = runCatching { publication.positions() }.getOrDefault(emptyList())
            LoadedPdfPublication(publication = publication, positions = positions)
        }
    }

    val loadedPdf = loadedPdfState.value
    val publication = loadedPdf?.publication
    val positions = loadedPdf?.positions.orEmpty()
    var activeFragment by remember(book.id) {
        mutableStateOf<PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>?>(null)
    }

    DisposableEffect(publication) {
        onDispose {
            publication?.let { runCatching { it.close() } }
        }
    }

    // Submit updated PdfiumPreferences whenever navigationMode (Swipe vs Scroll) or readingDirection changes
    LaunchedEffect(activeFragment, navigationMode, book.readingDirection) {
        isVerticalScrollRef.set(navigationMode == ReaderNavigationMode.VERTICAL_SCROLL)
        val frag = activeFragment ?: return@LaunchedEffect
        frag.submitPreferences(buildPdfiumPreferences(navigationMode, book.readingDirection))
    }

    // Bind page jumper & observe Readium's currentLocator StateFlow for progress persistence
    LaunchedEffect(activeFragment, loadedPdf) {
        val frag = activeFragment ?: return@LaunchedEffect
        val loaded = loadedPdf ?: return@LaunchedEffect
        val pub = loaded.publication
        val posList = loaded.positions
        val totalPages = posList.size.takeIf { it > 0 }
            ?: pub.metadata.numberOfPages?.takeIf { it > 0 }
            ?: 1

        latestOnBindNavigator?.invoke { targetPageOneBased ->
            val pageIdx = (targetPageOneBased - 1).coerceIn(0, totalPages - 1)
            val targetLocator = posList.getOrNull(pageIdx)
            if (targetLocator != null) {
                frag.go(targetLocator, animated = false)
            } else {
                pub.readingOrder.firstOrNull()?.let { link ->
                    frag.go(link, animated = false)
                }
            }
        }

        frag.currentLocator.collect { locator ->
            val pageNumber = (
                locator.locations.position
                    ?: locator.locations.totalProgression?.let { (it * totalPages).toInt() + 1 }
                    ?: 1
                ).coerceIn(1, totalPages)
            val progressPercent = if (totalPages > 1 && pageNumber >= totalPages) {
                100f
            } else {
                val fromLocator = locator.locations.totalProgression?.let { (it * 100.0).toFloat() }
                (fromLocator ?: (pageNumber.toFloat() / totalPages.toFloat() * 100f)).coerceIn(0.1f, 99.9f)
            }
            val serializedLocator = locator.toJSON().toString()
            latestOnLocatorChanged(serializedLocator, pageNumber, totalPages, progressPercent)
        }
    }

    if (publication != null) {
        val totalPages = positions.size.takeIf { it > 0 }
            ?: publication.metadata.numberOfPages?.takeIf { it > 0 }
            ?: 1

        DisposableEffect(book.id) {
            onDispose {
                val fm = activity.supportFragmentManager
                val tag = "readium_pdf_nav_${book.id}"
                val existing = fm.findFragmentByTag(tag)
                if (existing != null && !activity.isFinishing && !activity.isDestroyed) {
                    runCatching {
                        fm.commitNow(allowStateLoss = true) {
                            remove(existing)
                        }
                    }
                }
            }
        }

        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                FragmentContainerView(ctx).apply {
                    id = containerId
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                val fm = activity.supportFragmentManager
                val tag = "readium_pdf_nav_${book.id}"
                @Suppress("UNCHECKED_CAST")
                var fragment = fm.findFragmentByTag(tag) as? PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

                if (fragment == null && !activity.isFinishing && !activity.isDestroyed) {
                    isVerticalScrollRef.set(navigationMode == ReaderNavigationMode.VERTICAL_SCROLL)

                    val engineProvider = PdfiumEngineProvider(
                        listener = object : PdfiumEngineProvider.Listener {
                            override fun onConfigurePdfView(configurator: PDFView.Configurator) {
                                val pagedSwipe = !isVerticalScrollRef.get()
                                configurator
                                    .pageSnap(pagedSwipe)
                                    .autoSpacing(pagedSwipe)
                                    .pageFling(pagedSwipe)
                            }
                        }
                    )
                    val navigatorFactory = PdfNavigatorFactory(
                        publication = publication,
                        pdfEngineProvider = engineProvider
                    )

                    val savedLocatorFromJson = book.locatorJson.takeIf { it.isNotBlank() }?.let { rawJson ->
                        runCatching { Locator.fromJSON(JSONObject(rawJson)) }.getOrNull()
                    }
                    val fallbackPageIdx = book.currentPageIndex.coerceIn(0, totalPages - 1)
                    val resolvedInitialLocator = savedLocatorFromJson
                        ?: positions.getOrNull(fallbackPageIdx)
                        ?: publication.readingOrder.firstOrNull()?.let { publication.locatorFromLink(it) }

                    val initialPrefs = buildPdfiumPreferences(navigationMode, book.readingDirection)

                    val fragmentFactory = navigatorFactory.createFragmentFactory(
                        initialLocator = resolvedInitialLocator,
                        initialPreferences = initialPrefs,
                        listener = object : PdfNavigatorFragment.Listener {}
                    )

                    @Suppress("UNCHECKED_CAST")
                    fragment = fragmentFactory.instantiate(
                        activity.classLoader,
                        PdfNavigatorFragment::class.java.name
                    ) as PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

                    fm.commitNow(allowStateLoss = true) {
                        replace(view.id, fragment, tag)
                    }

                    fragment.addInputListener(object : InputListener {
                        override fun onTap(event: TapEvent): Boolean {
                            latestOnSingleTap()
                            return false
                        }

                        override fun onDrag(event: DragEvent): Boolean = false
                        override fun onKey(event: KeyEvent): Boolean = false
                    })
                }

                if (activeFragment !== fragment && fragment != null) {
                    activeFragment = fragment
                    latestOnDocumentReady(totalPages)
                }
            }
        )
    }
}
