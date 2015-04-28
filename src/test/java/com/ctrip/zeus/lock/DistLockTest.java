package com.ctrip.zeus.lock;

import com.ctrip.zeus.lock.impl.MysqlDistLock;
import com.ctrip.zeus.util.S;
import org.codehaus.plexus.component.repository.exception.ComponentLifecycleException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.unidal.dal.jdbc.datasource.DataSourceManager;
import org.unidal.dal.jdbc.transaction.TransactionManager;
import org.unidal.lookup.ContainerLoader;
import support.AbstractSpringTest;
import support.MysqlDbServer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by zhoumy on 2015/4/14.
 */
public class DistLockTest extends AbstractSpringTest {
    private static MysqlDbServer mysqlDbServer;

    @BeforeClass
    public static void setUpDb() throws ComponentLookupException, ComponentLifecycleException {
        S.setPropertyDefaultValue("CONF_DIR", new File("").getAbsolutePath() + "/conf/test");
        mysqlDbServer = new MysqlDbServer();
        mysqlDbServer.start();
    }

    @Test
    public void testFunctions() throws InterruptedException {
        final List<Boolean> report = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(4);
        new Thread() {
            public void run() {
                report.add(new MysqlDistLock("slock").tryLock() == true);
                latch.countDown();
            }
        }.run();
        new Thread() {
            public void run() {
                report.add(new MysqlDistLock("slock").tryLock() == false);
                MysqlDistLock lock = new MysqlDistLock("slock1");
                report.add(lock.tryLock() == true);
                lock.unlock();
                latch.countDown();
            }
        }.run();
        new Thread() {
            public void run() {
                new MysqlDistLock("slock1").lock();
                report.add(true);
                latch.countDown();
            }
        }.run();
        new Thread() {
            public void run() {
                try {
                    new MysqlDistLock("slock1").lock(3);
                    report.add(false);
                } catch (Exception e) {
                    report.add(true);
                }
                latch.countDown();
            }
        }.run();

        latch.await();
        Assert.assertEquals(5, report.size());

        for (Boolean result : report) {
            Assert.assertTrue(result);
        }
    }

    @Test
    public void testConcurrentScenario() throws ExecutionException, InterruptedException {
        final List<Boolean> report = new ArrayList<>();
        final int totalThreads = 20;
        ExecutorService es = Executors.newFixedThreadPool(totalThreads);

        List<Future<?>> lockOne = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            lockOne.add(es.submit(new Runnable() {
                @Override
                public void run() {
                    report.add(new MysqlDistLock("clock").tryLock());
                }
            }));
        }
        for (Future f : lockOne) {
            f.get();
        }
        es.shutdown();
        Assert.assertEquals(totalThreads, report.size());
        Assert.assertTrue(report.get(0));
        for (int i = 1; i < report.size(); i++) {
            Assert.assertFalse(report.get(i));
        }

        final List<Boolean> report2 = new ArrayList<>();
        final AtomicLong interval = new AtomicLong(0);
        new Thread() {
            public void run() {
                MysqlDistLock lock = new MysqlDistLock("wait");
                lock.lock();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    report2.add(false);
                }
                report2.add(true);
                lock.unlock();
            }
        }.run();
        new Thread() {
            public void run() {
                MysqlDistLock lock = new MysqlDistLock("wait");
                long start = System.nanoTime();
                lock.lock();
                report2.add(true);
                lock.unlock();
                interval.set(System.nanoTime() - start);
            }
        }.run();
        Assert.assertEquals(report2.size(), 2);
        Assert.assertTrue(report2.get(0));
        Assert.assertTrue(report2.get(1));
        Assert.assertTrue(interval.get() > 10000);
    }

    @Test
    public void testIncorrectExecution() {
        // Assume unlock cannot be done using different instance.
        MysqlDistLock lock = new MysqlDistLock("mistake");
        lock.lock();
        MysqlDistLock anotherLock = new MysqlDistLock("mistake");
        anotherLock.unlock();
        Assert.assertFalse(anotherLock.tryLock());
        lock.unlock();
        Assert.assertTrue(anotherLock.tryLock());
        anotherLock.unlock();
    }

    @AfterClass
    public static void tearDownDb() throws InterruptedException, ComponentLookupException, ComponentLifecycleException {
        mysqlDbServer.stop();

        DataSourceManager ds = ContainerLoader.getDefaultContainer().lookup(DataSourceManager.class);
        ContainerLoader.getDefaultContainer().release(ds);
        TransactionManager ts = ContainerLoader.getDefaultContainer().lookup(TransactionManager.class);
        ContainerLoader.getDefaultContainer().release(ts);
    }
}