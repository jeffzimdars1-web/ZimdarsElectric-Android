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

/** A quote is a priced job; an appointment is a visit booked before you know the work (no prices or materials). */
object Kind {
    const val QUOTE = "QUOTE"
    const val APPT = "APPT"
}

object JobType {
    /** Capital improvement under NY sales tax law: no sales tax on the bill, customer signs ST-124. */
    const val INSTALL = "INSTALL"
    /** Repair / maintenance: sales tax charged on the whole bill. */
    const val REPAIR = "REPAIR"
    /** Both on one bill: install and repair listed separately, sales tax only on the repair part. */
    const val BOTH = "BOTH"
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
    /** Other properties they own, one per line: "name<TAB>address" (e.g. "Remsen rental	123 Main St"). */
    @ColumnInfo(defaultValue = "") val properties: String = "",
) {
    data class Property(val label: String, val address: String)

    /** Extra properties beyond the main [address]. */
    val otherProperties: List<Property>
        get() = properties.lines().mapNotNull { ln ->
            val parts = ln.split('\t', limit = 2)
            val addr = parts.getOrNull(1)?.trim().orEmpty().ifBlank { parts[0].trim() }
            if (addr.isBlank()) null else Property(if (parts.size == 2) parts[0].trim() else "", addr)
        }

    /** The main address first, then the others. */
    val allProperties: List<Property>
        get() = (if (address.isNotBlank()) listOf(Property("Main", address)) else emptyList()) + otherProperties

    fun withProperties(list: List<Property>) = copy(properties = list.filter { it.address.isNotBlank() }
        .joinToString("\n") { "${it.label.replace('\t', ' ').replace('\n', ' ').trim()}\t${it.address.replace('\t', ' ').replace('\n', ' ').trim()}" })
}

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
    /** Receipt names and store item numbers that are this item (one per line), so receipts can add to stock by themselves. */
    @ColumnInfo(defaultValue = "") val aliases: String = "",
) {
    fun matchesReceipt(keys: List<String>) = keys.any { k -> aliases.lines().any { it == k } }
    fun withAliases(keys: List<String>) = copy(aliases = (aliases.lines() + keys).map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
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
    /** When the job starts (date + time). Null = not scheduled. */
    val startAt: Long? = null,
    @ColumnInfo(defaultValue = "QUOTE") val kind: String = Kind.QUOTE,
    /** Text the customer a reminder the evening before and an hour before (from your phone). */
    @ColumnInfo(defaultValue = "0") val textCustomer: Boolean = false,
    /** When the customer tapped "Confirm appointment" on the confirmation page. */
    val confirmedAt: Long? = null,
    /** Where the job is, for arrival and the map (from the address). */
    val siteLat: Double? = null,
    val siteLon: Double? = null,
    /** When you got there and when you left (job clock). */
    val arrivedAt: Long? = null,
    val leftAt: Long? = null,
    /** False when only the day is set ("time TBD"). */
    @ColumnInfo(defaultValue = "1") val hasTime: Boolean = true,
    /** Expected hours on site. 0 = use the quote's labor hours. */
    @ColumnInfo(defaultValue = "0") val hoursOnSite: Double = 0.0,
    /** Install + repair jobs: labor hours for the repair part ([laborHours] is the install part). */
    @ColumnInfo(defaultValue = "0") val repairHours: Double = 0.0,
    /** Install + repair jobs: what the repair work is (the customer sees this). */
    @ColumnInfo(defaultValue = "") val repairNotes: String = "",
    // NY Form ST-124 (capital improvement), signed online by the customer.
    @ColumnInfo(defaultValue = "") val st124Token: String = "",
    @ColumnInfo(defaultValue = "") val st124Url: String = "",
    val st124SentAt: Long? = null,
    val st124SignedAt: Long? = null,
    /** The filled-in, signed ST-124 PDF on the phone. */
    @ColumnInfo(defaultValue = "") val st124Path: String = "",
    /** Change order: the job this adds to. */
    val parentId: Long? = null,
    // Invoice sent by text, with automatic reminders.
    @ColumnInfo(defaultValue = "") val invoiceToken: String = "",
    @ColumnInfo(defaultValue = "") val invoiceUrl: String = "",
    val invoiceSentAt: Long? = null,
    val invoiceRemindedAt: Long? = null,
    /** Text the customer a review link the day after the job is done. */
    @ColumnInfo(defaultValue = "1") val askReview: Boolean = true,
    val reviewAskedAt: Long? = null,
    /** Your own note about the visit (gate code, call first, bring the ladder). Never sent to the customer. */
    @ColumnInfo(defaultValue = "") val privateNote: String = "",
    /** Appointment: the customer asked for a different day/time on the confirmation page. */
    val rescheduleRequestedAt: Long? = null,
    val rescheduleWhen: Long? = null,
    @ColumnInfo(defaultValue = "") val rescheduleText: String = "",
    @ColumnInfo(defaultValue = "") val rescheduleNote: String = "",
) {
    /** A new-time request that came in after the last confirmation was sent and hasn't been handled. */
    val hasRescheduleRequest: Boolean get() = rescheduleRequestedAt != null && rescheduleText.isNotBlank() && rescheduleRequestedAt > (sentAt ?: 0L)
    /** Install work here needs a signed ST-124 (new install, or the install part of install + repair). */
    val needsSt124: Boolean get() = kind == Kind.QUOTE && (jobType == JobType.INSTALL || jobType == JobType.BOTH)
    /** Hours to block on the calendar. */
    val blockHours: Double get() = if (hoursOnSite > 0) hoursOnSite else laborHours + if (jobType == JobType.BOTH) repairHours else 0.0
    val awaitingSignature: Boolean get() = signToken.isNotBlank() && signedAt == null && confirmedAt == null
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
    /** On an install + repair job: this item is for the repair part (taxed). */
    @ColumnInfo(defaultValue = "0") val repair: Boolean = false,
)

/** One business drive for the mileage log. */
@Entity
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val miles: Double,
    @ColumnInfo(defaultValue = "") val fromLabel: String = "",
    @ColumnInfo(defaultValue = "") val toLabel: String = "",
    val quoteId: Long? = null,
    @ColumnInfo(defaultValue = "") val purpose: String = "",
    /** Typed in by you instead of tracked by GPS. */
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
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
    /** Phone contacts you chose not to add as customers (last 10 digits of their number, one per line). */
    @ColumnInfo(defaultValue = "") val skippedContacts: String = "",
    /** Text the customer "I'm here" when GPS sees you arrive. */
    @ColumnInfo(defaultValue = "0") val textOnArrival: Boolean = false,
    /** Your first name, used in reminder texts to customers. */
    @ColumnInfo(defaultValue = "Jeff") val ownerName: String = "Jeff",
    /** "Give or take" minutes in customer reminder texts. */
    @ColumnInfo(defaultValue = "15") val arrivalWindow: Int = 15,
    /** Job reminders: the evening before (at this hour) and this many minutes before the start. */
    @ColumnInfo(defaultValue = "1") val remindNightBefore: Boolean = true,
    @ColumnInfo(defaultValue = "19") val remindNightHour: Int = 19,
    @ColumnInfo(defaultValue = "60") val remindMinutesBefore: Int = 60,
    /** Key that lets this phone create signing links. Kept only on the phone. */
    @ColumnInfo(defaultValue = "") val signKey: String = "",
    // Getting paid
    @ColumnInfo(defaultValue = "") val venmo: String = "",
    @ColumnInfo(defaultValue = "") val zelle: String = "",
    /** Extra payment line on invoices (e.g. "Checks payable to Zimdars Electric LLC"). */
    @ColumnInfo(defaultValue = "") val payNote: String = "",
    @ColumnInfo(defaultValue = "7") val invoiceReminderDays: Int = 7,
    /** Google review link for the review request text. Blank = don't ask. */
    @ColumnInfo(defaultValue = "") val reviewUrl: String = "",
    // Taxes (estimates)
    /** Your federal income tax bracket, for the quarterly estimate. */
    @ColumnInfo(defaultValue = "12") val fedTaxPct: Double = 12.0,
    /** NY State income tax, roughly. */
    @ColumnInfo(defaultValue = "5.5") val stateTaxPct: Double = 5.5,
    /** NY sales tax Certificate of Authority number (prints on ST-124). */
    @ColumnInfo(defaultValue = "") val coaNumber: String = "",
    /** Your own signature (drawn once), for the contractor line on ST-124. */
    @ColumnInfo(defaultValue = "") val ownerSignaturePath: String = "",
)

