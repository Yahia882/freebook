package com.example.freebook.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, AnnotationEntity::class],
    version = 4,
    exportSchema = false
)
abstract class BookFreeDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun annotationDao(): AnnotationDao

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

        @Volatile
        private var INSTANCE: BookFreeDatabase? = null

        fun getInstance(context: Context): BookFreeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookFreeDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
