package kr.shnea.platform.project;

import java.util.List;
import java.util.UUID;

final class MockReset {
    static final int BATCH_SIZE = 20;
    record Target(UUID id, String username, String provider) {}
    record Preview(List<Target> items, boolean hasMore, String revision) {}
    record Item(UUID id, String username, String status) {}
    record Result(int requested, int deleted, int failed, List<Item> items) {}
    private MockReset() {}
}
