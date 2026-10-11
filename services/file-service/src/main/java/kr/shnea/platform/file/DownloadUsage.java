package kr.shnea.platform.file;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

final class DownloadUsage {
    private static final int BATCH_SIZE=128;
    private final Semaphore capacity=new Semaphore(256,true);
    private final ConcurrentLinkedQueue<Pending> pending=new ConcurrentLinkedQueue<>();
    private final ReentrantLock writer=new ReentrantLock(true);
    private final Consumer<Set<UUID>> persist;

    DownloadUsage(Consumer<Set<UUID>> persist) {this.persist=persist;}

    void record(UUID id) {
        capacity.acquireUninterruptibly();
        try {
            var request=new Pending(id);
            pending.add(request);
            writer.lock();
            try {
                while(!request.completed)flush();
                if(request.failure!=null)throw request.failure;
            } finally {writer.unlock();}
        } finally {capacity.release();}
    }

    private void flush() {
        var batch=new ArrayList<Pending>();
        var ids=new LinkedHashSet<UUID>();
        for(int count=0;count<BATCH_SIZE;count++) {
            Pending request=pending.poll();
            if(request==null)break;
            batch.add(request);ids.add(request.id);
        }
        RuntimeException failure=null;
        try {persist.accept(ids);}
        catch(RuntimeException error) {failure=error;}
        for(Pending request:batch) {request.failure=failure;request.completed=true;}
    }

    private static final class Pending {
        final UUID id;
        boolean completed;
        RuntimeException failure;
        Pending(UUID id) {this.id=id;}
    }
}
