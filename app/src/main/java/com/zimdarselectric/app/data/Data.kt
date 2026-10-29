package com.zimdarselectric.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Quote status values. Draft, Sent and Approved quotes put their materials on hold. */
object Status {
    const val DRAFT = "DRAFT"
    const val SENT = "SENT"
    const val APPROVED = "APPROVED"
    const val DONE = "DONE"
    const val DECLINED = "DECLINED"
    val holding = setOf(DRAFT, SENT, APPROVED)
    val all = listOf(DRAFT, SENT, APPROVED, DONE, DECLINED)
}

object JobType {
    /** Capital improvement under NY sales tax law: no sales tax on the bill, customer signs ST-124. */
    const val INSTALL = "INSTALL"
    /** Repair / maintenance: sales tax charged on the whole bill. */
    const val REPAIR = "REPAIR"
}

@Entity
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity
data class Material(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val spec: String = "",
    /** "ea", "ft", "box", "roll"… */
    val unit: String = "ea",
    val onHand: Double = 0.0,
    /** Warn when the amount free to use drops below this. */
    val alertLevel: Double = 0.0,
    /** What one unit costs you, including the sales tax you paid at the store. */
    val cost: Double = 0.0,
    val location: String = "Truck",
    /** Your own photo of the item, stored on the phone. Blank = no photo yet. */
    @ColumnInfo(defaultValue = "") val photoPath: String = "",
    /** Pieces in one box or pack (e.g. 100 connectors). 0 = not bought by the box. Stock and cost are always per piece. */
    @ColumnInfo(defaultValue = "0") val packSize: Double = 0.0,
    /** Barcodes (UPC) scanned for this item, comma-separated. */
    @ColumnInfo(defaultValue = "") val barcodes: String = "",
) {
    fun hasBarcode(code: String) = code.isNotBlank() && barcodes.split(',').any { it == code }
    fun withBarcodes(codes: List<String>) = copy(barcodes = (barcodes.split(',') + codes).map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(","))
}

@Entity
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val title: String = "",
    val customerId: Long? = null,
    val customerName: String = "",
    val site: String = "",
    val jobType: String = JobType.INSTALL,
    val scheduled: String = "",
    val status: String = Status.DRAFT,
    val laborHours: Double = 0.0,
    val laborRate: Double = 95.0,
    val markupPct: Double = 20.0,
    val taxRatePct: Double = 8.75,
    val miles: Double = 0.0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val paidAmount: Double = 0.0,
    val paidAt: Long? = null,
    // Customer e-signature
    /** Private token in the signing link. Blank = not sent for signature. */
    @ColumnInfo(defaultValue = "") val signToken: String = "",
    @ColumnInfo(defaultValue = "") val signUrl: String = "",
    val sentAt: Long? = null,
    val viewedAt: Long? = null,
    val signedAt: Long? = null,
    @ColumnInfo(defaultValue = "") val signerName: String = "",
    @ColumnInfo(defaultValue = "") val signerIp: String = "",
    /** The customer's signature image, stored on the phone. */
    @ColumnInfo(defaultValue = "") val signaturePath: String = "",
) {
    val awaitingSignature: Boolean get() = signToken.isNotBlank() && signedAt == null
    /** Edited after the link went out, so the customer is looking at an older version. */
    val changedSinceSent: Boolean get() = sentAt != null && updatedAt > sentAt + 1500
}

@Entity
data class QuoteLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoteId: Long,
    val materialId: Long? = null,
    val name: String,
    val unit: String = "ea",
    val qty: Double = 1.0,
    /** Your cost per unit. The customer price adds the quote's markup. */
    val unitCost: Double = 0.0,
)

@Entity
data class StockMove(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val materialId: Long,
    val delta: Double,
    val reason: String,
    val quoteId: Long? = null,
    val at: Long = System.currentTimeMillis(),
)

@Entity
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long = System.currentTimeMillis(),
    val vendor: String,
    val amount: Double,
    val category: String,
    val note: String = "",
    /** What was on the receipt, as a JSON list of {name, qty, each, paidEach, sku, model}. Blank if unknown. */
    @ColumnInfo(defaultValue = "") val items: String = "",
)

/** A copy of a receipt (photo or PDF) stored on the phone, attached to an expense. */
@Entity
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val fileName: String,
    val mime: String,
    /** Absolute path inside the app's private storage. */
    val path: String,
    val addedAt: Long = System.currentTimeMillis(),
)

const val CHARGE_NOTE = "Service call \$125 covers the trip and the first 30 minutes on site. After that, \$95 per hour in 30-minute blocks. Flat-price work is quoted before any work starts, and nothing is done without your OK."

@Entity
data class Settings(
    @PrimaryKey val id: Int = 1,
    val businessName: String = "Zimdars Electric LLC",
    val phone: String = "",
    val email: String = "",
    val address: String = "6221 State Route 26, Rome, NY 13440",
    val laborRate: Double = 95.0,
    val markupPct: Double = 20.0,
    val repairTaxPct: Double = 8.75,
    val savingsPct: Double = 20.0,
    val taxSetAsidePct: Double = 25.0,
    val suppliesPct: Double = 3.0,
    val mpg: Double = 14.0,
    val gasPrice: Double = 3.29,
    val nextQuoteNo: Int = 1,
    val warranty: String = "1 year on workmanship. Materials carry the manufacturer's warranty. Not covered: misuse, work by others, surges, lightning or acts of nature.",
    val paymentTerms: String = "Payment due when the job is complete.",
    val license: String = "",
    val insurer: String = "",
    val quoteValidDays: Int = 30,
    /** How you charge, shown to every customer on quotes and the signing page. */
    @ColumnInfo(defaultValue = CHARGE_NOTE) val chargeNote: String = CHARGE_NOTE,
    /** Key that lets this phone create signing links. Kept only on the phone. */
    @ColumnInfo(defaultValue = "") val signKey: String = "",
)

