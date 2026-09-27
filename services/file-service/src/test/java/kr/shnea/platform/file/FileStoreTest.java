package kr.shnea.platform.file;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class FileStoreTest {
    private final UUID id = UUID.randomUUID(), project = UUID.randomUUID(), environment = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"사진.JPG,images,jpg", "영상.MP4,videos,mp4", "sound.flac,audio,flac",
        "안내.hwpx,documents,hwpx", "backup.tar.gz,archives,gz", "model.blend,others,blend",
        "README,others,bin", ".env,others,bin", "bad.a:b,others,bin", "bad.abcdefghijklmnopq,others,bin"})
    void categoryUsesSafeCaseInsensitiveExtension(String name, String type, String extension) {
        assertThat(FileStore.storagePath(id, project, environment, name, Instant.parse("2026-09-27T00:00:00Z")))
            .isEqualTo(type + "/2026/09/27/" + project + "/" + environment + "/" + id + "/original." + extension);
    }

    @Test void dateSwitchesAtKoreanMidnightAndNamesNeverBecomeDirectories() {
        assertThat(FileStore.storagePath(id, project, environment, "a.png", Instant.parse("2026-09-26T14:59:59Z")))
            .startsWith("images/2026/09/26/");
        assertThat(FileStore.storagePath(id, project, environment, "../../private/a.png", Instant.parse("2026-09-26T15:00:00Z")))
            .startsWith("images/2026/09/27/").doesNotContain("..", "private");
        assertThat(FileStore.storagePath(id, project, environment, "a.png/../../secret", Instant.EPOCH))
            .startsWith("others/").endsWith("/original.bin").doesNotContain("..");
    }
}
