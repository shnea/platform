package kr.shnea.platform.file;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class DownloadUsageTest {
    @Test void concurrentCompletionsShareOneWriterAndDeduplicateTheNextBatch() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var batches=new CopyOnWriteArrayList<Set<UUID>>();
        var usage=new DownloadUsage(ids->{
            batches.add(Set.copyOf(ids));
            if(batches.size()==1) {entered.countDown();await(release);}
        });
        UUID first=UUID.randomUUID(),duplicate=UUID.randomUUID(),other=UUID.randomUUID();
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var initial=threads.submit(()->usage.record(first));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var next=new ArrayList<Future<?>>();
                for(int index=0;index<12;index++)next.add(threads.submit(()->usage.record(duplicate)));
                next.add(threads.submit(()->usage.record(other)));
                until(()->pending(usage)==13);
                assertThat(batches).hasSize(1);
                assertThat(next).allMatch(result->!result.isDone());
                release.countDown();initial.get(3,TimeUnit.SECONDS);
                for(var result:next)result.get(3,TimeUnit.SECONDS);
                assertThat(batches).containsExactly(Set.of(first),Set.of(duplicate,other));
            } finally {release.countDown();}
        }
    }

    @Test void failedBatchReachesEveryCallerWithoutRetryAndNextBatchCanProceed() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var attempts=new AtomicInteger();var failure=new IllegalStateException("test persistence failure");
        var usage=new DownloadUsage(ids->{
            int attempt=attempts.incrementAndGet();
            if(attempt==1) {entered.countDown();await(release);}
            if(attempt==2)throw failure;
        });
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var initial=threads.submit(()->usage.record(UUID.randomUUID()));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var left=threads.submit(()->usage.record(UUID.randomUUID()));
                var right=threads.submit(()->usage.record(UUID.randomUUID()));
                until(()->pending(usage)==2);release.countDown();initial.get(3,TimeUnit.SECONDS);
                assertThatThrownBy(()->left.get(3,TimeUnit.SECONDS)).hasCause(failure);
                assertThatThrownBy(()->right.get(3,TimeUnit.SECONDS)).hasCause(failure);
                assertThat(attempts.get()).isEqualTo(2);
                usage.record(UUID.randomUUID());assertThat(attempts.get()).isEqualTo(3);
            } finally {release.countDown();}
        }
    }

    @Test void overloadIsBoundedAndEveryAcceptedCompletionIsPersisted() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var batches=new CopyOnWriteArrayList<Set<UUID>>();
        var usage=new DownloadUsage(ids->{
            batches.add(Set.copyOf(ids));
            if(batches.size()==1) {entered.countDown();await(release);}
        });
        var expected=new HashSet<UUID>();
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<?>>();
            UUID first=UUID.randomUUID();expected.add(first);results.add(threads.submit(()->usage.record(first)));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                for(int index=0;index<300;index++) {
                    UUID id=UUID.randomUUID();expected.add(id);results.add(threads.submit(()->usage.record(id)));
                }
                until(()->pending(usage)==255);
                assertThat(batches).hasSize(1);release.countDown();
                for(var result:results)result.get(5,TimeUnit.SECONDS);
                assertThat(batches).allMatch(batch->!batch.isEmpty()&&batch.size()<=128);
                assertThat(batches.stream().flatMap(Set::stream).toList()).containsExactlyInAnyOrderElementsOf(expected);
                assertThat(pending(usage)).isZero();
            } finally {release.countDown();}
        }
    }

    static int pending(DownloadUsage usage) {
        return ((Queue<?>)ReflectionTestUtils.getField(usage,"pending")).size();
    }
    static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    static void await(CountDownLatch latch) {
        try {assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
