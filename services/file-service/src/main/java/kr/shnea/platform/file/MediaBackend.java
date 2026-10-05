package kr.shnea.platform.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MediaBackend {
    private final boolean remote;
    MediaBackend(@Value("${platform.files.processing-backend:noedaeri}") String backend) {
        if (!backend.equals("noedaeri") && !backend.equals("local")) throw new IllegalArgumentException("Invalid media backend");
        remote = backend.equals("noedaeri");
    }
    boolean remote() { return remote; }
}
