package com.example.freebook.data

import android.content.Context
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.CoverStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PersistedAppState(
    val isDarkTheme: Boolean,
    val books: List<Book>,
    val annotations: List<BookAnnotation>
)

object BookRepository {

    private const val STORAGE_FILE = "bookfree_state_v1.json"

    fun loadState(context: Context): PersistedAppState {
        val file = File(context.filesDir, STORAGE_FILE)
        if (!file.exists()) {
            val seeded = createDefaultState()
            saveState(context, seeded)
            return seeded
        }

        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val isDark = root.optBoolean("isDarkTheme", true)

            val booksArray = root.optJSONArray("books") ?: JSONArray()
            val books = mutableListOf<Book>()
            for (i in 0 until booksArray.length()) {
                val obj = booksArray.getJSONObject(i)
                val pagesJson = obj.optJSONArray("pages") ?: JSONArray()
                val pages = mutableListOf<String>()
                for (p in 0 until pagesJson.length()) {
                    pages.add(pagesJson.getString(p))
                }
                books.add(
                    Book(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        author = obj.optString("author", ""),
                        coverStyle = runCatching {
                            CoverStyle.valueOf(obj.optString("coverStyle", CoverStyle.DEFAULT_EDITORIAL.name))
                        }.getOrDefault(CoverStyle.DEFAULT_EDITORIAL),
                        coverImagePath = obj.optString("coverImagePath", "").takeIf { it.isNotBlank() },
                        pages = pages.ifEmpty { listOf("") },
                        currentPageIndex = obj.optInt("currentPageIndex", 0),
                        displayPageOffset = obj.optInt("displayPageOffset", 1),
                        displayTotalPages = obj.optInt("displayTotalPages", pages.size.coerceAtLeast(1)),
                        isFinished = obj.optBoolean("isFinished", false),
                        isStarted = obj.optBoolean("isStarted", false),
                        isCustomNoteEntryOnly = obj.optBoolean("isCustomNoteEntryOnly", false),
                        fixedProgressPercent = if (obj.has("fixedProgressPercent") && !obj.isNull("fixedProgressPercent")) {
                            obj.getInt("fixedProgressPercent")
                        } else {
                            null
                        }
                    )
                )
            }

            val annotationsArray = root.optJSONArray("annotations") ?: JSONArray()
            val annotations = mutableListOf<BookAnnotation>()
            for (i in 0 until annotationsArray.length()) {
                val obj = annotationsArray.getJSONObject(i)
                annotations.add(
                    BookAnnotation(
                        id = obj.getString("id"),
                        bookId = obj.getString("bookId"),
                        type = runCatching {
                            AnnotationType.valueOf(obj.optString("type", AnnotationType.NOTE.name))
                        }.getOrDefault(AnnotationType.NOTE),
                        pageNumber = obj.optInt("pageNumber", 1),
                        text = obj.getString("text"),
                        timestampLabel = obj.optString("timestampLabel", "Added today"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }

            if (books.isEmpty()) {
                createDefaultState()
            } else {
                PersistedAppState(
                    isDarkTheme = isDark,
                    books = books,
                    annotations = annotations
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            createDefaultState()
        }
    }

    fun saveState(context: Context, state: PersistedAppState) {
        try {
            val root = JSONObject()
            root.put("isDarkTheme", state.isDarkTheme)

            val booksArray = JSONArray()
            for (book in state.books) {
                val obj = JSONObject()
                obj.put("id", book.id)
                obj.put("title", book.title)
                obj.put("author", book.author)
                obj.put("coverStyle", book.coverStyle.name)
                obj.put("coverImagePath", book.coverImagePath ?: "")
                val pagesJson = JSONArray()
                book.pages.forEach { pagesJson.put(it) }
                obj.put("pages", pagesJson)
                obj.put("currentPageIndex", book.currentPageIndex)
                obj.put("displayPageOffset", book.displayPageOffset)
                obj.put("displayTotalPages", book.displayTotalPages)
                obj.put("isFinished", book.isFinished)
                obj.put("isStarted", book.isStarted)
                obj.put("isCustomNoteEntryOnly", book.isCustomNoteEntryOnly)
                if (book.fixedProgressPercent != null) {
                    obj.put("fixedProgressPercent", book.fixedProgressPercent)
                }
                booksArray.put(obj)
            }
            root.put("books", booksArray)

            val annotationsArray = JSONArray()
            for (ann in state.annotations) {
                val obj = JSONObject()
                obj.put("id", ann.id)
                obj.put("bookId", ann.bookId)
                obj.put("type", ann.type.name)
                obj.put("pageNumber", ann.pageNumber)
                obj.put("text", ann.text)
                obj.put("timestampLabel", ann.timestampLabel)
                obj.put("createdAt", ann.createdAt)
                annotationsArray.put(obj)
            }
            root.put("annotations", annotationsArray)

            val file = File(context.filesDir, STORAGE_FILE)
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createDefaultState(): PersistedAppState {
        val bookRoomId = "book_room_of_ones_own"
        val bookMeditationsId = "book_meditations"
        val bookTeaId = "book_the_book_of_tea"

        val roomPages = listOf(
            "But, you may say, we asked you to speak about women and fiction—what has that got to do with a room of one's own? I will try to explain.\n\nWhen you asked me to speak about women and fiction I sat down on the banks of a river and began to wonder what the words meant. They might mean simply a few remarks about Fanny Burney; a few more about Jane Austen; a tribute to the Brontës and a sketch of Haworth Parsonage under snow.",
            "All I could do was to offer you an opinion upon one minor point—a woman must have money and a room of her own if she is to write fiction; and that, as you will see, leaves the great problem of the true nature of woman and the true nature of fiction unsolved.\n\nThought—to call it by a prouder name than it deserved—had let its line down into the stream. It swayed, minute after minute, hither and thither among the reflections and the weeds, letting the water lift it and sink it, until—you know the little tug—the sudden conglomeration of an idea at the end of one's line.",
            "Here then was I (call me Mary Beton, Mary Seton, Mary Carmichael or by any name you please—it is not a matter of any importance) sitting on the banks of a river a week or two ago in fine October weather, lost in thought.\n\nThat collar I have spoken of, women and fiction, the need of coming to some conclusion on a subject that raises all sorts of prejudices and passions, bowed my head to the ground. To the right and left bushes of some sort, golden and crimson, glowed with the colour, even it seemed burnt with the heat, of fire.",
            "Intellectual freedom depends upon material things. Poetry depends upon intellectual freedom. And women have always been poor, not for two hundred years merely, but from the beginning of time.\n\nLock up your libraries if you like; but there is no gate, no lock, no bolt that you can set upon the freedom of my mind."
        )

        val meditationsPages = listOf(
            "Besides the common popular religion, the belief of the masses, there was another which prevailed among the priests and among the educated. The primary doctrine of this esoteric religion was the real essential unity of the Divine Nature. The sacred texts, known only to the priests and to the initiated, taught that there was a single Being, \"the sole producer of all things both in heaven and earth, himself not produced of any,\" \"the only true living God, self-originated,\" \"who exists from the beginning,\" \"who has made all things, but has not himself been made.\" This Being seems never to have been represented by any material, even symbolical, form. It is thought that he had no name, or, if he had, that it must have been unlawful to pronounce or write it. He was a pure spirit, perfect in every respect—all-wise, almighty, supremely good. It is of him that the Egyptian poets use such expressions as the following: \"He is not graven in marble; he is not beheld; his abode is not known; no shrine is found with painted figures of him; there is no building that can contain him;\" and, again: \"Unknown is his name in heaven; he doth not manifest his forms; vain are all representations;\" and yet again: \"His commencement is from the beginning; he is the God who has existed from old time; there is no God without him; no mother bore him; no father hath begotten him; he",
            "When you wake up in the morning, tell yourself: The people I deal with today will be meddling, ungrateful, arrogant, dishonest, jealous, and surly. They are like this because they can't tell good from evil.\n\nBut I have seen the beauty of good, and the ugliness of evil, and have recognized that the wrongdoer has a nature related to my own—not of the same blood or birth, but the same mind, and possessing a share of the divine.",
            "You have power over your mind—not outside events. Realize this, and you will find strength.\n\nDwell on the beauty of life. Watch the stars, and see yourself running with them. Think constantly on the changes of the elements into each other, for such thoughts wash away the dust of earthly life.",
            "Very little is needed to make a happy life; it is all within yourself, in your way of thinking.\n\nAccept the things to which fate binds you, and love the people with whom fate brings you together, but do so with all your heart."
        )

        val bookOfTeaPages = listOf(
            "The essence of the art lies in the quiet perception of the everyday. In the stillness of the tea-room, where shadows converse with the flickering light, one discovers harmony not in grand monumentality, but in the deliberate balance of unadorned space. The masters of the tea ceremony understood that perfection is an illusion; true grace is found in the asymmetrical, the fleeting, and the subtle imperfections that reflect life itself.\n\nWhen we attune ourselves to this silent rhythm, the noisy cadence of the world recedes. Every gesture—the lifting of a ceramic bowl, the gentle rustle of bamboo, the aroma of steamed leaves—becomes a contemplation of existence. In simplicity, nothing is trivial; the ordinary object, freed from the burden of display, reveals its inherent dignity and invites the observer into profound communion with time.\n\nThus beauty manifests not as an ornament forced upon reality, but as reality unmasked in silence...",
            "Teaism is a cult founded on the adoration of the beautiful among the sordid facts of everyday existence. It inculcates purity and harmony, the mystery of mutual charity, the romanticism of the social order.\n\nIt is essentially a worship of the Imperfect, as it is a tender attempt to accomplish something possible in this impossible thing we know as life.",
            "The Philosophy of Tea is not mere aestheticism in the ordinary acceptance of the term, for it expresses conjointly with ethics and religion our whole point of view about man and nature.\n\nIt is hygiene, for it enforces cleanliness; it is economics, for it shows comfort in simplicity rather than in the complex and costly; it is moral geometry, inasmuch as it defines our sense of proportion to the universe.",
            "Those who cannot feel the littleness of great things in themselves are apt to overlook the greatness of little things in others.\n\nIn the liquid amber within the ivory-porcelain, the initiated may touch the sweet reticence of Confucius, the piquancy of Laotse, and the ethereal aroma of Sakyamuni himself."
        )

        val books = listOf(
            Book(
                id = bookRoomId,
                title = "A Room Of One’s Own",
                author = "Virginia Woolf",
                coverStyle = CoverStyle.ARCH_LIGHT,
                pages = roomPages,
                currentPageIndex = 0,
                displayPageOffset = 24,
                displayTotalPages = 112,
                isFinished = false,
                isStarted = true,
                fixedProgressPercent = 42
            ),
            Book(
                id = bookMeditationsId,
                title = "Meditations",
                author = "Marcus Aurelius",
                coverStyle = CoverStyle.CIRCLE_PEDESTAL,
                pages = meditationsPages,
                currentPageIndex = 0,
                displayPageOffset = 58,
                displayTotalPages = 240,
                isFinished = false,
                isStarted = false
            ),
            Book(
                id = bookTeaId,
                title = "The Book Of Tea",
                author = "Okakura Kakuzo",
                coverStyle = CoverStyle.OVAL_DARK,
                pages = bookOfTeaPages,
                currentPageIndex = 0,
                displayPageOffset = 51,
                displayTotalPages = 512,
                isFinished = true,
                isStarted = true
            )
        )

        val annotations = listOf(
            // Seeded annotations for The Book Of Tea matching Screen 3 (Book Notes Detail Screen)
            BookAnnotation(
                id = "ann_tea_1",
                bookId = bookTeaId,
                type = AnnotationType.HIGHLIGHT,
                pageNumber = 12,
                text = "always smile",
                timestampLabel = "Added today",
                createdAt = 1000L
            ),
            BookAnnotation(
                id = "ann_tea_2",
                bookId = bookTeaId,
                type = AnnotationType.NOTE,
                pageNumber = 18,
                text = "Life is good if you feel good",
                timestampLabel = "Added today",
                createdAt = 2000L
            ),
            BookAnnotation(
                id = "ann_tea_3",
                bookId = bookTeaId,
                type = AnnotationType.HIGHLIGHT,
                pageNumber = 24,
                text = "always be optimistic",
                timestampLabel = "Added today",
                createdAt = 3000L
            ),
            // Seeded annotations for A Room Of One's Own
            BookAnnotation(
                id = "ann_room_1",
                bookId = bookRoomId,
                type = AnnotationType.HIGHLIGHT,
                pageNumber = 14,
                text = "A woman must have money and a room of her own if she is to write fiction.",
                timestampLabel = "Added today",
                createdAt = 4000L
            ),
            BookAnnotation(
                id = "ann_room_2",
                bookId = bookRoomId,
                type = AnnotationType.NOTE,
                pageNumber = 24,
                text = "There is no gate, no lock, no bolt that you can set upon the freedom of my mind.",
                timestampLabel = "Added today",
                createdAt = 5000L
            )
        )

        return PersistedAppState(
            isDarkTheme = true,
            books = books,
            annotations = annotations
        )
    }
}