/** Schedule C expense categories, so receipts land on the right tax line. */
val ExpenseCategories = listOf(
    "Materials + supplies (line 22)",
    "Tools under $2,500 (line 22)",
    "Equipment over $2,500 (line 13)",
    "Truck / mileage (line 9)",
    "Contract labor (line 11)",
    "Insurance (line 15)",
    "Licenses + permits (line 23)",
    "Advertising (line 8)",
    "Professional fees (line 17)",
    "Software + education (line 27a)",
    "Phone + internet (line 25)",
    "Work gear (line 27a)",
    "Bank + card fees (line 10)",
    "Rent (line 20)",
    "Meals 50% (line 24b)",
    "Start-up costs",
    "Other (line 27a)",
)

@Dao
interface AppDao {
    @Query("SELECT * FROM Customer ORDER BY name COLLATE NOCASE")
    fun customers(): Flow<List<Customer>>

    @Insert suspend fun insertCustomer(c: Customer): Long
    @Update suspend fun updateCustomer(c: Customer)
    @Delete suspend fun deleteCustomer(c: Customer)

    @Query("SELECT * FROM Material ORDER BY name COLLATE NOCASE")
    fun materials(): Flow<List<Material>>

    @Query("SELECT * FROM Material WHERE id = :id")
    suspend fun material(id: Long): Material?

    @Insert suspend fun insertMaterial(m: Material): Long
    @Update suspend fun updateMaterial(m: Material)
    @Delete suspend fun deleteMaterial(m: Material)

    @Query("SELECT * FROM Quote ORDER BY updatedAt DESC")
    fun quotes(): Flow<List<Quote>>

    @Query("SELECT * FROM Quote WHERE id = :id")
    suspend fun quote(id: Long): Quote?

    @Query("SELECT * FROM Quote WHERE signToken != '' AND signedAt IS NULL")
    suspend fun quotesAwaitingSignature(): List<Quote>

    @Query("SELECT * FROM Customer WHERE id = :id")
    suspend fun customer(id: Long): Customer?

    @Insert suspend fun insertQuote(q: Quote): Long
    @Update suspend fun updateQuote(q: Quote)
    @Delete suspend fun deleteQuote(q: Quote)

    @Query("SELECT * FROM QuoteLine")
    fun allLines(): Flow<List<QuoteLine>>

    @Query("SELECT * FROM QuoteLine WHERE quoteId = :quoteId")
    suspend fun linesFor(quoteId: Long): List<QuoteLine>

    @Insert suspend fun insertLine(l: QuoteLine): Long
    @Update suspend fun updateLine(l: QuoteLine)
    @Delete suspend fun deleteLine(l: QuoteLine)

    @Query("DELETE FROM QuoteLine WHERE quoteId = :quoteId")
    suspend fun deleteLinesFor(quoteId: Long)

    @Insert suspend fun insertMove(m: StockMove): Long

    @Query("SELECT * FROM StockMove ORDER BY at DESC LIMIT 200")
    fun recentMoves(): Flow<List<StockMove>>

    @Query("SELECT * FROM Expense ORDER BY date DESC")
    fun expenses(): Flow<List<Expense>>

    @Insert suspend fun insertExpense(e: Expense): Long
    @Update suspend fun updateExpense(e: Expense)

    @Query("SELECT * FROM Expense")
    suspend fun expensesNow(): List<Expense>
    @Delete suspend fun deleteExpense(e: Expense)

    @Query("SELECT * FROM Settings WHERE id = 1")
    fun settings(): Flow<Settings?>

    @Query("SELECT * FROM Settings WHERE id = 1")
    suspend fun settingsNow(): Settings?

    @Upsert suspend fun saveSettings(s: Settings)

    @Query("SELECT * FROM Attachment ORDER BY addedAt")
    fun attachments(): Flow<List<Attachment>>

    @Query("SELECT * FROM Attachment WHERE expenseId = :expenseId")
    suspend fun attachmentsFor(expenseId: Long): List<Attachment>

    @Insert suspend fun insertAttachment(a: Attachment): Long
    @Delete suspend fun deleteAttachment(a: Attachment)
}

/** Version 2 adds receipt attachments. Keeps all existing data. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `Attachment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `expenseId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `mime` TEXT NOT NULL, `path` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)")
    }
}

/** Version 3 adds a photo to each stock item. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Material` ADD COLUMN `photoPath` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 4 remembers barcodes for each stock item. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Material` ADD COLUMN `barcodes` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 5 adds customer e-signatures. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("signToken", "signUrl", "signerName", "signerIp", "signaturePath").forEach {
            db.execSQL("ALTER TABLE `Quote` ADD COLUMN `$it` TEXT NOT NULL DEFAULT ''")
        }
        listOf("sentAt", "viewedAt", "signedAt").forEach { db.execSQL("ALTER TABLE `Quote` ADD COLUMN `$it` INTEGER") }
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `signKey` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 8 keeps the item lines from each receipt, so stock prices can come from what you paid. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Expense` ADD COLUMN `items` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 7 adds "comes in a box" with pieces per box. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Material` ADD COLUMN `packSize` REAL NOT NULL DEFAULT 0")
    }
}

/** Version 6 adds the "How I charge" note. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `chargeNote` TEXT NOT NULL DEFAULT '" + CHARGE_NOTE.replace("'", "''") + "'")
    }
}

@Database(
    entities = [Customer::class, Material::class, Quote::class, QuoteLine::class, StockMove::class, Expense::class, Settings::class, Attachment::class],
    version = 8,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "zimdars.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build().also { instance = it }
        }
    }
}
