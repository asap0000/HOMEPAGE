package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreWriteJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `journal deletes written files and database triplet`() {
        val root = temp.newFolder()
        val written = File(root, "files/new.bin").apply { parentFile.mkdirs(); writeText("new") }
        val dbFiles = listOf("db", "db-wal", "db-shm").map { File(root, it).apply { writeText("db") } }
        val journal = RestoreWriteJournal().apply { record(written) }

        assertThat(journal.rollback(dbFiles)).isTrue()
        assertThat((listOf(written) + dbFiles).none { it.exists() }).isTrue()
        assertThat(journal.writtenFiles()).contains(written)
    }

    @Test fun `staged output prefers move`() {
        val root = temp.newFolder()
        val source = File(root, "stage/item").apply { parentFile.mkdirs(); writeText("payload") }
        val destination = File(root, "target/item")

        assertThat(moveOrCopyStagedFile(source, destination)).isTrue()
        assertThat(destination.readText()).isEqualTo("payload")
        assertThat(source.exists()).isFalse()
    }
}