/** A photo taken on a job (before / after / problem found). */
@Entity
data class JobPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoteId: Long,
    val path: String,
    val takenAt: Long = System.currentTimeMillis(),
    val caption: String = "",
    /** Include on the customer's PDF. */
    val onCustomerCopy: Boolean = false,
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

    @Query("SELECT * FROM JobPhoto ORDER BY takenAt") fun photos(): Flow<List<JobPhoto>>
    @Query("SELECT * FROM JobPhoto WHERE quoteId = :quoteId ORDER BY takenAt") suspend fun photosFor(quoteId: Long): List<JobPhoto>
    @Insert suspend fun insertPhoto(p: JobPhoto): Long
    @Update suspend fun updatePhoto(p: JobPhoto)
    @Delete suspend fun deletePhoto(p: JobPhoto)
    @Query("SELECT * FROM Quote WHERE st124Token != '' AND st124SignedAt IS NULL") suspend fun quotesAwaitingSt124(): List<Quote>

    @Insert suspend fun insertCustomer(c: Customer): Long

    @Query("SELECT * FROM Customer")
    suspend fun customersNow(): List<Customer>
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

    @Query("SELECT * FROM Quote")
    suspend fun quotesNow(): List<Quote>

    @Query("SELECT * FROM Quote WHERE signToken != '' AND signedAt IS NULL AND confirmedAt IS NULL")
    suspend fun quotesAwaitingSignature(): List<Quote>

    @Query("SELECT * FROM Customer WHERE id = :id")
    suspend fun customer(id: Long): Customer?

    @Insert suspend fun insertQuote(q: Quote): Long
    @Update suspend fun updateQuote(q: Quote)
    @Delete suspend fun deleteQuote(q: Quote)

    @Query("SELECT * FROM QuoteLine")
    fun allLines(): Flow<List<QuoteLine>>

    @Query("SELECT * FROM QuoteLine")
    suspend fun allLinesNow(): List<QuoteLine>

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

    @Query("SELECT * FROM Expense WHERE id = :id")
    suspend fun expense(id: Long): Expense?

    @Query("SELECT * FROM Material")
    suspend fun materialsNow(): List<Material>
    @Delete suspend fun deleteExpense(e: Expense)

    @Query("SELECT * FROM Settings WHERE id = 1")
    fun settings(): Flow<Settings?>

    @Query("SELECT * FROM Settings WHERE id = 1")
    suspend fun settingsNow(): Settings?

    @Upsert suspend fun saveSettings(s: Settings)

    @Query("SELECT * FROM Trip ORDER BY startedAt DESC")
    fun trips(): Flow<List<Trip>>
    @Insert suspend fun insertTrip(t: Trip): Long
    @Update suspend fun updateTrip(t: Trip)
    @Delete suspend fun deleteTrip(t: Trip)

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

