package top.msfxp.schedule.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import top.msfxp.schedule.data.model.COURSE_TYPE_PRACTICAL
import top.msfxp.schedule.data.model.COURSE_TYPE_THEORY
import top.msfxp.schedule.data.model.Course
import top.msfxp.schedule.data.model.EXAM_TYPE_FINAL
import top.msfxp.schedule.data.model.ExamFetchResult
import top.msfxp.schedule.data.model.ExamRecord
import top.msfxp.schedule.data.model.RawExamItem
import top.msfxp.schedule.data.model.Semester
import top.msfxp.schedule.data.model.StudentProfile
import top.msfxp.schedule.data.model.buildExamRecordId
import top.msfxp.schedule.data.model.effectiveStartDate
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository

// 同步结果统计数据模型
data class SyncResultSummary(
    val regularCount: Int,
    val practicalCount: Int,
    val examCount: Int = 0
) {
    val totalCount: Int get() = regularCount + practicalCount
}

// 课表与考试并发抓取结果
private data class ScheduleFetchBundle(
    val eamsResult: EamsFetchResult,
    val practicalItems: List<RawPracticalItem>,
    val examResult: ExamFetchResult? = null
)

// 课表数据同步服务协调器
class CqustSyncManager(
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val examRepository: ExamRepository
) {

    // 登录教务并同步课表到本地数据库
    suspend fun syncCourses(
        studentId: String,
        passwordRaw: String,
        targetSemesterId: String? = null,
        allowEmpty: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): Result<SyncResultSummary> = withContext(Dispatchers.IO) {
        val sid = studentId.trim()
        val pwd = passwordRaw.trim()

        if (sid.isEmpty()) return@withContext Result.failure(IllegalArgumentException("学号不能为空"))
        if (pwd.isEmpty()) return@withContext Result.failure(IllegalArgumentException("密码不能为空"))

        // 1. 建立教务传输会话
        val connectResult = CqustCasAuthClient.connect(sid, pwd, onProgress)
        if (connectResult.isFailure) {
            return@withContext Result.failure(connectResult.exceptionOrNull() ?: Exception("教务网络连接失败"))
        }
        val session = connectResult.getOrThrow()

        // 2. 抓取课表与考试数据
        onProgress?.invoke("正在同步课表数据")
        val fetchBundle = fetchScheduleData(session, sid, targetSemesterId, onProgress)
        val eamsResult = fetchBundle.eamsResult
        val practicalItems = fetchBundle.practicalItems

        if (!eamsResult.success) {
            return@withContext Result.failure(Exception(eamsResult.errorMessage ?: "教务课表解析失败"))
        }
        if (!allowEmpty && eamsResult.courses.isEmpty()) {
            return@withContext Result.failure(Exception("返回课程数为 0，不覆盖本地课表"))
        }

        // 3. 构建时空原子矩阵
        val tableId = scheduleRepository.getOrCreateDefaultTableId()
        val (courses, events) = ScheduleMatrixBuilder.build(
            tableId = tableId,
            eamsCourses = eamsResult.courses,
            practicalItems = practicalItems
        )

        // 4. 持久化存储（入库后由 CourseAlarmObserver 统一响应式触发日历同步）
        try {
            scheduleRepository.saveSchedule(tableId, courses, events)
            saveSemesters(eamsResult)
            persistProfileAndCredentials(sid, pwd, eamsResult.profile)
            val examCount = saveExams(
                result = fetchBundle.examResult,
                tableId = tableId,
                semesterId = eamsResult.semesterId.orEmpty(),
                courses = courses
            )

            val regularCount = courses.count { it.courseType == COURSE_TYPE_THEORY }
            val practicalCount = courses.count { it.courseType == COURSE_TYPE_PRACTICAL }
            Result.success(
                SyncResultSummary(
                    regularCount = regularCount,
                    practicalCount = practicalCount,
                    examCount = examCount
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("课表数据入库失败: ${e.message}", e))
        }
    }

    // 并发协同抓取教务常规课表、实践课表与考试安排
    private suspend fun fetchScheduleData(
        session: CqustWebVpnSession,
        studentId: String,
        targetSemesterId: String?,
        onProgress: ((String) -> Unit)?
    ): ScheduleFetchBundle {
        val currentSemesterId = scheduleRepository.getCurrentSemesterOnce()?.id
        val shouldFetchExams = targetSemesterId == null || targetSemesterId == currentSemesterId

        val (eamsResult, practicalResult, examResult) = coroutineScope {
            val eamsDeferred = async {
                CqustEamsParser.fetchAndParse(
                    session = session,
                    studentId = studentId,
                    targetSemesterId = targetSemesterId,
                    onProgress = onProgress
                )
            }
            val practicalDeferred = if (targetSemesterId == null) {
                async {
                    CqustPracticalParser.fetchAndParse(
                        session = session,
                        studentId = studentId,
                        targetYearTerm = null
                    )
                }
            } else null
            val examDeferred = if (shouldFetchExams) {
                async {
                    onProgress?.invoke("正在获取考试安排")
                    CqustExamParser.fetchAndParse(session)
                }
            } else null

            Triple(eamsDeferred.await(), practicalDeferred?.await(), examDeferred?.await())
        }

        var practicalItems = emptyList<RawPracticalItem>()
        if (practicalResult != null && practicalResult.success) {
            practicalItems = practicalResult.items
        } else if (targetSemesterId != null) {
            val semesterId = eamsResult.semesterId ?: eamsResult.semesters.firstOrNull()?.id.orEmpty()
            val semInfo = eamsResult.semesters.find { it.id == semesterId } ?: eamsResult.semesters.firstOrNull()
            val practicalTerm = if (semInfo != null) "${semInfo.schoolYear}-${semInfo.name}" else null

            onProgress?.invoke("正在获取学期实践课表")
            val termResult = CqustPracticalParser.fetchAndParse(session, studentId, practicalTerm)
            if (termResult.success && termResult.items.isNotEmpty()) {
                practicalItems = termResult.items
            }
        }

        return ScheduleFetchBundle(eamsResult, practicalItems, examResult)
    }

    // 保存考试记录并匹配课程归属
    private suspend fun saveExams(
        result: ExamFetchResult?,
        tableId: String,
        semesterId: String,
        courses: List<Course>
    ): Int {
        if (result == null || !result.success || semesterId.isBlank()) return 0
        val now = System.currentTimeMillis()
        val records = result.items.map { item ->
            ExamRecord(
                id = buildExamRecordId(tableId, semesterId, item),
                tableId = tableId,
                semesterId = semesterId,
                courseId = matchCourseId(item, courses),
                examType = item.examType.ifBlank { EXAM_TYPE_FINAL },
                sequence = item.sequence,
                courseCode = item.courseCode,
                courseName = item.courseName,
                examDate = item.examDate,
                week = item.week,
                weekday = item.weekday,
                startTime = item.startTime,
                endTime = item.endTime,
                position = item.position,
                seatNo = item.seatNo,
                status = item.status,
                remark = item.remark,
                arrangeText = item.arrangeText,
                updatedAt = now
            )
        }
        examRepository.replaceSemesterExams(tableId, semesterId, records)
        return records.size
    }

    // 按课程代码与名称匹配考试所属课程
    private fun matchCourseId(item: RawExamItem, courses: List<Course>): String? {
        val theory = courses.filter { it.courseType == COURSE_TYPE_THEORY }
        val practical = courses.filter { it.courseType == COURSE_TYPE_PRACTICAL }
        val baseCode = item.courseCode.substringBefore('.').trim()

        theory.firstOrNull { it.courseCode == item.courseCode }?.let { return it.id }
        theory.firstOrNull { it.courseCode.substringBefore('.').trim() == baseCode }?.let { return it.id }
        practical.firstOrNull { it.courseCode == item.courseCode }?.let { return it.id }
        return (theory.firstOrNull { it.name == item.courseName }
            ?: practical.firstOrNull { it.name == item.courseName })?.id
    }

    // 保存学期列表至本地数据库
    private suspend fun saveSemesters(eamsResult: EamsFetchResult) {
        val semesterId = eamsResult.semesterId ?: eamsResult.semesters.firstOrNull()?.id.orEmpty()
        val semesterEntities = mutableListOf<Semester>()
        for (semInfo in eamsResult.semesters) {
            val existing = scheduleRepository.getSemesterById(semInfo.id)
            val startDate = existing?.startDate?.ifBlank { null }
                ?: semInfo.effectiveStartDate.toString()

            semesterEntities.add(
                Semester(
                    id = semInfo.id,
                    schoolYear = semInfo.schoolYear,
                    name = semInfo.name,
                    label = semInfo.label,
                    startDate = startDate,
                    totalWeeks = existing?.totalWeeks ?: 20,
                    isCurrent = (semInfo.id == semesterId)
                )
            )
        }
        scheduleRepository.clearSemesters()
        scheduleRepository.saveSemesters(semesterEntities)
        scheduleRepository.setActiveSemester(semesterId)
    }

    // 加密持久化档案与凭证
    private suspend fun persistProfileAndCredentials(
        studentId: String,
        passwordRaw: String,
        profile: StudentProfile
    ) {
        val encryptedPassword = LocalCrypto.encrypt(passwordRaw)
        settingsRepository.saveFullProfileAndCredentials(
            studentId = studentId,
            studentName = profile.name,
            college = profile.college,
            major = profile.major,
            className = profile.className,
            passwordEncrypted = encryptedPassword
        )
    }

    // 静默同步课表
    suspend fun silentSync(onProgress: ((String) -> Unit)? = null): Result<SyncResultSummary> {
        val settings = settingsRepository.getAppSettingsOnce()
        if (!settings.isLoggedIn || settings.studentId.isBlank() || settings.passwordEncrypted.isBlank()) {
            return Result.failure(IllegalStateException("尚未保存登录凭证"))
        }
        val pwd = LocalCrypto.decrypt(settings.passwordEncrypted)
        val currentSemester = scheduleRepository.getCurrentSemesterOnce()
        return syncCourses(
            studentId = settings.studentId,
            passwordRaw = pwd,
            targetSemesterId = currentSemester?.id,
            allowEmpty = false,
            onProgress = onProgress
        )
    }

    // 满足冷却时间条件时后台静默同步
    suspend fun silentSyncIfNeeded(cooldownHours: Long = 12L): Result<SyncResultSummary>? {
        val settings = settingsRepository.getAppSettingsOnce()
        if (!settings.isLoggedIn || settings.studentId.isBlank() || settings.passwordEncrypted.isBlank()) {
            return null
        }
        val now = System.currentTimeMillis()
        val lastSync = settings.lastSyncTime
        if ((now - lastSync) < (cooldownHours * 3600L * 1000L)) return null
        val res = silentSync()
        if (res.isSuccess) {
            settingsRepository.updateLastSyncTime(now)
        }
        return res
    }
}

// 本地密码加解密工具 (使用 AndroidKeyStore 硬件保护密钥，AES-GCM 算法)
object LocalCrypto {
    private const val KEY_ALIAS = "CqustMiuixScheduleLocalKey"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateSecretKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as javax.crypto.SecretKey }

        val keyGenerator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // AES-GCM 加密 (输出为 Base64 编码的 IV + 密文)
    fun encrypt(rawText: String): String {
        if (rawText.isEmpty()) return ""
        return try {
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(rawText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("LocalCrypto", "KeyStore encryption failed, fallback to raw", e)
            rawText
        }
    }

    // AES-GCM 解密
    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val combined = android.util.Base64.decode(encryptedText, android.util.Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return encryptedText
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("LocalCrypto", "KeyStore decryption failed, fallback to encrypted", e)
            encryptedText
        }
    }
}
