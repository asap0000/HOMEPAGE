package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupWriteTargetsTest {
    @Test fun `recorded source origin id is passed to manifest and unset remains null`() {
        assertThat(BackupWriteTargets.sourceOriginId("origin-from-restore")).isEqualTo("origin-from-restore")
        assertThat(BackupWriteTargets.sourceOriginId(null)).isNull()
    }
}
