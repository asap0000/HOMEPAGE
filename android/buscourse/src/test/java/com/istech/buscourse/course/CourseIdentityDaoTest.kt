package com.istech.buscourse.course

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import android.database.sqlite.SQLiteConstraintException
import com.istech.buscourse.core.data.BusCourseDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CourseIdentityDaoTest {
    private lateinit var db: BusCourseDatabase
    private lateinit var repository: CourseRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CourseRepository(context, db)
    }

    @After fun tearDown() = db.close()

    @Test fun identityCanBeCreatedFoundAndUpdated() = runTest {
        val unidentifiedId = repository.createCourse("legacy", CourseKind.STANDARD)
        assertThat(db.courseDao().getById(unidentifiedId)?.busId).isNull()
        assertThat(db.courseDao().getById(unidentifiedId)?.courseNo).isNull()
        assertThat(db.courseDao().getById(unidentifiedId)?.year).isNull()
        assertThat(db.courseDao().findByIdentity("青", 1, 2026)).isNull()

        val identifiedId = repository.createCourse(
            "identified",
            CourseKind.STANDARD,
            busId = "青",
            courseNo = 1,
            year = 2026,
        )
        assertThat(db.courseDao().findByIdentity("青", 1, 2026)?.id).isEqualTo(identifiedId)

        db.courseDao().updateIdentity(unidentifiedId, "赤", 2, 2026, updatedAt = 123L)
        val updated = db.courseDao().findByIdentity("赤", 2, 2026)
        assertThat(updated?.id).isEqualTo(unidentifiedId)
        assertThat(updated?.updatedAt).isEqualTo(123L)
    }

    /**
     * F-01 回帰: アプリの実書込経路 createCourse でも identity 重複は**例外で弾かれる**こと。
     * かつては @Upsert 経由で無言の -1 返却になっていた（v15 敵対的レビュー）。insert(ABORT) 化で担保。
     */
    @Test fun createCourse_duplicateIdentity_throwsInsteadOfSilentFailure() = runTest {
        val firstId = repository.createCourse("青1便A", CourseKind.STANDARD, busId = "青", courseNo = 1, year = 2026)
        assertThat(firstId).isGreaterThan(0L)
        val thrown: Throwable? = try {
            repository.createCourse("青1便B", CourseKind.STANDARD, busId = "青", courseNo = 1, year = 2026)
            null
        } catch (e: SQLiteConstraintException) {
            e
        }
        assertThat(thrown).isInstanceOf(SQLiteConstraintException::class.java)
        // 重複は1件も増えていない（弾かれた）。
        assertThat(db.courseDao().getAll().count { it.busId == "青" && it.courseNo == 1 && it.year == 2026 }).isEqualTo(1)
    }

    /** identity 無しの新規作成は従来どおり何件でも作れる（NULL は unique 制約で衝突しない）。 */
    @Test fun createCourse_withoutIdentity_allowsMultiple() = runTest {
        val a = repository.createCourse("legacyA", CourseKind.STANDARD)
        val b = repository.createCourse("legacyB", CourseKind.STANDARD)
        assertThat(a).isGreaterThan(0L)
        assertThat(b).isGreaterThan(0L)
        assertThat(b).isNotEqualTo(a)
    }
}
