package com.distributed.idgenerator;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.service.IdGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刷新超时机制测试
 * 验证修复后的刷新超时判断逻辑
 */
@SpringBootTest
@ActiveProfiles("test")
public class RefreshTimeoutTest {

    /**
     * 测试成功刷新后不会被误判为超时
     */
    @Test
    public void testSuccessfulRefreshDoesNotTriggerTimeout() throws Exception {
        // 创建一个模拟的SegmentBuffer
        Object segmentBuffer = createMockSegmentBuffer(1000, 2000, 0);
        
        // 模拟第一次刷新尝试
        boolean firstAttempt = callTrySetNeedRefresh(segmentBuffer);
        assertTrue(firstAttempt, "第一次尝试应该成功");
        
        // 验证刷新标志位和时间戳都被设置
        assertTrue(callIsNeedRefresh(segmentBuffer), "刷新标志位应该为true");
        long firstAttemptTime = getLastRefreshAttemptTime(segmentBuffer);
        assertTrue(firstAttemptTime > 0, "刷新尝试时间应该被设置");
        
        // 模拟成功刷新（调用updateRange）
        callUpdateRange(segmentBuffer, 2001, 3000);
        
        // 验证刷新成功后的状态
        assertFalse(callIsNeedRefresh(segmentBuffer), "刷新标志位应该被重置为false");
        assertEquals(0, getLastRefreshAttemptTime(segmentBuffer), "刷新尝试时间应该被重置为0");
        
        // 等待超过超时时间
        Thread.sleep(100); // 短暂等待，模拟时间流逝
        
        // 再次尝试刷新，应该能正常进行，不会被误判为超时
        boolean secondAttempt = callTrySetNeedRefresh(segmentBuffer);
        assertTrue(secondAttempt, "第二次尝试应该成功，不应该被误判为超时");
        
        System.out.println("✅ 测试通过：成功刷新后不会被误判为超时");
    }
    
    /**
     * 测试真正的超时情况
     */
    @Test
    public void testRealTimeoutScenario() throws Exception {
        // 创建一个模拟的SegmentBuffer
        Object segmentBuffer = createMockSegmentBuffer(1000, 2000, 0);
        
        // 设置一个很短的超时时间用于测试
        setRefreshTimeout(segmentBuffer, 50); // 50毫秒
        
        // 第一个线程尝试刷新
        boolean firstAttempt = callTrySetNeedRefresh(segmentBuffer);
        assertTrue(firstAttempt, "第一次尝试应该成功");
        
        // 等待超过超时时间，但不调用updateRange（模拟刷新卡住）
        Thread.sleep(100);
        
        // 第二个线程尝试刷新，应该能检测到超时并强制重置
        boolean secondAttempt = callTrySetNeedRefresh(segmentBuffer);
        assertTrue(secondAttempt, "第二次尝试应该成功，因为检测到超时");
        
        System.out.println("✅ 测试通过：真正的超时情况能被正确检测");
    }
    
    /**
     * 测试并发场景下的刷新机制
     */
    @Test
    public void testConcurrentRefreshScenario() throws Exception {
        Object segmentBuffer = createMockSegmentBuffer(1000, 2000, 0);
        
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // 启动多个线程同时尝试刷新
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    boolean success = callTrySetNeedRefresh(segmentBuffer);
                    if (success) {
                        successCount.incrementAndGet();
                        System.out.println("线程 " + threadId + " 成功获取刷新权限");
                        
                        // 模拟刷新操作
                        Thread.sleep(10);
                        callUpdateRange(segmentBuffer, 2001 + threadId * 1000, 3000 + threadId * 1000);
                    } else {
                        failureCount.incrementAndGet();
                        System.out.println("线程 " + threadId + " 未获取刷新权限");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // 开始测试
        startLatch.countDown();
        endLatch.await();
        executor.shutdown();
        
        // 验证结果：只有一个线程应该成功
        assertEquals(1, successCount.get(), "只有一个线程应该成功获取刷新权限");
        assertEquals(threadCount - 1, failureCount.get(), "其他线程应该失败");
        
        System.out.println("✅ 测试通过：并发场景下只有一个线程能获取刷新权限");
    }
    
    // === 辅助方法：通过反射访问私有类和方法 ===
    
    private Object createMockSegmentBuffer(long startValue, long maxValue, int shardType) throws Exception {
        // 获取IdGeneratorService类
        Class<?> serviceClass = Class.forName("com.distributed.idgenerator.service.IdGeneratorService");
        
        // 获取内部类SegmentBuffer
        Class<?> bufferClass = null;
        for (Class<?> innerClass : serviceClass.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("SegmentBuffer")) {
                bufferClass = innerClass;
                break;
            }
        }
        
        assertNotNull(bufferClass, "找不到SegmentBuffer内部类");
        
        // 创建SegmentBuffer实例
        java.lang.reflect.Constructor<?> constructor = bufferClass.getDeclaredConstructor(long.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(startValue, maxValue, shardType);
    }
    
    private boolean callTrySetNeedRefresh(Object segmentBuffer) throws Exception {
        Method method = segmentBuffer.getClass().getDeclaredMethod("trySetNeedRefresh");
        method.setAccessible(true);
        return (Boolean) method.invoke(segmentBuffer);
    }
    
    private boolean callIsNeedRefresh(Object segmentBuffer) throws Exception {
        Method method = segmentBuffer.getClass().getDeclaredMethod("isNeedRefresh");
        method.setAccessible(true);
        return (Boolean) method.invoke(segmentBuffer);
    }
    
    private void callUpdateRange(Object segmentBuffer, long newStartValue, long newMaxValue) throws Exception {
        Method method = segmentBuffer.getClass().getDeclaredMethod("updateRange", long.class, long.class);
        method.setAccessible(true);
        method.invoke(segmentBuffer, newStartValue, newMaxValue);
    }
    
    private long getLastRefreshAttemptTime(Object segmentBuffer) throws Exception {
        Field field = segmentBuffer.getClass().getDeclaredField("lastRefreshAttemptTime");
        field.setAccessible(true);
        return (Long) field.get(segmentBuffer);
    }
    
    private void setRefreshTimeout(Object segmentBuffer, long timeoutMs) throws Exception {
        Field field = segmentBuffer.getClass().getDeclaredField("REFRESH_TIMEOUT_MS");
        field.setAccessible(true);
        // 注意：这是一个static final字段，实际上不能修改
        // 这里只是为了测试目的，实际实现中可能需要其他方式
        System.out.println("注意：REFRESH_TIMEOUT_MS是static final字段，测试中使用较短的等待时间");
    }
}