/** Version 16 adds appointment confirmations. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `confirmedAt` INTEGER")
    }
}

/** Version 21: customer can ask for a different appointment time. */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `rescheduleRequestedAt` INTEGER")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `rescheduleWhen` INTEGER")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `rescheduleText` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `rescheduleNote` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 20: your own note on a job or appointment. */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `privateNote` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 19: ST-124 signing, change orders, invoices, review requests, job photos, tax settings. */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "ALTER TABLE `Quote` ADD COLUMN `st124Token` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Quote` ADD COLUMN `st124Url` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Quote` ADD COLUMN `st124SentAt` INTEGER",
            "ALTER TABLE `Quote` ADD COLUMN `st124SignedAt` INTEGER",
            "ALTER TABLE `Quote` ADD COLUMN `st124Path` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Quote` ADD COLUMN `parentId` INTEGER",
            "ALTER TABLE `Quote` ADD COLUMN `invoiceToken` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Quote` ADD COLUMN `invoiceUrl` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Quote` ADD COLUMN `invoiceSentAt` INTEGER",
            "ALTER TABLE `Quote` ADD COLUMN `invoiceRemindedAt` INTEGER",
            "ALTER TABLE `Quote` ADD COLUMN `askReview` INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE `Quote` ADD COLUMN `reviewAskedAt` INTEGER",
            "ALTER TABLE `Settings` ADD COLUMN `venmo` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Settings` ADD COLUMN `zelle` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Settings` ADD COLUMN `payNote` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Settings` ADD COLUMN `invoiceReminderDays` INTEGER NOT NULL DEFAULT 7",
            "ALTER TABLE `Settings` ADD COLUMN `reviewUrl` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Settings` ADD COLUMN `fedTaxPct` REAL NOT NULL DEFAULT 12",
            "ALTER TABLE `Settings` ADD COLUMN `stateTaxPct` REAL NOT NULL DEFAULT 5.5",
            "ALTER TABLE `Settings` ADD COLUMN `coaNumber` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `Settings` ADD COLUMN `ownerSignaturePath` TEXT NOT NULL DEFAULT ''",
            "CREATE TABLE IF NOT EXISTS `JobPhoto` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `quoteId` INTEGER NOT NULL, `path` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, `caption` TEXT NOT NULL, `onCustomerCopy` INTEGER NOT NULL)",
        ).forEach { db.execSQL(it) }
    }
}

