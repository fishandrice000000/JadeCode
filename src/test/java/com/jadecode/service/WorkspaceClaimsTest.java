package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 占用只按顶层归属隔离；相同归属的并行领取不会把 Team 串行化。 */
class WorkspaceClaimsTest{
    @TempDir Path root;
    @Test void sharedTeamOwnerAllowsConcurrentReferencesAndOtherOwnerWaitsForAllReleases()throws Exception{
        var registry=new WorkspaceClaims();var team=registry.owner("leader-and-team");var other=registry.owner("other");
        var first=team.acquire(List.of(root));var second=team.acquire(List.of(root));
        first.close();assertThrows(java.io.IOException.class,()->other.acquire(List.of(root)));
        second.close();try(var third=other.acquire(List.of(root))){assertNotNull(third);}
    }
    @Test void batchConflictLeavesNoPartialClaimAndAncestorsConflict()throws Exception{
        var registry=new WorkspaceClaims();var a=registry.owner("a");var b=registry.owner("b");
        Path nested=Files.createDirectory(root.resolve("nested")),free=Files.createDirectory(root.resolve("free"));
        try(var held=a.acquire(List.of(nested))){
            assertThrows(java.io.IOException.class,()->b.acquire(List.of(free,root)));
            try(var reclaimed=a.acquire(List.of(free))){assertNotNull(reclaimed);}
        }
    }
    @Test void competingAcquisitionsHaveExactlyOneWinner()throws Exception{
        var registry=new WorkspaceClaims();var start=new CountDownLatch(1);
        var won=new AtomicInteger();var leases=new CopyOnWriteArrayList<WorkspaceClaims.Lease>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++){
                var owner=registry.owner("session-"+i);
                futures.add(pool.submit(()->{try{start.await();leases.add(owner.acquire(List.of(root)));won.incrementAndGet();}
                    catch(Exception expected){}finally{}}));
            }
            start.countDown();for(var future:futures)future.get(2,TimeUnit.SECONDS);
            assertEquals(1,won.get());
        }finally{leases.forEach(WorkspaceClaims.Lease::close);}
    }
}
