package com.example.freebook.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        AnnotationEntity::class,
        AudiobookGroupEntity::class,
        AudiobookTrackEntity::class,
        AudiobookMappingEntity::class,
        ChapterAlignmentEntity::class,
        PdfPageMetricEntity::class,
        AudiobookPlaybackStateEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class BookFreeDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun audiobookDao(): AudiobookDao

    companion object {
        private const val DB_NAME = "bookfree_relational.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readingCharOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN locatorJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN layoutType TEXT NOT NULL DEFAULT 'REFLOWABLE'")
                db.execSQL("ALTER TABLE books ADD COLUMN readingDirection TEXT NOT NULL DEFAULT 'LTR'")
                db.execSQL("ALTER TABLE books ADD COLUMN language TEXT NOT NULL DEFAULT 'en'")
                db.execSQL("ALTER TABLE annotations ADD COLUMN charOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE annotations ADD COLUMN quotedText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE annotations ADD COLUMN highlightColorName TEXT NOT NULL DEFAULT 'YELLOW'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE annotations ADD COLUMN endCharOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audiobook_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        isCollapsed INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audiobook_groups_bookId_orderIndex ON audiobook_groups(bookId, orderIndex)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audiobook_tracks (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        isCopiedLocally INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        clipStartMs INTEGER NOT NULL DEFAULT 0,
                        clipEndMs INTEGER NOT NULL DEFAULT 0,
                        orderIndex INTEGER NOT NULL,
                        audioHash TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(groupId) REFERENCES audiobook_groups(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audiobook_tracks_bookId_orderIndex ON audiobook_tracks(bookId, orderIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audiobook_tracks_groupId_orderIndex ON audiobook_tracks(groupId, orderIndex)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audiobook_mappings (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        bookChapterTitle TEXT NOT NULL DEFAULT '',
                        bookStartPageIndex INTEGER NOT NULL DEFAULT 0,
                        bookEndPageIndex INTEGER NOT NULL DEFAULT 0,
                        bookStartCharOffset INTEGER NOT NULL DEFAULT 0,
                        bookEndCharOffset INTEGER NOT NULL DEFAULT 0,
                        anchorsJson TEXT NOT NULL DEFAULT '[]',
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(groupId) REFERENCES audiobook_groups(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audiobook_mappings_bookId ON audiobook_mappings(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audiobook_mappings_groupId ON audiobook_mappings(groupId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_alignments (
                        mappingId TEXT NOT NULL PRIMARY KEY,
                        bookId TEXT NOT NULL,
                        provider TEXT NOT NULL DEFAULT 'NONE',
                        status TEXT NOT NULL DEFAULT 'NONE',
                        progressPercent INTEGER NOT NULL DEFAULT 0,
                        coverageRatio REAL NOT NULL DEFAULT 0.0,
                        avgConfidence REAL NOT NULL DEFAULT 0.0,
                        audioFingerprint TEXT NOT NULL DEFAULT '',
                        textHash TEXT NOT NULL DEFAULT '',
                        tokenizerVersion INTEGER NOT NULL DEFAULT 1,
                        wordCount INTEGER NOT NULL DEFAULT 0,
                        packedTimingsBlob BLOB,
                        errorMessage TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(mappingId) REFERENCES audiobook_mappings(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_alignments_bookId ON chapter_alignments(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_alignments_mappingId ON chapter_alignments(mappingId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pdf_page_metrics (
                        bookId TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        charCount INTEGER NOT NULL,
                        wordCount INTEGER NOT NULL,
                        weightedCharCount INTEGER NOT NULL,
                        isScanned INTEGER NOT NULL,
                        bodyStartChar INTEGER NOT NULL,
                        bodyEndChar INTEGER NOT NULL,
                        PRIMARY KEY(bookId, pageIndex),
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_page_metrics_bookId ON pdf_page_metrics(bookId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audiobook_playback_state (
                        bookId TEXT NOT NULL PRIMARY KEY,
                        lastTrackId TEXT NOT NULL DEFAULT '',
                        lastPositionMs INTEGER NOT NULL DEFAULT 0,
                        playbackSpeed REAL NOT NULL DEFAULT 1.0,
                        followSyncEnabled INTEGER NOT NULL DEFAULT 1,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audiobook_groups ADD COLUMN isCategory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audiobook_mappings ADD COLUMN trackId TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_audiobook_mappings_groupId")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audiobook_mappings_groupId_trackId ON audiobook_mappings(groupId, trackId)")
            }
        }

        @Volatile
        private var INSTANCE: BookFreeDatabase? = null

        fun getInstance(context: Context): BookFreeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookFreeDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