/** Version 18 lets a customer have more than one property. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Customer` ADD COLUMN `properties` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 17 lets one job be part new install, part repair. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `repairHours` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `repairNotes` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `QuoteLine` ADD COLUMN `repair` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 15 remembers phone contacts you chose not to add. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `skippedContacts` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 14 adds GPS: job locations, job clock, mileage log. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("siteLat", "siteLon").forEach { db.execSQL("ALTER TABLE `Quote` ADD COLUMN `$it` REAL") }
        listOf("arrivedAt", "leftAt").forEach { db.execSQL("ALTER TABLE `Quote` ADD COLUMN `$it` INTEGER") }
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `textOnArrival` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `Trip` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `miles` REAL NOT NULL, `fromLabel` TEXT NOT NULL DEFAULT '', `toLabel` TEXT NOT NULL DEFAULT '', `quoteId` INTEGER, `purpose` TEXT NOT NULL DEFAULT '', `manual` INTEGER NOT NULL DEFAULT 0)")
    }
}

/** Version 13 adds appointments and customer reminder texts. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'QUOTE'")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `textCustomer` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `ownerName` TEXT NOT NULL DEFAULT 'Jeff'")
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `arrivalWindow` INTEGER NOT NULL DEFAULT 15")
    }
}

/** Version 12 lets a job be on a day with no set start time. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `hasTime` INTEGER NOT NULL DEFAULT 1")
    }
}

/** Version 11 adds job reminder settings. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `remindNightBefore` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `remindNightHour` INTEGER NOT NULL DEFAULT 19")
        db.execSQL("ALTER TABLE `Settings` ADD COLUMN `remindMinutesBefore` INTEGER NOT NULL DEFAULT 60")
    }
}

/** Version 10 adds a real start date/time for the calendar. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `startAt` INTEGER")
        db.execSQL("ALTER TABLE `Quote` ADD COLUMN `hoursOnSite` REAL NOT NULL DEFAULT 0")
    }
}

/** Version 9 lets stock items remember their receipt names. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Material` ADD COLUMN `aliases` TEXT NOT NULL DEFAULT ''")
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
    entities = [Customer::class, Material::class, Quote::class, QuoteLine::class, StockMove::class, Expense::class, Settings::class, Attachment::class, Trip::class, JobPhoto::class],
    version = 21,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        /** Closes the database (before a restore replaces it). */
        fun close() = synchronized(this) { instance?.close(); instance = null }
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "zimdars.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                .build().also { instance = it }
        }
    }
}
